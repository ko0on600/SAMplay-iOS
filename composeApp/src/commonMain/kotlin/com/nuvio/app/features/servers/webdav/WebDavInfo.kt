package com.nuvio.app.features.servers.webdav

import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.servers.ServerRepository
import com.nuvio.app.features.servers.ServerStorage
import io.ktor.http.Url
import kotlinx.serialization.json.JsonElement
import kotlin.time.Clock
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import io.ktor.http.encodeURLParameter
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Poster and details the user set for one drive file or folder, by hand or from a link. */
@Serializable
internal data class WebDavInfoOverride(
    val name: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val description: String? = null,
    val year: String? = null,
    /** IMDb id ("tt…"): brings episode titles and online subtitles. */
    val imdbId: String? = null,
    /** "movie" or "series" when the source says; [TYPE_FOLDER] for a plain section the user named. */
    val type: String? = null,
    val genres: List<String> = emptyList(),
    val sourceUrl: String? = null,
    /** When it was set (epoch ms): between devices the newer edit wins. */
    val updatedAt: Long = 0L,
    /** A removed edit, kept so the removal reaches the other devices. */
    val deleted: Boolean = false,
    /** The drive copy ("webdav-file:…") of a picture [poster] shows from this device's files. */
    val drivePoster: String? = null,
) {
    /** A plain section: only its name and picture, never looked up. */
    val isFolder: Boolean
        get() = type == TYPE_FOLDER

    val isEmpty: Boolean
        get() = !isFolder && listOf(name, poster, background, description, year, imdbId).all { it.isNullOrBlank() }

    /** As other devices get it: a picture in this device's files goes by its drive copy. */
    fun shared(): WebDavInfoOverride =
        copy(poster = if (poster?.startsWith("file:") == true) drivePoster else poster, drivePoster = null)

    companion object {
        /** Same value SAMplay writes, so the three apps read each other's sections. */
        const val TYPE_FOLDER = "folder"
    }
}

internal fun WebDavTitleMeta.toOverride(): WebDavInfoOverride = WebDavInfoOverride(
    name = name.ifBlank { null },
    poster = poster,
    background = background,
    description = description,
    year = releaseInfo,
    imdbId = imdbId,
    type = type.ifBlank { null },
    genres = genres,
    sourceUrl = sourceUrl,
)

/**
 * The user's poster/details overrides, per connection and path, kept with the media servers.
 * Shared with the TV app through the drive itself (see [WebDavDriveSync]) and also synced through
 * the Nuvio account (the profile settings blob).
 */
internal object WebDavInfoOverrides {
    private const val STORAGE_KEY = "webdav_info_overrides"
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = SynchronizedObject()
    private var entries: MutableMap<String, WebDavInfoOverride>? = null
    private val _changes = MutableStateFlow(0)

    /** Bumped on every change, for screens that show overridden artwork. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    fun get(connectionId: String, path: String): WebDavInfoOverride? =
        synchronized(lock) { loaded()[key(connectionId, path)]?.takeUnless { it.deleted } }

    fun set(connectionId: String, path: String, value: WebDavInfoOverride?) {
        val now = Clock.System.now().toEpochMilliseconds()
        synchronized(lock) {
            val map = loaded()
            val key = key(connectionId, path)
            // A picture kept as it was still goes by its drive copy.
            val drivePoster = map[key]?.takeIf { it.poster == value?.poster }?.drivePoster
            map[key] = if (value == null || value.isEmpty) {
                WebDavInfoOverride(updatedAt = now, deleted = true)
            } else {
                value.copy(updatedAt = now, deleted = false, drivePoster = drivePoster)
            }
            save(map)
        }
        _changes.value += 1
        WebDavDriveSync.schedulePush(connectionId)
    }

    /** This connection's entries, by path. */
    fun entriesFor(connectionId: String): Map<String, WebDavInfoOverride> {
        val prefix = "$connectionId|"
        return synchronized(lock) {
            loaded().filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
        }
    }

    /** Takes the newer edits from the drive's shared file (path → entry). */
    fun mergeDrive(connectionId: String, remote: JsonObject): Boolean {
        var changed = false
        synchronized(lock) {
            val map = loaded()
            remote.forEach { (path, element) ->
                val incoming = runCatching { json.decodeFromJsonElement(WebDavInfoOverride.serializer(), element) }.getOrNull()
                    ?: return@forEach
                val local = map[key(connectionId, path)]
                if (local == null || incoming.updatedAt > local.updatedAt) {
                    map[key(connectionId, path)] = incoming
                    changed = true
                }
            }
            if (changed) save(map)
        }
        if (changed) _changes.value += 1
        return changed
    }

