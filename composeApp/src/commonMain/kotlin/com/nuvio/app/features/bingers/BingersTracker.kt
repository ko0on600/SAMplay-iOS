package com.nuvio.app.features.bingers

import co.touchlab.kermit.Logger
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingAuthProvider
import com.nuvio.app.features.tracking.TrackingCapability
import com.nuvio.app.features.tracking.TrackingHistoryItem
import com.nuvio.app.features.tracking.TrackingHistoryWriter
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingMutationResult
import com.nuvio.app.features.tracking.TrackingProviderDescriptor
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.tracking.TrackingScrobbleEvent
import com.nuvio.app.features.tracking.TrackingScrobbler
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Instant

/** What the Tracking card shows for the active profile. */
data class BingersUiState(
    val isConnected: Boolean = false,
    val accountName: String? = null,
    val isBusy: Boolean = false,
    val error: BingersError? = null,
    val lastRecordedEpochMs: Long? = null,
)

@Serializable
private data class BingersStoredAccount(
    val token: String,
    val name: String? = null,
    val lastRecordedEpochMs: Long? = null,
)

/**
 * Nuvio RS: Bingers (bingers.app) as a write-only tracker. Once the user pastes their personal
 * access token, playback is scrobbled (Bingers records a watch when playback stops at 80 % or
 * more) and episodes or movies marked watched by hand are added to the history; unmarking
 * removes them. Bingers is never a library or progress source.
 */
object BingersTracker : TrackingAuthProvider, TrackingHistoryWriter, TrackingScrobbler {
    private val log = Logger.withTag("Bingers")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val api by lazy { BingersApi() }
    private val lock = SynchronizedObject()
    private var loadedProfileId: Int? = null
    private var stored: BingersStoredAccount? = null
    private var generation = 0L

    private val authenticated = MutableStateFlow(false)
    private val mutableState = MutableStateFlow(BingersUiState())

    val state: StateFlow<BingersUiState> = mutableState.asStateFlow()

    override val descriptor = TrackingProviderDescriptor(
        id = TrackingProviderId.BINGERS,
        displayName = "Bingers",
        capabilities = setOf(
            TrackingCapability.AUTHENTICATION,
            TrackingCapability.WATCHED_WRITE,
            TrackingCapability.SCROBBLE,
        ),
    )
    override val providerId: TrackingProviderId = TrackingProviderId.BINGERS
    override val isAuthenticated: StateFlow<Boolean> = authenticated.asStateFlow()
    override val accountGeneration: Long
        get() = synchronized(lock) { generation }

    fun register() {
        if (TrackingProviderRegistry.authProvider(providerId) === this) return
        TrackingProviderRegistry.register(this)
        TrackingProviderRegistry.registerHistoryWriter(this)
        TrackingProviderRegistry.registerScrobbler(this)
    }

    override fun ensureLoaded() {
        val profileId = ProfileRepository.activeProfileId
        synchronized(lock) {
            if (loadedProfileId == profileId) return
            // Before the app has opened its storage, nothing is cached so the next call reads it.
            if (!PlatformBingersTokenPersistence.isReady()) return
            loadedProfileId = profileId
            stored = readStored(profileId)
            generation++
            publishLocked(error = null)
        }
    }

    override fun onProfileChanged() {
        synchronized(lock) { loadedProfileId = null }
        ensureLoaded()
    }

    override fun clearLocalState() {
        synchronized(lock) {
            runCatching { PlatformBingersTokenPersistence.clear() }
                .onFailure { log.w(it) { "Could not clear Bingers tokens" } }
            stored = null
            generation++
            publishLocked(error = null)
        }
    }

    override fun removeStoredProfile(profileId: Int) {
        synchronized(lock) {
            runCatching { PlatformBingersTokenPersistence.write(profileId, null) }
                .onFailure { log.w(it) { "Could not remove the Bingers token of profile $profileId" } }
            if (loadedProfileId == profileId) {
                stored = null
                generation++
                publishLocked(error = null)
            }
        }
    }

