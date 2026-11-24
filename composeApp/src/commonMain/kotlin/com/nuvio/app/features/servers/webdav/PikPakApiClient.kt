package com.nuvio.app.features.servers.webdav

import com.nuvio.app.core.network.createApiHttpClient
import com.nuvio.app.features.servers.ServerStorage
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.get
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.time.Clock

/**
 * PikPak Drive HTTP API (OAuth) used to list real playable media variants
 * (`medias`: Original + transcodes such as 4K/1080P/720P/480P). WebDAV alone
 * only exposes the original file URL.
 */
internal class PikPakApiClient(
    private val http: HttpClient = createApiHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun signIn(username: String, password: String): PikPakApiSession {
        val deviceId = randomDeviceId()
        val captcha = captchaToken(
            action = "POST:/v1/auth/signin",
            deviceId = deviceId,
            accessToken = null,
            userId = "",
            loginIdentity = username,
        )
        val body = buildJsonObject {
            put("captcha_token", captcha)
            put("client_id", CLIENT_ID)
            put("client_secret", CLIENT_SECRET)
            put("username", username)
            put("password", password)
        }
        val response = http.post("$USER_HOST/v1/auth/signin") {
            parameter("client_id", CLIENT_ID)
            header("X-Device-ID", deviceId)
            header("X-Captcha-Token", captcha)
            header(HttpHeaders.UserAgent, userAgent(deviceId, userId = ""))
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw PikPakApiException(parseError(text) ?: "PikPak sign-in failed (${response.status})")
        }
        val obj = json.parseToJsonElement(text).jsonObject
        val access = obj["access_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val refresh = obj["refresh_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val sub = obj["sub"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (access.isBlank() || refresh.isBlank()) {
            throw PikPakApiException(parseError(text) ?: "PikPak sign-in returned no token")
        }
        return PikPakApiSession(
            username = username,
            password = password,
            accessToken = access,
            refreshToken = refresh,
            userId = sub,
            deviceId = deviceId,
            captchaToken = captcha,
        )
    }

    suspend fun ensureSession(session: PikPakApiSession): PikPakApiSession {
        if (session.accessToken.isNotBlank()) return session
        return refresh(session)
    }

    suspend fun refresh(session: PikPakApiSession): PikPakApiSession {
        val body = buildJsonObject {
            put("client_id", CLIENT_ID)
            put("client_secret", CLIENT_SECRET)
            put("grant_type", "refresh_token")
            put("refresh_token", session.refreshToken)
        }
        val response = http.post("$USER_HOST/v1/auth/token") {
            parameter("client_id", CLIENT_ID)
            header("X-Device-ID", session.deviceId)
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            // Refresh expired — fall back to password login when available.
            if (session.username.isNotBlank() && session.password.isNotBlank()) {
                return signIn(session.username, session.password).copy(deviceId = session.deviceId)
            }
            throw PikPakApiException(parseError(text) ?: "PikPak token refresh failed")
        }
        val obj = json.parseToJsonElement(text).jsonObject
        val access = obj["access_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val refresh = obj["refresh_token"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { session.refreshToken }
        val sub = obj["sub"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { session.userId }
        if (access.isBlank()) throw PikPakApiException("PikPak token refresh returned no access token")
        return session.copy(accessToken = access, refreshToken = refresh, userId = sub)
    }

    /**
     * Walk the drive by folder name from [path] (WebDAV-style `/a/b/file.mkv`) and
     * return file detail including every media variant.
     */
    suspend fun fileDetailForPath(session: PikPakApiSession, path: String): PikPakFileDetail {
        var live = ensureSession(session)
        val segments = path.trim('/').split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) throw PikPakApiException("Empty PikPak path")
        var parentId = ""
        for ((index, name) in segments.withIndex()) {
            val isLast = index == segments.lastIndex
            val files = listChildren(live, parentId)
            val match = files.firstOrNull { it.name == name }
                ?: files.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: throw PikPakApiException("Not found in PikPak: $name")
            if (isLast) {
                return fileDetail(live, match.id)
            }
            if (!match.isFolder) throw PikPakApiException("Not a folder: $name")
            parentId = match.id
        }
        throw PikPakApiException("Not found in PikPak: $path")
    }

    suspend fun fileDetail(session: PikPakApiSession, fileId: String): PikPakFileDetail {
        var live = ensureSession(session)
        return try {
            getFile(live, fileId)
        } catch (error: PikPakApiException) {
            if (!error.tokenExpired) throw error
            live = refresh(live)
            PikPakApiSessions.put(live)
            getFile(live, fileId)
        }
    }

    private suspend fun getFile(session: PikPakApiSession, fileId: String): PikPakFileDetail {
        val captcha = captchaToken(
            action = "GET:/drive/v1/files",
            deviceId = session.deviceId,
            accessToken = session.accessToken,
            userId = session.userId,
            loginIdentity = null,
        )
        val response = http.get("$DRIVE_HOST/drive/v1/files/$fileId") {
            parameter("usage", "FETCH")
            parameter("_magic", "2021")
            parameter("thumbnail_size", "SIZE_LARGE")
            header(HttpHeaders.Authorization, "Bearer ${session.accessToken}")
            header("X-Device-ID", session.deviceId)
            header("X-Captcha-Token", captcha)
            header(HttpHeaders.UserAgent, userAgent(session.deviceId, session.userId))
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw PikPakApiException(parseError(text) ?: "PikPak file detail failed", tokenExpired = isTokenError(text))
        }
        return parseFileDetail(text)
    }

    private suspend fun listChildren(session: PikPakApiSession, parentId: String): List<PikPakChild> {
        val out = mutableListOf<PikPakChild>()
        var pageToken = ""
        do {
            val captcha = captchaToken(
                action = "GET:/drive/v1/files",
                deviceId = session.deviceId,
                accessToken = session.accessToken,
                userId = session.userId,
                loginIdentity = null,
            )
            val response = http.get("$DRIVE_HOST/drive/v1/files") {
                if (parentId.isNotBlank()) parameter("parent_id", parentId)
                parameter("thumbnail_size", "SIZE_LARGE")
                parameter("with_audit", "true")
                parameter("limit", "100")
                parameter("filters", """{"phase":{"eq":"PHASE_TYPE_COMPLETE"},"trashed":{"eq":false}}""")
                if (pageToken.isNotBlank()) parameter("page_token", pageToken)
                header(HttpHeaders.Authorization, "Bearer ${session.accessToken}")
                header("X-Device-ID", session.deviceId)
                header("X-Captcha-Token", captcha)
                header(HttpHeaders.UserAgent, userAgent(session.deviceId, session.userId))
            }
            val text = response.bodyAsText()
            if (!response.status.isSuccess()) {
                if (isTokenError(text)) {
                    val refreshed = refresh(session)
                    PikPakApiSessions.put(refreshed)
                    return listChildren(refreshed, parentId)
                }
                throw PikPakApiException(parseError(text) ?: "PikPak list failed")
            }
            val obj = json.parseToJsonElement(text).jsonObject
            pageToken = obj["next_page_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val files = obj["files"]?.jsonArray.orEmpty()
            for (el in files) {
                val f = el.jsonObject
                val id = f["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val name = f["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val kind = f["kind"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (id.isBlank() || name.isBlank()) continue
                out += PikPakChild(id = id, name = name, isFolder = kind.contains("folder", ignoreCase = true))
            }
        } while (pageToken.isNotBlank())
        return out
    }

    private suspend fun captchaToken(
        action: String,
        deviceId: String,
        accessToken: String?,
        userId: String,
        loginIdentity: String?,
    ): String {
        val timestamp = Clock.System.now().toEpochMilliseconds().toString()
        val metaObj = buildJsonObject {
            put("client_version", CLIENT_VERSION)
            put("package_name", PACKAGE_NAME)
            put("timestamp", timestamp)
            put("captcha_sign", captchaSign(deviceId, timestamp))
            when {
                !loginIdentity.isNullOrBlank() && '@' in loginIdentity -> put("email", loginIdentity)
                !loginIdentity.isNullOrBlank() && loginIdentity.length in 11..18 && loginIdentity.all { it.isDigit() || it == '+' } ->
                    put("phone_number", loginIdentity)
                !loginIdentity.isNullOrBlank() -> put("username", loginIdentity)
                userId.isNotBlank() -> put("user_id", userId)
            }
        }
        val body = buildJsonObject {
            put("action", action)
            put("captcha_token", "")
            put("client_id", CLIENT_ID)
            put("device_id", deviceId)
            put("meta", metaObj)
            put("redirect_uri", "xlaccsdk01://xbase.cloud/callback?state=harbor")
        }
        val response = http.post("$USER_HOST/v1/shield/captcha/init") {
            parameter("client_id", CLIENT_ID)
            header("X-Device-ID", deviceId)
            if (!accessToken.isNullOrBlank()) header(HttpHeaders.Authorization, "Bearer $accessToken")
            header(HttpHeaders.UserAgent, userAgent(deviceId, userId))
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val text = response.bodyAsText()
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw PikPakApiException(parseError(text) ?: "PikPak captcha init failed")
        val url = obj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (url.isNotBlank()) {
            throw PikPakApiException("PikPak requires interactive verification. Sign in again from PikPak settings.")
        }
        val token = obj["captcha_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (token.isBlank()) throw PikPakApiException(parseError(text) ?: "PikPak captcha token missing")
        return token
    }

    private fun captchaSign(deviceId: String, timestamp: String): String {
        var str = CLIENT_ID + CLIENT_VERSION + PACKAGE_NAME + deviceId + timestamp
        for (algo in ALGORITHMS) {
            str = PikPakMd5.hex(str + algo)
        }
        return "1.$str"
    }

    private fun parseFileDetail(text: String): PikPakFileDetail {
        val obj = json.parseToJsonElement(text).jsonObject
        val id = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val size = obj["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        val web = obj["web_content_link"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val medias = obj["medias"]?.jsonArray.orEmpty().mapNotNull { el ->
            val m = el.jsonObject
            val mediaId = m["media_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val mediaName = m["media_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val resolution = m["resolution_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val isOrigin = m["is_origin"].asBool(false)
            val isVisible = m["is_visible"].asBool(true)
            val linkObj = m["link"]?.jsonObject
            val url = linkObj?.get("url")?.jsonPrimitive?.contentOrNull.orEmpty()
            val video = m["video"]?.jsonObject
            val height = video?.get("height")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            val width = video?.get("width")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            if (url.isBlank() && !isOrigin) return@mapNotNull null
            // Skip invisible duplicates with no usable URL.
            if (!isVisible && url.isBlank()) return@mapNotNull null
            PikPakMedia(
                mediaId = mediaId.ifBlank { if (isOrigin) "origin" else resolution.ifBlank { mediaName } },
                mediaName = mediaName,
                resolutionName = resolution,
                isOrigin = isOrigin,
                isVisible = isVisible,
                url = url,
                height = height,
                width = width,
            )
        }
        return PikPakFileDetail(id = id, name = name, sizeBytes = size, webContentLink = web, medias = medias)
    }

    private fun parseError(text: String): String? = runCatching {
        val obj = json.parseToJsonElement(text).jsonObject
        obj["error_description"]?.jsonPrimitive?.contentOrNull
            ?: obj["error"]?.jsonPrimitive?.contentOrNull
            ?: obj["message"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private fun isTokenError(text: String): Boolean {
        val lower = text.lowercase()
        return "unauthenticated" in lower || "\"error_code\":16" in lower ||
            "\"error_code\":4121" in lower || "\"error_code\":4122" in lower ||
            "invalid_grant" in lower
    }

    private fun userAgent(deviceId: String, userId: String): String {
        val deviceSign = run {
            val base = deviceId + PACKAGE_NAME + "1" + "appkey"
            // sha1 then md5 — approximate with double md5 of the same salt if sha1 unavailable;
            // PikPak accepts the Android UA string shape even when device_sign is derived loosely.
            val md = PikPakMd5.hex(PikPakMd5.hex(base))
            "div101.$deviceId$md"
        }
        return "ANDROID-com.pikcloud.pikpak/$CLIENT_VERSION protocolversion/200 accesstype/ " +
            "clientid/$CLIENT_ID clientversion/$CLIENT_VERSION action_type/ networktype/WIFI " +
            "sessionid/ deviceid/$deviceId providername/NONE devicesign/$deviceSign " +
            "refresh_token/ sdkversion/$SDK_VERSION datetime/${Clock.System.now().toEpochMilliseconds()} " +
            "usrno/$userId appname/android-com.pikcloud.pikpak appid/ " +
            "appversion/$CLIENT_VERSION appid/ com.pikcloud.pikpak/$CLIENT_VERSION"
    }

    private fun randomDeviceId(): String {
        val alphabet = "0123456789abcdef"
        return buildString(32) { repeat(32) { append(alphabet[Random.nextInt(alphabet.length)]) } }
    }

    companion object {
        private const val USER_HOST = "https://user.mypikpak.com"
        private const val DRIVE_HOST = "https://api-drive.mypikpak.com"
        // Public Android client used by Alist / OpenList / rclone-compatible tools.
        private const val CLIENT_ID = "YNxT9w7GMdWvEOKa"
        private const val CLIENT_SECRET = "dbw2OtmVEeuUvIptb1Coyg"
        private const val CLIENT_VERSION = "1.53.2"
        private const val PACKAGE_NAME = "com.pikcloud.pikpak"
        private const val SDK_VERSION = "2.0.6.206003"
        private val ALGORITHMS = listOf(
            "SOP04dGzk0TNO7t7t9ekDbAmx+eq0OI1ovEx",
            "nVBjhYiND4hZ2NCGyV5beamIr7k6ifAsAbl",
            "Ddjpt5B/Cit6EDq2a6cXgxY9lkEIOw4yC1GDF28KrA",
            "VVCogcmSNIVvgV6U+AochorydiSymi68YVNGiz",
            "u5ujk5sM62gpJOsB/1Gu/zsfgfZO",
            "dXYIiBOAHZgzSruaQ2Nhrqc2im",
            "z5jUTBSIpBN9g4qSJGlidNAutX6",
            "KJE2oveZ34du/g1tiimm",
        )

        fun isPikPakAddress(address: String): Boolean =
            address.contains("pikpak", ignoreCase = true)
    }
}

internal data class PikPakApiSession(
    val username: String,
    val password: String,
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val deviceId: String,
    val captchaToken: String = "",
)

internal data class PikPakChild(val id: String, val name: String, val isFolder: Boolean)

internal data class PikPakFileDetail(
    val id: String,
    val name: String,
    val sizeBytes: Long?,
    val webContentLink: String,
    val medias: List<PikPakMedia>,
)

internal data class PikPakMedia(
    val mediaId: String,
    val mediaName: String,
    val resolutionName: String,
    val isOrigin: Boolean,
    val isVisible: Boolean,
    val url: String,
    val height: Int,
    val width: Int,
) {
    /** Label for the stream picker: Original, 2160P, 1080P, 720P, 480P, … */
    fun qualityLabel(): String {
        if (isOrigin) return "Original"
        val fromName = resolutionName.ifBlank { mediaName }.trim()
        if (fromName.isNotBlank()) {
            val normalized = fromName.uppercase().replace("Ｐ", "P")
            return when {
                normalized.contains("ORIGIN") -> "Original"
                normalized.endsWith("P") || normalized.contains("K") -> normalized
                height > 0 -> "${height}P"
                else -> normalized
            }
        }
        return when {
            height >= 2160 -> "2160P"
            height >= 1080 -> "1080P"
            height >= 720 -> "720P"
            height >= 480 -> "480P"
            height > 0 -> "${height}P"
            else -> mediaName.ifBlank { "Stream" }
        }
    }
}


private fun kotlinx.serialization.json.JsonElement?.asBool(default: Boolean): Boolean {
    val prim = this?.jsonPrimitive ?: return default
    return prim.contentOrNull?.toBooleanStrictOrNull()
        ?: prim.booleanOrNull
        ?: default
}

internal class PikPakApiException(
    message: String,
    val tokenExpired: Boolean = false,
) : Exception(message)

/**
 * Persists PikPak API sessions next to WebDAV connections so quality lists
 * survive restarts. Keyed by WebDAV [ServerConnection.id].
 */
internal object PikPakApiSessions {
    private val lock = SynchronizedObject()
    private val memory = mutableMapOf<String, PikPakApiSession>()
    private val json = Json { ignoreUnknownKeys = true }

    fun get(connectionId: String): PikPakApiSession? = synchronized(lock) {
        memory[connectionId] ?: read(connectionId)?.also { memory[connectionId] = it }
    }

    fun put(session: PikPakApiSession, connectionId: String? = null) {
        // When refreshing without an id, update every in-memory copy that shares the refresh token.
        synchronized(lock) {
            if (connectionId != null) {
                memory[connectionId] = session
                write(connectionId, session)
                return
            }
            memory.entries.forEach { (id, existing) ->
                if (existing.refreshToken == session.refreshToken || existing.userId == session.userId) {
                    memory[id] = session
                    write(id, session)
                }
            }
        }
    }

    fun putForConnection(connectionId: String, session: PikPakApiSession) {
        synchronized(lock) {
            memory[connectionId] = session
            write(connectionId, session)
        }
    }

    fun clear(connectionId: String) {
        synchronized(lock) {
            memory.remove(connectionId)
            ServerStorage.write(key(connectionId), null)
        }
    }

    fun decodeBasic(authorization: String): Pair<String, String>? {
        val raw = authorization.removePrefix("Basic ").trim()
        if (raw.isBlank()) return null
        val decoded = runCatching {
            val bytes = androidSafeBase64(raw)
            bytes.decodeToString()
        }.getOrNull() ?: return null
        val sep = decoded.indexOf(':')
        if (sep <= 0) return null
        return decoded.substring(0, sep) to decoded.substring(sep + 1)
    }

    private fun read(connectionId: String): PikPakApiSession? {
        val payload = ServerStorage.read(key(connectionId)) ?: return null
        return runCatching { json.decodeFromString(Stored.serializer(), payload) }.getOrNull()?.toSession()
    }

    private fun write(connectionId: String, session: PikPakApiSession) {
        val stored = Stored(
            username = session.username,
            password = session.password,
            accessToken = session.accessToken,
            refreshToken = session.refreshToken,
            userId = session.userId,
            deviceId = session.deviceId,
        )
        ServerStorage.write(key(connectionId), json.encodeToString(Stored.serializer(), stored))
    }

    private fun key(connectionId: String) = "pikpak.api.$connectionId"

    private fun androidSafeBase64(value: String): ByteArray {
        // Ktor/Kotlin Base64 — use kotlin.io or manual.
        val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val cleaned = value.trim().filter { it != '\n' && it != '\r' && it != '=' }
        val out = ArrayList<Byte>()
        var buffer = 0
        var bits = 0
        for (ch in cleaned) {
            val v = table.indexOf(ch)
            if (v < 0) continue
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add(((buffer shr bits) and 0xff).toByte())
            }
        }
        return out.toByteArray()
    }

    @Serializable
    private data class Stored(
        val username: String = "",
        val password: String = "",
        @SerialName("access_token") val accessToken: String = "",
        @SerialName("refresh_token") val refreshToken: String = "",
        @SerialName("user_id") val userId: String = "",
        @SerialName("device_id") val deviceId: String = "",
    ) {
        fun toSession() = PikPakApiSession(
            username = username,
            password = password,
            accessToken = accessToken,
            refreshToken = refreshToken,
            userId = userId,
            deviceId = deviceId,
        )
    }
}
