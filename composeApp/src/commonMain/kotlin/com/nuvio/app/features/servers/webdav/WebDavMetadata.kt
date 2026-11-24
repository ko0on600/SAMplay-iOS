package com.nuvio.app.features.servers.webdav

import com.nuvio.app.features.addons.httpRequestRaw
import io.ktor.http.encodeURLParameter
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** What the catalogue knows about a film or show found on a WebDAV drive. */
internal data class WebDavTitleMeta(
    /** Null for a title known only to elCinema. */
    val imdbId: String?,
    /** "movie" or "series". */
    val type: String,
    val name: String,
    val poster: String?,
    val background: String?,
    val logo: String?,
    val description: String?,
    val releaseInfo: String?,
    val genres: List<String>,
    val episodes: List<WebDavEpisodeMeta>,
    /** The page the details came from (elCinema), when not Cinemeta. */
    val sourceUrl: String? = null,
)

internal data class WebDavEpisodeMeta(
    val season: Int,
    val episode: Int,
    val title: String?,
    val thumbnail: String?,
    val overview: String?,
    val released: String?,
)

/**
 * Posters, backgrounds and descriptions for the films and shows on a WebDAV drive, found by name
 * through Cinemeta (Stremio's public catalogue, no API key needed). Results, misses included, are
 * kept for the session.
 */
internal object WebDavMetadata {
    private const val CINEMETA = "https://v3-cinemeta.strem.io"
    private const val LOOKUP_TIMEOUT_MS = 8_000L

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = SynchronizedObject()
    private val searches = mutableMapOf<String, Lookup>()
    private val details = mutableMapOf<String, Lookup>()

    /** A lookup that completed, match or not; timeouts and network errors are not kept. */
    private class Lookup(val meta: WebDavTitleMeta?)

    /** The best catalogue match for a file or folder [name], searching films or shows. */
    suspend fun find(name: String, series: Boolean, yearHint: Int? = null): WebDavTitleMeta? {
        val parsed = WebDavNames.parse(name)
        val title = WebDavNames.lookupTitle(name)
        if (title.length < 2) return null
        val year = yearHint ?: parsed.year
        val type = if (series) "series" else "movie"
        val key = "$type|${WebDavNames.normalize(title)}|${year ?: 0}"
        synchronized(lock) { searches[key] }?.let { return it.meta }
        val outcome = completed { search(type, title, year) } ?: return null
        synchronized(lock) { searches[key] = outcome }
        return outcome.meta
    }

    /** Full catalogue entry (description, genres, episodes) for an IMDb id. */
    suspend fun details(imdbId: String, type: String): WebDavTitleMeta? {
        val key = "$type|$imdbId"
        synchronized(lock) { details[key] }?.let { return it.meta }
        val outcome = completed {
            get("$CINEMETA/meta/$type/$imdbId.json")?.let { body ->
                json.parseToJsonElement(body).jsonObject["meta"]?.jsonObject?.toMeta(type)
            }
        } ?: return null
        synchronized(lock) { details[key] = outcome }
        return outcome.meta
    }

    private suspend fun completed(block: suspend () -> WebDavTitleMeta?): Lookup? = try {
        withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { Lookup(block()) }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        null
    }

    /** Titles matching a free-text [query] (both films and shows), for the online subtitle search. */
    suspend fun searchAll(query: String): List<WebDavTitleMeta> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()
        val movies = results("movie", trimmed)
        val shows = results("series", trimmed)
        return movies.take(10) + shows.take(10)
    }

    private suspend fun search(type: String, title: String, year: Int?): WebDavTitleMeta? {
        // Arabic names: elCinema knows Arabic titles and their Arabic posters.
        if (title.hasArabic()) WebDavOnlineInfo.findArabic(title, series = type == "series", year = year)?.let { return it }
        val metas = results(type, title)
        if (metas.isEmpty()) return null
        if (title.hasArabic()) {
            // Cinemeta finds Arabic names through IMDb's alternative titles but answers in English,
            // so its best match is taken as long as the years agree.
            return metas.take(3).firstOrNull { meta ->
                val metaYear = meta.releaseInfo?.take(4)?.toIntOrNull()
                year == null || metaYear == null || kotlin.math.abs(metaYear - year) <= 1
            }
        }
        val exact = metas.filter { WebDavNames.titleMatches(it.name, title) }
        val byYear = exact.firstOrNull { meta ->
            val metaYear = meta.releaseInfo?.take(4)?.toIntOrNull()
            year == null || metaYear == null || kotlin.math.abs(metaYear - year) <= 1
        }
        if (byYear != null) return byYear
        if (exact.isNotEmpty() && year == null) return exact.first()
        // A close name (a subtitle dropped: "Dune" for "Dune: Part One") is usually the same work,
        // but only when the shorter name is most of the longer one and the years agree.
        val wanted = WebDavNames.normalize(title)
        return metas.take(5).firstOrNull { meta ->
            val name = WebDavNames.normalize(meta.name)
            val shorter = minOf(name.length, wanted.length)
            val longer = maxOf(name.length, wanted.length)
            val metaYear = meta.releaseInfo?.take(4)?.toIntOrNull()
            name.isNotEmpty() && shorter >= 4 && shorter * 10 >= longer * 7 &&
                (name.startsWith("$wanted ") || wanted.startsWith("$name ")) &&
                (year == null || metaYear == null || kotlin.math.abs(metaYear - year) <= 1)
        }
    }

    private suspend fun results(type: String, query: String): List<WebDavTitleMeta> {
        val body = get("$CINEMETA/catalog/$type/top/search=${query.encodeURLParameter()}.json") ?: return emptyList()
        val metas = json.parseToJsonElement(body).jsonObject["metas"] as? JsonArray ?: return emptyList()
        return metas.mapNotNull { (it as? JsonObject)?.toMeta(type) }
    }

    /** The response body; throws on a server error so the miss is not remembered. */
    private suspend fun get(url: String): String? {
        val response = httpRequestRaw(method = "GET", url = url, headers = mapOf("Accept" to "application/json"), body = "")
        if (response.status >= 500) error("HTTP ${response.status}")
        return response.body.takeIf { response.status in 200..299 && it.isNotBlank() }
    }

    private fun JsonObject.toMeta(fallbackType: String): WebDavTitleMeta? {
        val id = string("imdb_id")?.takeIf { it.startsWith("tt") } ?: string("id")?.takeIf { it.startsWith("tt") } ?: return null
        val type = string("type") ?: fallbackType
        val episodes = (this["videos"] as? JsonArray).orEmpty().mapNotNull { element ->
            val video = element as? JsonObject ?: return@mapNotNull null
            val season = video.int("season") ?: return@mapNotNull null
            val episode = video.int("episode") ?: video.int("number") ?: return@mapNotNull null
            WebDavEpisodeMeta(
                season = season,
                episode = episode,
                title = video.string("name") ?: video.string("title"),
                thumbnail = video.string("thumbnail"),
                overview = video.string("overview") ?: video.string("description"),
                released = video.string("released")?.take(10),
            )
        }
        return WebDavTitleMeta(
            imdbId = id,
            type = type,
            name = string("name") ?: return null,
            poster = string("poster") ?: "https://images.metahub.space/poster/medium/$id/img",
            background = string("background") ?: "https://images.metahub.space/background/medium/$id/img",
            logo = string("logo"),
            description = string("description"),
            releaseInfo = string("releaseInfo") ?: string("year"),
            genres = (this["genres"] as? JsonArray ?: this["genre"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            episodes = episodes,
        )
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private fun JsonObject.int(name: String): Int? =
        (this[name] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
}
