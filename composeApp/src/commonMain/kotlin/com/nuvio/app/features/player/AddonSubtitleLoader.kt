package com.nuvio.app.features.player

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.AddonResource
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.encodeAddonPathSegment
import com.nuvio.app.features.addons.fetchAddonResponseText
import com.nuvio.app.features.servers.ServerItemRef
import com.nuvio.app.features.streams.StreamBehaviorHints
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_addon_subtitle_display_format
import org.jetbrains.compose.resources.getString

@Serializable
data class SubtitleAddonRequest(
    val url: String,
    val addonId: String,
    val addonName: String,
)

data class SubtitleRequestExtras(
    val filename: String? = null,
    val videoSize: Long? = null,
    val videoHash: String? = null,
) {
    fun toPathSegment(): String? = buildList {
        videoHash?.trim()?.takeIf(String::isNotBlank)?.let { hash ->
            add("videoHash=${hash.encodeAddonPathSegment()}")
        }
        videoSize?.takeIf { it > 0L }?.let { size ->
            add("videoSize=$size")
        }
        filename?.trim()?.takeIf(String::isNotBlank)?.let { name ->
            add("filename=${name.encodeAddonPathSegment()}")
        }
    }.joinToString(separator = "&").ifBlank { null }

    companion object {
        fun from(hints: StreamBehaviorHints): SubtitleRequestExtras? =
            SubtitleRequestExtras(
                filename = hints.filename,
                videoSize = hints.videoSize,
                videoHash = hints.videoHash,
            ).takeIf { it.toPathSegment() != null }

        /**
         * Creates extras from stream behavior hints with a filename fallback
         * derived from the playback URL (same pattern as NuvioTV's
         * `streamFilenameFromUrl`).
         */
        fun from(hints: StreamBehaviorHints, sourceUrl: String?): SubtitleRequestExtras? {
            val filename = hints.filename ?: sourceUrl?.let(::sourceFilenameFromUrl)
            return SubtitleRequestExtras(
                filename = filename,
                videoSize = hints.videoSize,
                videoHash = hints.videoHash,
            ).takeIf { it.toPathSegment() != null }
        }
    }
}

internal fun buildSubtitleRequestUrl(
    manifestUrl: String,
    type: String,
    videoId: String,
    extras: SubtitleRequestExtras? = null,
): String = buildAddonResourceUrl(
    manifestUrl = manifestUrl,
    resource = "subtitles",
    type = canonicalSubtitleType(type),
    id = videoId,
    extraPathSegment = extras?.toPathSegment(),
)

internal fun addonSubtitleRequests(
    type: String,
    videoId: String,
    extras: SubtitleRequestExtras? = null,
): List<SubtitleAddonRequest> {
    if (ServerItemRef.isServerId(videoId)) return emptyList()
    val requestType = canonicalSubtitleType(type)
    return AddonRepository.uiState.value.addons.enabledAddons().mapNotNull { addon ->
        val manifest = addon.manifest ?: return@mapNotNull null
        if (manifest.resources.none { resource ->
                (resource.name.equals("subtitles", true) || resource.name.equals("subtitle", true)) &&
                    resource.supportsSubtitleType(requestType, videoId)
            }) return@mapNotNull null
        SubtitleAddonRequest(
            url = buildSubtitleRequestUrl(manifest.transportUrl, requestType, videoId, extras),
            addonId = manifest.id,
            addonName = addon.displayTitle,
        )
    }
}

private const val OPEN_SUBTITLES_URL = "https://opensubtitles-v3.strem.io"
private const val OPEN_SUBTITLES_ID = "org.stremio.opensubtitlesv3"

/**
 * The installed subtitle add-ons' requests for [videoId] plus Stremio's public OpenSubtitles
 * add-on when none of them is it already: used for PikPak/WebDAV files matched to a catalogue
 * title and for the player's online subtitle search, which must work without any add-on.
 */