    /** Swaps in this device's copy of a drive picture (or records an upload); not an edit. */
    fun replaceQuietly(connectionId: String, path: String, value: WebDavInfoOverride) {
        synchronized(lock) {
            val map = loaded()
            map[key(connectionId, path)] = value
            save(map)
        }
        _changes.value += 1
    }

    fun encodeShared(value: WebDavInfoOverride): JsonElement =
        json.encodeToJsonElement(WebDavInfoOverride.serializer(), value.shared())

    /**
     * The overrides for the account's settings blob, keyed "host|path" so any device on the same
     * drive finds them. A picture picked from this phone's gallery goes by its drive copy.
     */
    fun exportForSync(): JsonObject {
        val entries = synchronized(lock) { loaded().toMap() }
        val out = linkedMapOf<String, JsonElement>()
        entries.forEach { (key, value) ->
            val connectionId = key.substringBefore('|')
            val path = key.substringAfter('|')
            val host = hostOf(connectionId) ?: return@forEach
            out["$host|$path"] = json.encodeToJsonElement(WebDavInfoOverride.serializer(), value.shared())
        }
        return JsonObject(out)
    }

    /** Takes the newer edits from the account's settings blob (another phone's). */
    fun importFromSync(remote: JsonObject) {
        if (remote.isEmpty()) return
        var changed = false
        synchronized(lock) {
            val map = loaded()
            val connections = ServerRepository.enabledConnections().filter { it.providerId == WebDavProvider.ID }
            remote.forEach { (syncKey, element) ->
                val incoming = runCatching { json.decodeFromJsonElement(WebDavInfoOverride.serializer(), element) }.getOrNull()
                    ?: return@forEach
                val host = syncKey.substringBefore('|')
                val path = syncKey.substringAfter('|')
                connections.filter { hostOf(it.id) == host }.forEach { connection ->
                    val local = map[key(connection.id, path)]
                    if (local == null || incoming.updatedAt > local.updatedAt) {
                        // Keep this phone's own gallery picture when the other side had none to share.
                        val poster = incoming.poster ?: local?.poster?.takeIf { it.startsWith("file:") && !incoming.deleted }
                        map[key(connection.id, path)] = incoming.copy(poster = poster)
                        changed = true
                    }
                }
            }
            if (changed) save(map)
        }
        if (changed) _changes.value += 1
    }

    private fun hostOf(connectionId: String): String? =
        ServerRepository.connection(connectionId)
            ?.takeIf { it.providerId == WebDavProvider.ID }
            ?.let { runCatching { Url(it.address).host.lowercase() }.getOrNull() }

    private fun save(map: Map<String, WebDavInfoOverride>) {
        ServerStorage.write(STORAGE_KEY, json.encodeToString(map.toMap()))
    }

    private fun loaded(): MutableMap<String, WebDavInfoOverride> {
        entries?.let { return it }
        val stored = ServerStorage.read(STORAGE_KEY)?.let { raw ->
            runCatching { json.decodeFromString<Map<String, WebDavInfoOverride>>(raw) }.getOrNull()
        }.orEmpty()
        return stored.toMutableMap().also { entries = it }
    }

    private fun key(connectionId: String, path: String) = "$connectionId|$path"
}

/**
 * Posters and details from the sites Arabic titles live on: elCinema (searched by name and read
 * from its title pages), and links from IMDb, TMDB, TheTVDB, elCinema or any page with Open Graph
 * tags. TMDB pages refuse apps, so a TMDB link goes through TMDB's API when a TMDB key is set in
 * Settings, otherwise through Wikidata to the title's IMDb id.
 */
internal object WebDavOnlineInfo {
    private const val ELCINEMA = "https://elcinema.com"
    private const val WIKIDATA = "https://www.wikidata.org/w/api.php"
    private const val BROWSER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
    private const val MAX_PAGE_BYTES = 3 * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = SynchronizedObject()
    private val works = mutableMapOf<String, WebDavTitleMeta?>()

    /** One elCinema search result. */
    class ElcinemaHit(val id: String, val title: String, val poster: String?)

    suspend fun elcinemaSearch(query: String): List<ElcinemaHit> {
        val html = page("$ELCINEMA/search/?q=${query.trim().encodeURLParameter()}") ?: return emptyList()
        return ELCINEMA_HIT.findAll(html).map { match ->
            ElcinemaHit(
                id = match.groupValues[1],
                title = decodeHtml(match.groupValues[3]).trim(),
                poster = match.groupValues[2].takeUnless { it.contains("blank_photos") },
            )
        }.distinctBy { it.id }.toList()
    }