    /**
     * Checks [rawToken] with `GET /me` and keeps it for the active profile. Returns the error to
     * show, or null once connected.
     */
    suspend fun connect(rawToken: String): BingersError? {
        ensureLoaded()
        val token = rawToken.trim()
        if (token.isEmpty() || token.any(Char::isWhitespace) || token.length > 512) {
            setError(BingersError.INVALID_TOKEN)
            return BingersError.INVALID_TOKEN
        }
        val profileId = ProfileRepository.activeProfileId
        mutableState.update { it.copy(isBusy = true, error = null) }
        val account = try {
            api.me(token)
        } catch (error: CancellationException) {
            mutableState.update { it.copy(isBusy = false) }
            throw error
        } catch (error: Throwable) {
            val reason = (error as? BingersApiException)?.error ?: BingersError.NETWORK
            log.w { "Bingers sign-in check failed: $reason" }
            mutableState.update { it.copy(isBusy = false, error = reason) }
            return reason
        }
        if (account.canWrite == false) {
            mutableState.update { it.copy(isBusy = false, error = BingersError.READ_ONLY_TOKEN) }
            return BingersError.READ_ONLY_TOKEN
        }
        synchronized(lock) {
            if (loadedProfileId != profileId) {
                publishLocked(error = null)
                return null
            }
            val value = BingersStoredAccount(token = token, name = account.name)
            try {
                PlatformBingersTokenPersistence.write(profileId, json.encodeToString(BingersStoredAccount.serializer(), value))
            } catch (error: Throwable) {
                log.e(error) { "Could not save the Bingers token" }
                publishLocked(error = BingersError.NETWORK)
                return BingersError.NETWORK
            }
            stored = value
            generation++
            publishLocked(error = null)
        }
        log.i { "Bingers connected for profile $profileId" }
        return null
    }

    fun disconnect() {
        ensureLoaded()
        synchronized(lock) {
            val profileId = loadedProfileId ?: return
            runCatching { PlatformBingersTokenPersistence.write(profileId, null) }
                .onFailure { log.w(it) { "Could not remove the Bingers token" } }
            stored = null
            generation++
            publishLocked(error = null)
        }
    }

    fun clearError() {
        mutableState.update { it.copy(error = null) }
    }

    // ---- TrackingScrobbler ----

    override suspend fun scrobble(profileId: Int, action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
        val token = tokenFor(profileId) ?: return
        val target = bingersTarget(event.media) ?: run {
            log.d { "Bingers can't address ${event.media.stableKey}; not scrobbled" }
            return
        }
        val records = action == TrackingScrobbleAction.STOP && event.progressPercent >= RECORD_PERCENT
        try {
            api.playback(token, action.wireValue, target, event.progressPercent)
            if (records) noteRecorded(profileId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: BingersApiException) {
            handleFailure(error)
            // A finished play must not be lost to a busy minute or a dropped connection: add it to
            // the history instead, a little later (a repeat within 12 hours is answered "already").
            if (records && error.error in RETRYABLE) retryAsHistory(profileId, target, nowIso(), error.retryAfterEpochMs)
            throw error
        }
    }

    // ---- TrackingHistoryWriter ----

    override suspend fun addToHistory(profileId: Int, items: Collection<TrackingHistoryItem>): TrackingMutationResult {
        val token = tokenFor(profileId) ?: return TrackingMutationResult(attemptedCount = 0)
        val targets = items.mapNotNull { item ->
            bingersTarget(item.media)?.let { target -> target to (item.watchedAtEpochMs?.let(::isoTime)) }
        }
        val unaddressable = items.size - targets.size
        if (targets.isEmpty()) return TrackingMutationResult(attemptedCount = items.size, notFoundCount = unaddressable)
        var unresolved = 0
        targets.chunked(BingersApi.MAX_ITEMS_PER_REQUEST).forEach { chunk ->
            val results = runApi { api.addHistory(token, chunk) }
            unresolved += results.count { !it.isResolved }
        }
        noteRecorded(profileId)
        return TrackingMutationResult(attemptedCount = items.size, notFoundCount = unaddressable + unresolved)
    }

    override suspend fun removeFromHistory(profileId: Int, items: Collection<TrackingMediaReference>): TrackingMutationResult {
        val token = tokenFor(profileId) ?: return TrackingMutationResult(attemptedCount = 0)
        val targets = items.mapNotNull(::bingersTarget)
        val unaddressable = items.size - targets.size
        var unresolved = 0
        targets.chunked(BingersApi.MAX_ITEMS_PER_REQUEST).forEach { chunk ->
            val results = runApi { api.unwatch(token, chunk) }
            unresolved += results.count { !it.isResolved }
        }
        return TrackingMutationResult(attemptedCount = items.size, notFoundCount = unaddressable + unresolved)
    }

    // ---- internals ----

    private fun tokenFor(profileId: Int): String? {
        ensureLoaded()
        return synchronized(lock) { stored?.token?.takeIf { loadedProfileId == profileId } }
    }

    private suspend fun <T> runApi(block: suspend () -> T): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: BingersApiException) {
        handleFailure(error)
        throw error
    }