internal fun subtitleRequestsWithOpenSubtitles(
    type: String,
    videoId: String,
    extras: SubtitleRequestExtras? = null,
): List<SubtitleAddonRequest> {
    val installed = addonSubtitleRequests(type, videoId, extras)
    if (!videoId.startsWith("tt")) return installed
    if (installed.any { it.addonId == OPEN_SUBTITLES_ID || it.url.startsWith(OPEN_SUBTITLES_URL) }) return installed
    return installed + SubtitleAddonRequest(
        url = buildSubtitleRequestUrl("$OPEN_SUBTITLES_URL/manifest.json", type, videoId, extras),
        addonId = OPEN_SUBTITLES_ID,
        addonName = "OpenSubtitles",
    )
}

internal suspend fun loadAddonSubtitles(
    requests: List<SubtitleAddonRequest>,
    onLoaded: (SubtitleAddonRequest, List<AddonSubtitle>) -> Unit = { _, _ -> },
): List<AddonSubtitle> = supervisorScope {
    requests.map { request ->
        async {
            val subtitles = try {
                withTimeoutOrNull(10_000L) {
                    parseAddonSubtitles(fetchAddonResponseText(request.url), request)
                } ?: emptyList<AddonSubtitle>().also {
                    subtitleAddonLog.w { "${request.addonName}: no answer within 10 s" }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                subtitleAddonLog.w { "${request.addonName}: failed (${error.safeReason()})" }
                emptyList()
            }
            currentCoroutineContext().ensureActive()
            // SAMplay 2.0.3: one line per add-on (never its address, which can hold a key), so a
            // subtitle add-on that answers nothing can be told apart from one that has no subtitles.
            subtitleAddonLog.i { "${request.addonName}: ${subtitles.size} subtitles" }
            onLoaded(request, subtitles)
            subtitles
        }
    }.awaitAll().flatten()
}

private val subtitleAddonLog = Logger.withTag("SubtitleAddons")

/** Why an add-on request failed, without the address (add-on addresses can hold keys). */
private fun Throwable.safeReason(): String {
    val name = this::class.simpleName ?: "error"
    val text = message?.takeIf { this is IllegalStateException }
        ?.replace(Regex("https?://[^ ]+"), "<address>")
        ?.take(80)
    return if (text.isNullOrBlank()) name else "$name: $text"
}

private suspend fun parseAddonSubtitles(response: String, request: SubtitleAddonRequest): List<AddonSubtitle> {
    val subtitles = Json.parseToJsonElement(response).jsonObject["subtitles"]?.jsonArray.orEmpty()
    return subtitles.mapIndexedNotNull { index, element ->
        val obj = element as? JsonObject ?: return@mapIndexedNotNull null
        val url = obj.stringValue("url") ?: return@mapIndexedNotNull null
        // SAMplay 2.0.3: a subtitle served through Stremio's own streaming server on the device
        // (YIFY's zips via 127.0.0.1:11470) can never load here, as there is no such server: it
        // used to be listed and then fail when picked.
        if (url.needsStremioStreamingServer()) return@mapIndexedNotNull null
        val language = listOf("lang", "language", "languageCode", "locale", "label")
            .firstNotNullOfOrNull(obj::stringValue) ?: "unknown"
        val addonProvidedId = obj.stringValue("id")
        AddonSubtitle(
            id = addonProvidedId ?: "${request.addonId}_$index",
            url = url,
            language = normalizeLanguageCode(language) ?: language,
            display = getString(
                Res.string.player_addon_subtitle_display_format,
                getLanguageLabelForCode(language),
                request.addonName,
            ),
            addonName = request.addonName,
            label = addonProvidedId?.takeIf { it != language && it != url },
        )
    }
}

internal fun String.needsStremioStreamingServer(): Boolean {
    val lower = trim().lowercase()
    return lower.startsWith("http://127.0.0.1:11470/") || lower.startsWith("http://localhost:11470/")
}

private fun canonicalSubtitleType(type: String): String =
    if (type.equals("tv", ignoreCase = true)) "series" else type.lowercase()

private fun AddonResource.supportsSubtitleType(type: String, videoId: String): Boolean {
    val canonical = canonicalSubtitleType(type)
    val typeMatches = types.isEmpty() || types.any { canonicalSubtitleType(it).equals(canonical, ignoreCase = true) }
    return typeMatches && (idPrefixes.isEmpty() || idPrefixes.any { videoId.startsWith(it) })
}

private fun JsonObject.stringValue(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
