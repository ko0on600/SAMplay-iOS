package com.nuvio.app.features.bingers

import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.network.createApiHttpClient
import com.nuvio.app.core.network.readBoundedResponseBody
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.http.contentLength
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Why a Bingers request failed, as the app shows it. */
enum class BingersError {
    /** 401: the token is wrong, revoked or expired. */
    INVALID_TOKEN,
    /** The token can only read; recording needs "Read + write". */
    READ_ONLY_TOKEN,
    /** 403: the account has no API access (Legend or Icon plan) or the token lacks the scope. */
    NOT_ENABLED,
    /** 429: over 60 requests a minute or 1,000 an hour. */
    RATE_LIMITED,
    NETWORK,
    SERVER,
}

internal class BingersApiException(
    val error: BingersError,
    val status: Int = 0,
    val retryAfterEpochMs: Long? = null,
    message: String? = null,
) : Exception(message ?: "Bingers request failed: $error ($status)")

/** Who a token belongs to, from `GET /me`. */
internal data class BingersAccount(
    val name: String?,
    val canWrite: Boolean?,
)

/** One item of a `/scrobble` or `/library/actions` answer. */
internal data class BingersResult(
    val status: String?,
    val outcome: String?,
    val message: String?,
) {
    val isResolved: Boolean
        get() = status == "ok" || status == "already"
}

/**
 * The few calls of Bingers' public API (https://api.bingers.app/v1) that Nuvio uses: who the
 * token belongs to, playback scrobbles, history imports and unwatch. The token is the user's own
 * personal access token (`bng_pat_…`), pasted by them; nothing is built into the app.
 */
internal class BingersApi(
    private val client: HttpClient = createApiHttpClient(),
    private val nowEpochMs: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val limitLock = Mutex()
    private var blockedUntilEpochMs = 0L

    suspend fun me(token: String): BingersAccount {
        val body = request(token, HttpMethod.Get, "/me", null)
        return parseBingersAccount(body)
    }

    /** `POST /scrobble/{phase}`: start and pause are only acknowledged; stop at 80 % or more records a watch. */
    suspend fun playback(token: String, phase: String, target: BingersTarget, progressPercent: Double) {
        require(phase == "start" || phase == "pause" || phase == "stop")
        val progress = progressPercent.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0) ?: 0.0
        val body = target.toJson { put("progress", JsonPrimitive(progress)) }
        request(token, HttpMethod.Post, "/scrobble/$phase", body)
    }

    /** `POST /scrobble`: adds plays to the history (a second one within 12 hours is answered "already"). */
    suspend fun addHistory(token: String, items: List<Pair<BingersTarget, String?>>): List<BingersResult> {
        require(items.size in 1..MAX_ITEMS_PER_REQUEST)
        val body = JsonObject(
            mapOf(
                "items" to JsonArray(
                    items.map { (target, watchedAt) ->
                        target.toJson { watchedAt?.let { put("watched_at", JsonPrimitive(it)) } }
                    },
                ),
            ),
        )
        return parseBingersResults(request(token, HttpMethod.Post, "/scrobble", body))
    }

    /** `POST /library/actions` with `unwatch`. */
    suspend fun unwatch(token: String, targets: List<BingersTarget>): List<BingersResult> {
        require(targets.size in 1..MAX_ITEMS_PER_REQUEST)
        val body = JsonObject(
            mapOf(
                "actions" to JsonArray(
                    targets.map { target -> target.toJson { put("action", JsonPrimitive("unwatch")) } },
                ),
            ),
        )
        return parseBingersResults(request(token, HttpMethod.Post, "/library/actions", body))
    }

    private suspend fun request(token: String, method: HttpMethod, path: String, body: JsonObject?): String {
        limitLock.withLock {
            if (blockedUntilEpochMs > nowEpochMs()) {
                throw BingersApiException(BingersError.RATE_LIMITED, 429, blockedUntilEpochMs)
            }
        }
        val response = try {
            client.prepareRequest("$BASE_URL$path") {
                this.method = method
                header("Accept", "application/json")
                header("User-Agent", "NuvioRS/${AppVersionConfig.VERSION_NAME}")
                header("Authorization", "Bearer $token")
                if (body != null) {
                    header("Content-Type", "application/json")
                    setBody(body.toString())
                }
            }.execute { response ->
                Triple(
                    response.status.value,
                    readBoundedResponseBody(response.bodyAsChannel(), response.contentLength(), MAX_RESPONSE_BYTES),
                    response.headers["Retry-After"],
                )
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw BingersApiException(BingersError.NETWORK, message = error.message)
        }
        val (status, text, retryAfter) = response
        if (status in 200..299) return text
        val errorBody = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?.get("error") as? JsonObject
        val message = errorBody?.string("message") ?: errorBody?.string("code")
        when (status) {
            401 -> throw BingersApiException(BingersError.INVALID_TOKEN, status, message = message)
            403 -> throw BingersApiException(BingersError.NOT_ENABLED, status, message = message)
            429 -> {
                val seconds = retryAfter?.trim()?.toLongOrNull()
                    ?: errorBody?.get("retryAfterSeconds")?.jsonPrimitive?.intOrNull?.toLong()
                    ?: 60L
                val until = nowEpochMs() + seconds.coerceIn(1L, 3_600L) * 1_000L
                limitLock.withLock { blockedUntilEpochMs = maxOf(blockedUntilEpochMs, until) }
                throw BingersApiException(BingersError.RATE_LIMITED, status, until, message)
            }
            // 400 (a title it can't take) and 5xx alike: Bingers didn't take the request.
            else -> throw BingersApiException(BingersError.SERVER, status, message = message ?: "HTTP $status")
        }
    }

    companion object {
        const val BASE_URL = "https://api.bingers.app/v1"
        const val MAX_ITEMS_PER_REQUEST = 100
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    }
}

/**
 * `GET /me` documents only that it names the token's owner and scopes; read the name and the
 * scopes from whichever of the usual fields are there.
 */
internal fun parseBingersAccount(body: String): BingersAccount {
    val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return BingersAccount(null, null)
    val holders = listOfNotNull(root, root["user"] as? JsonObject, root["account"] as? JsonObject, root["owner"] as? JsonObject)
    val name = holders.firstNotNullOfOrNull { holder ->
        sequenceOf("name", "display_name", "displayName", "handle", "username")
            .firstNotNullOfOrNull { key -> holder.string(key)?.takeIf(String::isNotBlank) }
    }
    val scopes = holders.firstNotNullOfOrNull { holder ->
        when (val value = holder["scopes"] ?: holder["scope"] ?: holder["access"]) {
            is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> value.contentOrNull?.split(' ', ',', '+')?.filter(String::isNotBlank)
            else -> null
        }
    }
    val canWrite = scopes?.takeIf { it.isNotEmpty() }?.any { it.contains("write", ignoreCase = true) }
    return BingersAccount(name = name, canWrite = canWrite)
}

internal fun parseBingersResults(body: String): List<BingersResult> {
    val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
    val results = root["results"] as? JsonArray ?: return emptyList()
    return results.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        BingersResult(
            status = item.string("status"),
            outcome = item.string("outcome"),
            message = item.string("message"),
        )
    }
}

private fun JsonObject.string(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