    private fun handleFailure(error: BingersApiException) {
        log.w { "Bingers request failed: ${error.error} (${error.status}) ${error.message.orEmpty()}" }
        if (error.error == BingersError.INVALID_TOKEN) {
            // Bingers tokens don't expire, so a 401 means it was revoked: stop sending it.
            synchronized(lock) {
                val profileId = loadedProfileId
                if (profileId != null) runCatching { PlatformBingersTokenPersistence.write(profileId, null) }
                stored = null
                generation++
                publishLocked(error = BingersError.INVALID_TOKEN)
            }
        } else if (error.error == BingersError.NOT_ENABLED) {
            setError(BingersError.NOT_ENABLED)
        }
    }

    private fun retryAsHistory(profileId: Int, target: BingersTarget, watchedAt: String, retryAfterEpochMs: Long?) {
        scope.launch {
            var waitMs = retryAfterEpochMs?.let { it - nowMs() }?.coerceIn(5_000L, 15 * 60_000L) ?: 30_000L
            repeat(RETRY_ATTEMPTS) { attempt ->
                delay(waitMs)
                val token = tokenFor(profileId) ?: return@launch
                try {
                    val result = api.addHistory(token, listOf(target to watchedAt)).firstOrNull()
                    log.i { "Bingers recorded a play on retry ${attempt + 1}: ${result?.status}/${result?.outcome}" }
                    noteRecorded(profileId)
                    return@launch
                } catch (error: CancellationException) {
                    throw error
                } catch (error: BingersApiException) {
                    if (error.error !in RETRYABLE) {
                        handleFailure(error)
                        return@launch
                    }
                    waitMs = error.retryAfterEpochMs?.let { it - nowMs() }?.coerceIn(5_000L, 15 * 60_000L)
                        ?: (waitMs * 2).coerceAtMost(15 * 60_000L)
                }
            }
            log.w { "Bingers play not recorded after $RETRY_ATTEMPTS retries" }
        }
    }

    private fun noteRecorded(profileId: Int) {
        synchronized(lock) {
            val current = stored ?: return
            if (loadedProfileId != profileId) return
            val updated = current.copy(lastRecordedEpochMs = nowMs())
            runCatching {
                PlatformBingersTokenPersistence.write(profileId, json.encodeToString(BingersStoredAccount.serializer(), updated))
            }
            stored = updated
            publishLocked(error = null)
        }
    }

    private fun setError(error: BingersError) {
        mutableState.update { it.copy(isBusy = false, error = error) }
    }

    private fun readStored(profileId: Int): BingersStoredAccount? {
        val raw = runCatching { PlatformBingersTokenPersistence.read(profileId) }
            .onFailure { log.w(it) { "Could not read the Bingers token" } }
            .getOrNull() ?: return null
        return runCatching { json.decodeFromString(BingersStoredAccount.serializer(), raw) }.getOrNull()
            ?.takeIf { it.token.isNotBlank() }
    }

    /** Called with [lock] held. */
    private fun publishLocked(error: BingersError?) {
        val account = stored
        authenticated.value = account != null
        mutableState.value = BingersUiState(
            isConnected = account != null,
            accountName = account?.name,
            isBusy = false,
            error = error,
            lastRecordedEpochMs = account?.lastRecordedEpochMs,
        )
    }

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    private fun nowIso(): String = isoTime(nowMs())

    /** RFC 3339 in whole seconds, e.g. 2026-11-15T21:00:00Z. */
    private fun isoTime(epochMs: Long): String = Instant.fromEpochSeconds(epochMs / 1_000L).toString()

    private const val RECORD_PERCENT = 80.0
    private const val RETRY_ATTEMPTS = 4
    private val RETRYABLE = setOf(BingersError.RATE_LIMITED, BingersError.NETWORK, BingersError.SERVER)
}
