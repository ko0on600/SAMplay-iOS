package com.nuvio.app.features.device

import com.nuvio.app.features.player.PlayerLaunch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/** The player launch of a device video is told apart by these two values. */
const val DEVICE_VIDEO_CONTENT_TYPE = "device-video"
const val DEVICE_VIDEO_PROVIDER_ID = "samplay-device"

data class DeviceVideosUiState(
    val isSupported: Boolean = false,
    val hasPermission: Boolean = false,
    /** Only the videos the viewer picked are visible (Android 14+ «limited access»). */
    val isPartialAccess: Boolean = false,
    val isLoading: Boolean = false,
    val isLoaded: Boolean = false,
    /** What the library shows: [allVideos] without the hidden ones and short clips. */
    val videos: List<DeviceVideo> = emptyList(),
    /** Every video the app may read, hidden ones too. */
    val allVideos: List<DeviceVideo> = emptyList(),
    val sort: DeviceVideoSort = DeviceVideoSort.NameAscending,
    val positions: Map<String, DeviceVideoPosition> = emptyMap(),
    /** «Continue» row: unfinished videos that can still be opened, newest first. */
    val continueWatching: List<DeviceVideoPosition> = emptyList(),
    /** Folder id → name of the folders left out of the library. */
    val hiddenFolders: Map<String, String> = emptyMap(),
    /** File uri → name of the videos left out of the library. */
    val hiddenVideos: Map<String, String> = emptyMap(),
    /** «Hide short clips» in seconds; 0 = off. */
    val minDurationSec: Int = 0,
    /** When a video ends, the next one in its folder plays. */
    val autoplayNext: Boolean = true,
    /** What was hidden last, for the «Undo» bar; null once it is gone. */
    val lastHidden: DeviceVideoHidden? = null,
) {
    val minDurationMs: Long get() = minDurationSec * 1_000L

    fun isShown(video: DeviceVideo): Boolean =
        DeviceVideoLibrary.isVisible(video, hiddenFolders.keys, hiddenVideos.keys, minDurationMs)
}

/** Something the viewer just hid (a folder or one video), so it can be shown again at once. */
data class DeviceVideoHidden(
    val id: Long,
    val name: String,
    val folderId: String? = null,
    val videoUri: String? = null,
    /** When it was hidden: the «Undo» offer runs out a few seconds later, seen or not. */
    val atEpochMs: Long = 0L,
)

/**
 * SAMplay: the videos on this device (Library › On this device), where the viewer stopped in
 * each one, and the play requests (from the library, «Open file», or another app's «Open with»).
 * Positions stay on this device: the files are not on the viewer's other devices.
 */
object DeviceVideoRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(DeviceVideosUiState())
    val uiState: StateFlow<DeviceVideosUiState> = _uiState.asStateFlow()

    private val playChannel = Channel<DeviceVideo>(capacity = Channel.BUFFERED)
    /** Collected once by the app shell, which opens the player. */
    val playRequests: Flow<DeviceVideo> = playChannel.receiveAsFlow()

    private var stateLoaded = false
    private var loadJob: Job? = null
    private var loadedAtEpochMs = 0L

    fun ensureLoaded() {
        if (stateLoaded) return
        stateLoaded = true
        val stored = DeviceVideoPlatform.loadState()
            ?.takeIf(String::isNotBlank)
            ?.let { runCatching { json.decodeFromString(DeviceVideoStoredState.serializer(), it) }.getOrNull() }
            ?: DeviceVideoStoredState()
        _uiState.update {
            it.copy(
                isSupported = DeviceVideoPlatform.isSupported,
                hasPermission = DeviceVideoPlatform.isSupported && DeviceVideoPlatform.hasPermission(),
                isPartialAccess = DeviceVideoPlatform.isSupported && DeviceVideoPlatform.isPartialAccess(),
                sort = DeviceVideoLibrary.parseSort(stored.sort),
                positions = stored.positions.associateBy(DeviceVideoPosition::uri),
                hiddenFolders = stored.hiddenFolders,
                hiddenVideos = stored.hiddenVideos,
                minDurationSec = stored.minDurationSec.coerceAtLeast(0),
                autoplayNext = stored.autoplayNext,
            ).withVisibleVideos()
        }
        refreshContinueWatching()
    }

    /** Reads the device's videos again (after the permission changes, or on pull to refresh). */
    fun refresh() {
        ensureLoaded()
        if (!DeviceVideoPlatform.isSupported) return
        val granted = DeviceVideoPlatform.hasPermission()
        val partial = granted && DeviceVideoPlatform.isPartialAccess()
        _uiState.update { it.copy(hasPermission = granted, isPartialAccess = partial) }
        if (!granted) {
            _uiState.update { it.copy(isLoading = false, isLoaded = true, videos = emptyList(), allVideos = emptyList()) }
            refreshContinueWatching()
            return
        }
        loadJob?.cancel()
        _uiState.update { it.copy(isLoading = true) }
        loadJob = scope.launch {
            val videos = try {
                DeviceVideoPlatform.queryVideos()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                emptyList()
            }
            loadedAtEpochMs = nowEpochMs()
            _uiState.update { it.copy(isLoading = false, isLoaded = true, allVideos = videos).withVisibleVideos() }
            refreshContinueWatching()
        }
    }

    /** Loads once; later visits keep what was read (pull to refresh reads again). */
    fun ensureVideosLoaded() {
        ensureLoaded()
        val state = _uiState.value
        val granted = DeviceVideoPlatform.isSupported && DeviceVideoPlatform.hasPermission()
        val partial = granted && DeviceVideoPlatform.isPartialAccess()
        val sameAccess = state.hasPermission == granted && state.isPartialAccess == partial
        if (sameAccess && state.isLoading) return
        // Coming back after a while: new downloads and recordings show up without a refresh.
        // With limited access the picked videos can change in the system's settings, so those
        // are read again on every return.
        val fresh = nowEpochMs() - loadedAtEpochMs < RELOAD_AFTER_MS
        if (sameAccess && state.isLoaded && fresh && !partial) return
        refresh()
    }

    fun setSort(sort: DeviceVideoSort) {
        ensureLoaded()
        _uiState.update { it.copy(sort = sort) }
        persist()
    }

    fun requestPlay(video: DeviceVideo) {
        playChannel.trySend(video)
    }

    /** Leaves a folder out of the library (the files stay on the device). */
    fun hideFolder(folderId: String, name: String) {
        if (folderId.isBlank()) return
        ensureLoaded()
        _uiState.update {
            it.copy(
                hiddenFolders = it.hiddenFolders + (folderId to name),
                lastHidden = DeviceVideoHidden(
                    id = nextHiddenId(),
                    name = name,
                    folderId = folderId,
                    atEpochMs = nowEpochMs(),
                ),
            ).withVisibleVideos()
        }
        visibilityChanged()
    }

    fun showFolder(folderId: String) {
        ensureLoaded()
        _uiState.update { it.copy(hiddenFolders = it.hiddenFolders - folderId, lastHidden = null).withVisibleVideos() }
        visibilityChanged()
    }

    /** Leaves one video out of the library (the file stays on the device). */
    fun hideVideo(video: DeviceVideo) {
        if (video.uri.isBlank()) return
        ensureLoaded()
        _uiState.update {
            it.copy(
                hiddenVideos = it.hiddenVideos + (video.uri to video.name),
                lastHidden = DeviceVideoHidden(
                    id = nextHiddenId(),
                    name = DeviceVideoLibrary.title(video.name),
                    videoUri = video.uri,
                    atEpochMs = nowEpochMs(),
                ),
            ).withVisibleVideos()
        }
        visibilityChanged()
    }

    fun showVideos(uris: Collection<String>) {
        ensureLoaded()
        _uiState.update { it.copy(hiddenVideos = it.hiddenVideos - uris.toSet(), lastHidden = null).withVisibleVideos() }
        visibilityChanged()
    }

    /** «Hidden videos»: [uri] is hidden again or shown (no «Undo» bar; the sheet shows the state). */
    fun setVideoHidden(uri: String, name: String, hidden: Boolean) {
        if (uri.isBlank()) return
        ensureLoaded()
        _uiState.update {
            val videos = if (hidden) it.hiddenVideos + (uri to name) else it.hiddenVideos - uri
            it.copy(hiddenVideos = videos, lastHidden = null).withVisibleVideos()
        }
        visibilityChanged()
    }

    /** The «Undo» bar: shows again what was hidden last. */
    fun undoHide() {
        val hidden = _uiState.value.lastHidden ?: return
        hidden.folderId?.let(::showFolder)
        hidden.videoUri?.let { showVideos(listOf(it)) }
    }

    /** How long the «Undo» offer for [hidden] still stands (0 once it ran out). */
    fun undoTimeLeftMs(hidden: DeviceVideoHidden): Long =
        (UNDO_VISIBLE_MS - (nowEpochMs() - hidden.atEpochMs)).coerceIn(0L, UNDO_VISIBLE_MS)

    /** The «Undo» bar goes after a while; [id] keeps a newer one. */
    fun dismissUndo(id: Long) {
        _uiState.update { if (it.lastHidden?.id == id) it.copy(lastHidden = null) else it }
    }

    /** «Choose folders to show»: [folderId] is shown or hidden. */
    fun setFolderShown(folderId: String, name: String, shown: Boolean) {
        if (shown) showFolder(folderId) else hideFolder(folderId, name)
        _uiState.update { it.copy(lastHidden = null) }
    }

    /** Shows every folder that is on the device now. */
    fun showAllFolders() {
        ensureLoaded()
        _uiState.update { state ->
            val present = DeviceVideoLibrary.folders(state.allVideos, DeviceVideoSort.NameAscending)
            state.copy(
                hiddenFolders = DeviceVideoLibrary.hiddenFoldersAfterChoice(
                    current = state.hiddenFolders,
                    present = present,
                    shownIds = present.mapTo(HashSet()) { it.id },
                ),
                lastHidden = null,
            ).withVisibleVideos()
        }
        visibilityChanged()
    }

    fun setMinDurationSec(seconds: Int) {
        ensureLoaded()
        _uiState.update { it.copy(minDurationSec = seconds.coerceAtLeast(0), lastHidden = null).withVisibleVideos() }
        visibilityChanged()
    }

    fun setAutoplayNext(enabled: Boolean) {
        ensureLoaded()
        _uiState.update { it.copy(autoplayNext = enabled) }
        persist()
    }

    /**
     * What plays when [uri] ends: the next video in its folder, in the library's order, skipping
     * hidden ones; null when «play the next video» is off, at the folder's end, or for a file
     * that is not in the library. Opened from another app before the library was ever shown,
     * the videos are read first.
     */
    suspend fun nextVideoAfter(uri: String): DeviceVideo? {
        ensureLoaded()
        if (!_uiState.value.autoplayNext || uri.isBlank()) return null
        val known = _uiState.value.allVideos.any { it.uri == uri }
        if (!known && DeviceVideoPlatform.isSupported && DeviceVideoPlatform.hasPermission()) {
            val videos = try {
                DeviceVideoPlatform.queryVideos()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            }
            if (videos != null) {
                loadedAtEpochMs = nowEpochMs()
                _uiState.update { it.copy(isLoaded = true, allVideos = videos).withVisibleVideos() }
                refreshContinueWatching()
            }
        }
        val state = _uiState.value
        return DeviceVideoLibrary.nextInFolder(state.allVideos, uri, state.sort, state::isShown)
    }

    fun resumePositionMs(uri: String): Long {
        ensureLoaded()
        return DeviceVideoLibrary.resumePositionMs(_uiState.value.positions[uri])
    }

    /** Called by the player while a device video plays and when it closes. */
    fun recordPosition(uri: String, title: String, folderName: String, positionMs: Long, durationMs: Long) {
        if (uri.isBlank() || positionMs < 0L) return
        ensureLoaded()
        val update = DeviceVideoPosition(
            uri = uri,
            title = title,
            folderName = folderName,
            positionMs = positionMs,
            durationMs = durationMs.coerceAtLeast(0L),
            updatedAtEpochMs = nowEpochMs(),
        )
        _uiState.update { state ->
            val merged = DeviceVideoLibrary.mergePosition(state.positions.values.sortedByDescending { it.updatedAtEpochMs }, update)
            state.copy(positions = merged.associateBy(DeviceVideoPosition::uri))
        }
        persist()
        refreshContinueWatching()
    }

    /** Forgets where the viewer stopped in [uri] (the «Continue» row's remove). */
    fun clearPosition(uri: String) {
        ensureLoaded()
        _uiState.update { it.copy(positions = it.positions - uri) }
        persist()
        refreshContinueWatching()
    }

    /** The video behind a «Continue» entry, from the library when it is there. */
    fun videoFor(position: DeviceVideoPosition): DeviceVideo =
        _uiState.value.allVideos.firstOrNull { it.uri == position.uri }
            ?: DeviceVideo(uri = position.uri, name = position.title, folderName = position.folderName, durationMs = position.durationMs)

    private fun refreshContinueWatching() {
        val state = _uiState.value
        val hidden = DeviceVideoLibrary.hiddenUris(
            state.allVideos,
            state.hiddenFolders.keys,
            state.hiddenVideos.keys,
            state.minDurationMs,
        )
        val candidates = DeviceVideoLibrary.inProgress(state.positions.values)
            .filter { it.uri !in hidden }
            .take(CONTINUE_LIMIT)
        if (candidates.isEmpty()) {
            _uiState.update { it.copy(continueWatching = emptyList()) }
            return
        }
        scope.launch {
            val readable = candidates.filter { position ->
                runCatching { DeviceVideoPlatform.canRead(position.uri) }.getOrDefault(false)
            }
            _uiState.update { it.copy(continueWatching = readable) }
        }
    }

    private fun persist() {
        val state = _uiState.value
        val payload = runCatching {
            json.encodeToString(
                DeviceVideoStoredState.serializer(),
                DeviceVideoStoredState(
                    sort = state.sort.name,
                    positions = state.positions.values.sortedByDescending { it.updatedAtEpochMs },
                    hiddenFolders = state.hiddenFolders,
                    hiddenVideos = state.hiddenVideos,
                    minDurationSec = state.minDurationSec,
                    autoplayNext = state.autoplayNext,
                ),
            )
        }.getOrNull() ?: return
        DeviceVideoPlatform.saveState(payload)
    }

    private fun nowEpochMs(): Long = Clock.System.now().toEpochMilliseconds()

    private fun DeviceVideosUiState.withVisibleVideos(): DeviceVideosUiState =
        copy(videos = DeviceVideoLibrary.visible(allVideos, hiddenFolders.keys, hiddenVideos.keys, minDurationMs))

    private fun visibilityChanged() {
        persist()
        refreshContinueWatching()
    }

    private var hiddenSerial = 0L

    private fun nextHiddenId(): Long = ++hiddenSerial

    private const val CONTINUE_LIMIT = 12
    private const val RELOAD_AFTER_MS = 60_000L
    private const val UNDO_VISIBLE_MS = 8_000L
}

/** The player launch for a device video; it resumes where the viewer stopped. */
internal fun deviceVideoPlayerLaunch(video: DeviceVideo, profileId: Int): PlayerLaunch {
    val title = DeviceVideoLibrary.title(video.name).ifBlank { video.name }
    return PlayerLaunch(
        profileId = profileId,
        title = title,
        sourceUrl = video.uri,
        streamTitle = title,
        streamSubtitle = video.folderName.takeIf(String::isNotBlank),
        providerName = "SAMplay",
        providerAddonId = DEVICE_VIDEO_PROVIDER_ID,
        contentType = DEVICE_VIDEO_CONTENT_TYPE,
        // No catalog entry: no add-on sources, subtitles or tracking are looked up for it.
        videoId = null,
        parentMetaId = "device:${video.uri}",
        parentMetaType = DEVICE_VIDEO_CONTENT_TYPE,
        initialPositionMs = DeviceVideoRepository.resumePositionMs(video.uri),
        sourceFilename = video.name.takeIf(String::isNotBlank),
    )
}
