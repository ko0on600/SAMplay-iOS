package com.nuvio.app.features.reshaped.sync

import com.nuvio.app.samplay.IosPrefs
import com.nuvio.app.samplay.nowMs
import com.nuvio.app.samplay.samplayHttp
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** A Google sign-in in progress: the code the viewer types at [verificationUrl]. */
internal data class GoogleDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresAtMs: Long,
    val intervalMs: Long,
)

internal class SyncIOException(message: String) : Exception(message)

internal fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonObject.long(key: String, fallback: Long): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: fallback

/**
 * SAMplay iOS port of the Android GoogleAccount: Google's sign-in for TVs and limited-input
 * devices (a code approved in the browser), the same client as Android and the TV, Drive access
 * to the app's own file only.
 */
internal object GoogleAccount {
    private const val KEY_REFRESH = "refresh_token"
    private const val KEY_EMAIL = "email"
    private const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val SCOPES = "openid email $DRIVE_SCOPE"
    private const val DEVICE_CODE_URL = "https://oauth2.googleapis.com/device/code"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"
    private const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
    private val prefs = IosPrefs("nuvio_reshaped_sync_account")
    private val json = Json { ignoreUnknownKeys = true }

    val isConfigured: Boolean
        get() = ReshapedGoogleConfig.CLIENT_ID.isNotBlank() && ReshapedGoogleConfig.CLIENT_SECRET.isNotBlank()

    private val _email = MutableStateFlow<String?>(null)
    val email: StateFlow<String?> = _email.asStateFlow()

    @Volatile private var loaded = false
    @Volatile private var accessToken: String? = null
    @Volatile private var accessExpiresAtMs = 0L
    private val tokenLock = Mutex()

    fun ensureLoaded() {
        if (loaded) return
        _email.value = prefs.getString(KEY_EMAIL)?.takeIf { prefs.contains(KEY_REFRESH) }
        loaded = true
    }

    fun isSignedIn(): Boolean {
        ensureLoaded()
        return prefs.getString(KEY_REFRESH) != null
    }

    suspend fun requestDeviceCode(): GoogleDeviceCode {
        val obj = post(DEVICE_CODE_URL, mapOf("client_id" to ReshapedGoogleConfig.CLIENT_ID, "scope" to SCOPES))
        if (obj.containsKey("error")) throw GoogleAuthException(obj.str("error"), obj.str("error_description"))
        return GoogleDeviceCode(
            deviceCode = obj.str("device_code"),
            userCode = obj.str("user_code"),
            verificationUrl = obj.str("verification_url").ifBlank { obj.str("verification_uri").ifBlank { "https://www.google.com/device" } },
            expiresAtMs = nowMs() + obj.long("expires_in", 1800L) * 1000,
            intervalMs = obj.long("interval", 5L).coerceAtLeast(1L) * 1000,
        )
    }

    suspend fun awaitSignIn(code: GoogleDeviceCode): String {
        var interval = code.intervalMs
        while (nowMs() < code.expiresAtMs) {
            delay(interval)
            val obj = try {
                post(
                    TOKEN_URL,
                    mapOf(
                        "client_id" to ReshapedGoogleConfig.CLIENT_ID,
                        "client_secret" to ReshapedGoogleConfig.CLIENT_SECRET,
                        "device_code" to code.deviceCode,
                        "grant_type" to DEVICE_GRANT,
                    ),
                )
            } catch (io: SyncIOException) {
                continue
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            when (obj.str("error")) {
                "" -> {
                    val refresh = obj.str("refresh_token").ifBlank { throw GoogleAuthException("no_refresh_token") }
                    val scopes = obj.str("scope")
                    if (scopes.isNotBlank() && DRIVE_SCOPE !in scopes.split(' ')) {
                        runCatching { post(REVOKE_URL, mapOf("token" to refresh)) }
                        throw GoogleAuthException("access_denied")
                    }
                    val email = emailFromIdToken(obj.str("id_token")).orEmpty()
                    prefs.putString(KEY_REFRESH, refresh)
                    prefs.putString(KEY_EMAIL, email)
                    accessToken = obj.str("access_token").ifBlank { null }
                    accessExpiresAtMs = nowMs() + obj.long("expires_in", 0L) * 1000
                    loaded = true
                    _email.value = email
                    return email
                }
                "authorization_pending" -> Unit
                "slow_down" -> interval += 5_000
                else -> throw GoogleAuthException(obj.str("error"), obj.str("error_description"))
            }
        }
        throw GoogleAuthException("expired_token")
    }

    suspend fun accessToken(forceRefresh: Boolean = false): String? = tokenLock.withLock {
        val refresh = prefs.getString(KEY_REFRESH) ?: return@withLock null
        val current = accessToken
        if (!forceRefresh && current != null && nowMs() < accessExpiresAtMs - 60_000) return@withLock current
        val obj = post(
            TOKEN_URL,
            mapOf(
                "client_id" to ReshapedGoogleConfig.CLIENT_ID,
                "client_secret" to ReshapedGoogleConfig.CLIENT_SECRET,
                "refresh_token" to refresh,
                "grant_type" to "refresh_token",
            ),
        )
        when (obj.str("error")) {
            "" -> Unit
            "invalid_grant" -> {
                clearLocal()
                return@withLock null
            }
            else -> throw SyncIOException("Google token refresh failed: ${obj.str("error")}")
        }
        accessToken = obj.str("access_token")
        accessExpiresAtMs = nowMs() + obj.long("expires_in", 3600L) * 1000
        accessToken
    }

    suspend fun signOut() {
        val refresh = prefs.getString(KEY_REFRESH)
        clearLocal()
        if (refresh != null) runCatching { post(REVOKE_URL, mapOf("token" to refresh)) }
    }

    private fun clearLocal() {
        prefs.remove(KEY_REFRESH)
        prefs.remove(KEY_EMAIL)
        accessToken = null
        accessExpiresAtMs = 0L
        _email.value = null
    }

    private suspend fun post(url: String, form: Map<String, String>): JsonObject {
        val response = samplayHttp.submitForm(
            url = url,
            formParameters = Parameters.build { form.forEach { (k, v) -> append(k, v) } },
        )
        val text = response.bodyAsText()
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        return when {
            obj != null && (response.status.isSuccess() || obj.containsKey("error")) -> obj
            response.status.isSuccess() -> JsonObject(emptyMap())
            else -> throw SyncIOException("HTTP ${response.status.value}")
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun emailFromIdToken(idToken: String): String? = runCatching {
        val payload = idToken.split('.')[1]
        val decoded = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(payload).decodeToString()
        json.parseToJsonElement(decoded).jsonObject.str("email").ifBlank { null }
    }.getOrNull()
}

internal class GoogleAuthException(val code: String, val description: String = "") :
    Exception("Google sign-in failed: $code $description".trim()) {
    val detail: String get() = if (description.isBlank()) code else "$code: $description"
}