    /** An elCinema title page: Arabic name, poster, story, type, year and genres. */
    suspend fun elcinemaWork(id: String): WebDavTitleMeta? {
        synchronized(lock) { if (works.containsKey(id)) return works[id] }
        val html = page("$ELCINEMA/work/$id/") ?: return null
        val ogTitle = decodeHtml(metaTag(html, "og:title").orEmpty())
        // "KIND - NAME - YEAR مشاهدة اونلاين…"; the kind itself may contain " - " ("فيلم - قصير").
        val parts = ogTitle.split(" - ")
        val yearAt = parts.indexOfFirst { YEAR_FIRST.containsMatchIn(it.trim()) }
        val name = when {
            yearAt >= 1 -> parts[yearAt - 1]
            parts.size >= 2 -> parts[1].substringBefore(" مشاهدة")
            else -> parts.firstOrNull().orEmpty()
        }.trim()
        val kind = Regex("""نوع العمل:</li>\s*<li>([^<]+)</li>""").find(html)?.groupValues?.get(1)?.trim().orEmpty()
        val story = Regex("""ملخص القصة:</strong>\s*<p>\s*(?:<p>)?(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.let { decodeHtml(it.replace(Regex("<[^>]+>"), "")).trim() }
        val meta = WebDavTitleMeta(
            imdbId = null,
            type = when {
                kind.contains("مسلسل") -> "series"
                kind.contains("فيلم") -> "movie"
                else -> kind
            },
            name = name,
            poster = metaTag(html, "og:image")?.takeUnless { it.contains("blank_photos") },
            background = null,
            logo = null,
            description = story?.ifBlank { null },
            releaseInfo = YEAR.find(ogTitle)?.value,
            genres = Regex("""/index/work/genre/\d+">([^<]+)</a>""").findAll(html)
                .map { decodeHtml(it.groupValues[1]).trim() }.distinct().take(4).toList(),
            episodes = emptyList(),
            sourceUrl = "$ELCINEMA/work/$id/",
        ).takeIf { name.isNotBlank() }
        synchronized(lock) { works[id] = meta }
        return meta
    }

    /**
     * An Arabic-named film or show on elCinema: the same title (letters normalised), the right
     * kind (film or series), a real poster, and a year within one of [year] when both are known.
     */
    suspend fun findArabic(title: String, series: Boolean, year: Int?): WebDavTitleMeta? {
        val wanted = WebDavNames.normalize(title)
        val hits = elcinemaSearch(title).filter { WebDavNames.normalize(it.title) == wanted && it.poster != null }.take(4)
        for (hit in hits) {
            val work = elcinemaWork(hit.id) ?: continue
            if (work.poster == null || work.type != (if (series) "series" else "movie")) continue
            val workYear = work.releaseInfo?.toIntOrNull()
            if (year != null && workYear != null && kotlin.math.abs(workYear - year) > 1) continue
            return work
        }
        return null
    }

    /** Titles for the editor's search: Cinemeta (films and shows) and elCinema, Arabic first for Arabic names. */
    suspend fun searchTitles(query: String): List<WebDavTitleMeta> {
        val catalogue = runCatching { WebDavMetadata.searchAll(query) }.getOrDefault(emptyList())
        val elcinema = runCatching { elcinemaSearch(query) }.getOrDefault(emptyList()).take(12).map { hit ->
            WebDavTitleMeta(
                imdbId = null, type = "", name = hit.title, poster = hit.poster, background = null, logo = null,
                description = null, releaseInfo = null, genres = emptyList(), episodes = emptyList(),
                sourceUrl = "$ELCINEMA/work/${hit.id}/",
            )
        }
        return if (query.hasArabic()) elcinema + catalogue else catalogue + elcinema
    }

    /** Full details for a search result the user picked. */
    suspend fun details(result: WebDavTitleMeta): WebDavInfoOverride {
        result.sourceUrl?.let { url -> fromLink(url)?.let { return it } }
        val imdbId = result.imdbId ?: return result.toOverride()
        val full = WebDavMetadata.details(imdbId, result.type.ifBlank { "movie" })
        return (full ?: result).toOverride()
    }

    /**
     * Poster and details from a link: an image, or a title page on IMDb, TMDB, TheTVDB, elCinema,
     * or any site with Open Graph tags. Null when the link names nothing usable.
     */
    suspend fun fromLink(link: String): WebDavInfoOverride? {
        val url = link.trim().let { if (it.contains("://")) it else "https://$it" }
        val lower = url.lowercase()
        if (IMAGE_URL.containsMatchIn(lower)) return WebDavInfoOverride(poster = url, sourceUrl = url)
        Regex("""elcinema\.com/(?:en/)?work/(\d+)""").find(lower)?.let { match ->
            return elcinemaWork(match.groupValues[1])?.toOverride()
        }
        Regex("""themoviedb\.org/(movie|tv)/(\d+)""").find(lower)?.let { match ->
            return fromTmdb(match.groupValues[1], match.groupValues[2], url)
        }
        IMDB_ID.find(url)?.let { match ->
            if (lower.contains("imdb.com")) return fromImdb(match.value, url)
        }
        val html = page(url) ?: return null
        // TheTVDB and many other pages link the title's IMDb page.
        IMDB_ID.find(Regex("""imdb\.com/title/tt\d+""").find(html)?.value.orEmpty())?.let { match ->
            fromImdb(match.value, url)?.let { return it }
        }
        val poster = metaTag(html, "og:image")
            ?: Regex("""https://artworks\.thetvdb\.com/banners/[^"']*posters?/[^"']+\.(?:jpg|png|webp)""").find(html)?.value
        val title = metaTag(html, "og:title") ?: Regex("""<title>([^<]+)</title>""").find(html)?.groupValues?.get(1)
        if (poster == null && title == null) return null
        return WebDavInfoOverride(
            name = title?.let(::decodeHtml)?.substringBefore(" - TheTVDB")?.trim(),
            poster = poster,
            description = metaTag(html, "og:description")?.let(::decodeHtml),
            sourceUrl = url,
        )
    }

    /**
     * Cinemeta first (it brings episodes); new or Arabic titles it lacks come from IMDb's own
     * suggestion service. Null when IMDb has no film or show under [imdbId] (a podcast episode,
     * a mistyped id), so the editor says so instead of keeping a bare id.
     */
    private suspend fun fromImdb(imdbId: String, url: String): WebDavInfoOverride? {
        val meta = WebDavMetadata.details(imdbId, "series") ?: WebDavMetadata.details(imdbId, "movie")
        val found = meta?.toOverride() ?: runCatching { imdbSuggestion(imdbId) }.getOrNull() ?: return null
        return found.copy(imdbId = imdbId, sourceUrl = url)
    }

    /** IMDb's public suggestion service: name, poster, year and kind of one title. */
    private suspend fun imdbSuggestion(imdbId: String): WebDavInfoOverride? {
        val body = get("https://v3.sg.media-imdb.com/suggestion/x/$imdbId.json") ?: return null
        val hit = (json.parseToJsonElement(body).jsonObject["d"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { it.text("id") == imdbId } ?: return null
        val kind = hit.text("qid").orEmpty()
        val series = kind.startsWith("tvSeries") || kind == "tvMiniSeries"
        if (!series && kind !in IMDB_FILM_KINDS) return null
        return WebDavInfoOverride(
            name = hit.text("l") ?: return null,
            poster = (hit["i"] as? JsonObject)?.text("imageUrl"),
            year = (hit["y"] as? JsonPrimitive)?.contentOrNull,
            type = if (series) "series" else "movie",
        )
    }

    private suspend fun fromTmdb(kind: String, id: String, url: String): WebDavInfoOverride? {
        val key = runCatching { TmdbSettingsRepository.effectiveApiKey() }.getOrDefault("")
        if (key.isNotBlank()) {
            tmdbApi(kind, id, key, url)?.let { return it }
        }
        val (imdbId, arabicName) = wikidataForTmdb(kind, id) ?: return null
        val base = imdbId?.let { fromImdb(it, url) } ?: WebDavInfoOverride(sourceUrl = url)
        return base.copy(name = arabicName ?: base.name, type = if (kind == "tv") "series" else "movie")
    }

    /** TMDB's own API (needs a key): Arabic title and story when TMDB has them. */
    private suspend fun tmdbApi(kind: String, id: String, key: String, url: String): WebDavInfoOverride? {
        val body = get(
            "https://api.themoviedb.org/3/$kind/$id?api_key=${key.encodeURLParameter()}&language=ar&append_to_response=external_ids",
        ) ?: return null
        val root = json.parseToJsonElement(body).jsonObject
        val image = { path: String? -> path?.let { "https://image.tmdb.org/t/p/w780$it" } }
        val external = root["external_ids"] as? JsonObject
        return WebDavInfoOverride(
            name = root.text("name") ?: root.text("title"),
            poster = image(root.text("poster_path")),
            background = image(root.text("backdrop_path")),
            description = root.text("overview"),
            year = (root.text("first_air_date") ?: root.text("release_date"))?.take(4),
            imdbId = (root.text("imdb_id") ?: external?.text("imdb_id"))?.takeIf { it.startsWith("tt") },
            type = if (kind == "tv") "series" else "movie",
            genres = (root["genres"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.text("name") },
            sourceUrl = url,
        )
    }

    /** IMDb id and Arabic name of the Wikidata item carrying a TMDB id. */
    private suspend fun wikidataForTmdb(kind: String, id: String): Pair<String?, String?>? {
        val property = if (kind == "tv") "P4983" else "P4947"
        val search = get("$WIKIDATA?action=query&list=search&srsearch=haswbstatement:$property=$id&format=json") ?: return null
        val item = Regex(""""title"\s*:\s*"(Q\d+)"""").find(search)?.groupValues?.get(1) ?: return null
        val entity = get("$WIKIDATA?action=wbgetentities&ids=$item&props=claims%7Clabels&languages=ar&format=json") ?: return null
        val imdbId = Regex(""""P345".*?"value"\s*:\s*"(tt\d+)"""", RegexOption.DOT_MATCHES_ALL).find(entity)?.groupValues?.get(1)
        val arabic = runCatching {
            val root = json.parseToJsonElement(entity).jsonObject["entities"]?.jsonObject?.get(item)?.jsonObject
            (root?.get("labels")?.jsonObject?.get("ar") as? JsonObject)?.text("value")
        }.getOrNull()
        return imdbId to arabic
    }

    private suspend fun page(url: String): String? {
        val response = httpRequestRaw(
            method = "GET",
            url = url,
            headers = mapOf("User-Agent" to BROWSER_AGENT, "Accept-Language" to "ar,en;q=0.8", "Accept" to "text/html,*/*"),
            body = "",
            maxResponseBodyBytes = MAX_PAGE_BYTES,
        )
        if (response.status >= 500) error("HTTP ${response.status}")
        return response.body.takeIf { response.status in 200..299 && it.isNotBlank() }
    }

    private suspend fun get(url: String): String? {
        val response = httpRequestRaw(
            method = "GET",
            url = url,
            headers = mapOf("Accept" to "application/json", "User-Agent" to "NuvioRS/1.0 (personal media app)"),
            body = "",
        )
        if (response.status >= 500) error("HTTP ${response.status}")
        return response.body.takeIf { response.status in 200..299 && it.isNotBlank() }
    }

    private fun metaTag(html: String, property: String): String? {
        val name = Regex.escape(property)
        return (
            Regex("""<meta[^>]+(?:property|name)="$name"[^>]*\scontent="([^"]*)"""").find(html)
                ?: Regex("""<meta[^>]+content="([^"]*)"[^>]*(?:property|name)="$name"""").find(html)
            )?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun JsonObject.text(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private val ELCINEMA_HIT = Regex(
        """<a href="/work/(\d+)/"><img[^>]*data-src="([^"]+)"[^>]*></a>\s*</div>\s*<ul[^>]*>\s*<li><a href="/work/\1/">([^<]+)</a>""",
    )
    private val IMDB_ID = Regex("""tt\d{5,10}""")
    private val IMDB_FILM_KINDS = setOf("movie", "tvMovie", "short", "tvShort", "video", "tvSpecial")
    private val IMAGE_URL = Regex("""\.(?:jpe?g|png|webp)(?:\?.*)?$""")
    private val YEAR = Regex("""\b(?:19|20)\d{2}\b""")
    private val YEAR_FIRST = Regex("""^(?:19|20)\d{2}\b""")
}

internal fun String.hasArabic(): Boolean = any { it in '؀'..'ۿ' }

/** &amp;, &quot;, &#39;, &#x27;… in page text. */
internal fun decodeHtml(value: String): String = value
    .replace(Regex("""&#x([0-9a-fA-F]+);""")) { it.groupValues[1].toIntOrNull(16)?.let(::codePointString) ?: it.value }
    .replace(Regex("""&#(\d+);""")) { it.groupValues[1].toIntOrNull()?.let(::codePointString) ?: it.value }
    .replace("&quot;", "\"")
    .replace("&apos;", "'")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&nbsp;", " ")
    .replace("&amp;", "&")

private fun codePointString(code: Int): String =
    if (code < 0x10000) code.toChar().toString()
    else charArrayOf((0xD800 + ((code - 0x10000) shr 10)).toChar(), (0xDC00 + ((code - 0x10000) and 0x3FF)).toChar()).concatToString()
