package com.nuvio.app.features.servers.webdav

import com.nuvio.app.core.network.readBoundedResponseBody
import com.nuvio.app.features.servers.ServerException
import com.nuvio.app.features.servers.ServerFailure
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.contentLength
import io.ktor.http.decodeURLPart
import io.ktor.http.encodedPath
import io.ktor.http.takeFrom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlinx.atomicfu.locks.synchronized
import kotlinx.atomicfu.locks.SynchronizedObject
import io.ktor.client.plugins.timeout
import kotlin.io.encoding.Base64

/** One entry of a WebDAV folder listing (a `<response>` of a PROPFIND multistatus). */
internal data class WebDavEntry(
    /** Decoded, absolute path on the server, folders end with '/'. */
    val path: String,
    val name: String,
    val isFolder: Boolean,
    val size: Long?,
    val contentType: String?,
    val lastModified: String?,
) {
    /**
     * Last path segment from the href. PikPak (and some other DAV servers) often put a
     * clean title in `<displayname>` without the real extension; video/subtitle detection
     * must use this, or folder pages filter every file out and show an empty catalog.
     */
    val fileName: String
        get() = path.trimEnd('/').substringAfterLast('/').ifBlank { name }

    /**
     * Whether this entry should appear as a playable video in the catalog.
     *
     * SAMplay's working PikPak browser uses: extension on the display name, or a
     * Content-Type under the video/ tree. sam2 only checked [fileName]; that still left
     * folders empty when PikPak omitted the extension in the href and left Content-Type
     * as the only signal — or when both name and href were extensionless titles.
     * Match SAMplay, then also accept href extensions and extensionless non-sidecar files.
     */
    fun looksLikeVideo(): Boolean {
        if (isFolder) return false
        if (WebDavNames.isVideo(fileName) || WebDavNames.isVideo(name)) return true
        // Before the Content-Type: a server that labels every file video/* must not make episodes of them.
        if (WebDavNames.isSubtitle(fileName) || WebDavNames.isSubtitle(name)) return false
        if (WebDavNames.isSidecar(fileName) || WebDavNames.isSidecar(name)) return false
        val ct = contentType?.lowercase().orEmpty()
        if (ct.startsWith("video/")) return true
        if (ct == "application/x-mpegurl" || ct == "application/vnd.apple.mpegurl") return true
        // Nuvio RS: a server that says what the file is, and it is no video (before the checks
        // below: "audio/mpeg" is an mp3, "audio/mp4" an m4a).
        if (NON_VIDEO_TYPES.any { ct.startsWith(it) }) return false
        if ("matroska" in ct || "mp4" in ct || "mpeg" in ct) return true
        // PikPak: renamed titles with no extension anywhere — still playable. Nuvio RS: so are
        // names with a dot but no video extension ("الرسالة.1976", "فيلم.HD", an ".rm" film),
        // which left the user's "عربي" folder empty; only files surely no video stay out.
        return !WebDavNames.isKnownNonVideo(fileName) && !WebDavNames.isKnownNonVideo(name)
    }

    private companion object {
        val NON_VIDEO_TYPES = listOf("audio/", "image/", "text/", "application/pdf", "application/zip", "application/x-rar")
    }
}

/** The URL the player reads, and whether it still needs the drive's credentials. */
/**
 * @param missing SAMplay 2.0.3: the drive answered 404 for the file itself (PikPak lost its folder,
 * see [WebDavClient.revisitParents]); not set when the drive was only slow.
 */
internal data class ResolvedPlayback(val url: String, val needsAuthorization: Boolean, val missing: Boolean = false)

/**
 * Minimal WebDAV client: PROPFIND (Depth 0/1) with HTTP Basic auth, which is all a
 * read-only media browser needs. Works with PikPak (dav.pikpak.ai / dav.mypikpak.com),
 * Nextcloud, Synology, rclone serve webdav, Alist and other standard servers.
 */
internal class WebDavClient(private val http: HttpClient) {

    suspend fun propfind(baseUrl: String, path: String, authorization: String, depth: Int): List<WebDavEntry> {
        var url = resolve(baseUrl, path)
        var response = execute(url, authorization, depth)
        // Follow redirects (trailing-slash or encoding normalisation). Auth stays on every
        // hop: listing never leaves the DAV host the way playback CDN redirects do.
        var hops = 0
        while (response.status in 300..399 && hops < MAX_REDIRECTS) {
            val location = response.location ?: break
            url = runCatching { URLBuilder(url).takeFrom(location).buildString() }.getOrNull() ?: break
            response = execute(url, authorization, depth)
            hops++
        }
        // Some servers answer a folder without its trailing slash with a redirect that
        // was already consumed above; still try the slash form once if we never moved.
        if (response.status in 300..399 && !path.endsWith('/') && hops == 0) {
            response = execute(resolve(baseUrl, "$path/"), authorization, depth)
        }
        throwForStatus(response.status)
        return parseMultistatus(response.body, requestPath = path)
    }

    /**
     * SAMplay 2.0.3: lists every folder above [path] again, from the address's root down, and
     * says how many it listed. On the user's TV (19 Nov 2026) PikPak found a folder with an Arabic
     * name ("/sam/english/مسلسلات/") only for about half a minute after the folder above it had
     * been listed; after that the folder, and every folder and file under it ("Cougar Town/S01"),
     * answered 404 again and again until "english" was listed again. Folders with English names
     * always opened. Called after a 404, so the next try finds the path. Stops at the first
     * folder that fails (the ones under it would too).
     */
    suspend fun revisitParents(baseUrl: String, path: String, authorization: String): Int {
        var listed = 0
        for (folder in parentFolders(path, rootPath(baseUrl))) {
            try {
                propfind(baseUrl, folder, authorization, depth = 1)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                break
            }
            listed++
        }
        return listed
    }

    /**
     * Where the player should read [url] from.
     *
     * PikPak answers a file open with a redirect to a signed CDN URL. sam1–sam3 probed with
     * Range then a full GET (up to 8 hops each) on the shared 30s HttpClient timeout — that
     * alone could burn many seconds before ExoPlayer started, then the player opened again
     * (double network). Android [PlayerPlaybackNetworking] already keeps Authorization on the
     * DAV host only and strips it on CDN hops, so a missed/slow probe can safely fall back to
     * the DAV URL + Auth and let the player follow redirects.
     *
     * Fast path: brief in-memory CDN cache → HEAD redirect chase → tiny Range(0-0) chase,
     * short per-request timeouts, hard budget. Never downloads a body. Caches signed CDN URLs
     * briefly so a second tap / seek prep is instant.
     */
    suspend fun resolvePlayback(url: String, authorization: String): ResolvedPlayback {
        cachedPlayback(url)?.let { return it }

        val probed = withTimeoutOrNull(PROBE_BUDGET_MS) {
            followRedirectChain(url, authorization, useHead = true)
                ?: followRedirectChain(url, authorization, useHead = false)
        }

        val resolved = when {
            probed != null && !sameOrigin(probed.url, url) ->
                ResolvedPlayback(probed.url, needsAuthorization = false)
            else ->
                ResolvedPlayback(url, needsAuthorization = true, missing = probed?.missing == true)
        }
        if (!resolved.needsAuthorization) rememberPlayback(url, resolved)
        return resolved
    }

    /**
     * SAMplay 2.0.3: the link "Copy stream link" hands to a download app (the user's SMD), which
     * must work without the drive's login. On the user's phone, 19 Nov 2026, every copy of a PikPak
     * file said "No direct stream link available": [resolvePlayback] asks with HEAD first and stops
     * at any answer but a redirect (or a HEAD slower than 3 s), so the Range GET that gets PikPak's
     * redirect was never sent. The TV asks with the Range GET only, and its links work.
     *
     * Only GET with Range bytes=0-0 (the body is never read), with longer timeouts than the
     * player's probe since no player is waiting. [onStep] gets each answer's status, -1 when the
     * request failed; never the address, since a signed link is a credential. The link counts once
     * a redirect left the drive and the next host answered 2xx (or 416, for the Range).
     */
    suspend fun signedLink(
        url: String,
        authorization: String,
        onStep: (status: Int) -> Unit = {},
    ): ResolvedPlayback {
        var current = url
        for (hop in 0 until PLAYBACK_MAX_REDIRECTS) {
            val (status, location) = try {
                http.prepareRequest(current) {
                    method = HttpMethod.Get
                    timeout {
                        requestTimeoutMillis = LINK_REQUEST_TIMEOUT_MS
                        connectTimeoutMillis = LINK_CONNECT_TIMEOUT_MS
                        socketTimeoutMillis = LINK_REQUEST_TIMEOUT_MS
                    }
                    if (sameOrigin(current, url)) header("Authorization", authorization)
                    header("User-Agent", BROWSER_UA)
                    header("Accept", "*/*")
                    header("Range", "bytes=0-0")
                }.execute { response -> response.status.value to response.headers["Location"] }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                -1 to null
            }
            onStep(status)
            val leftTheDrive = !sameOrigin(current, url)
            when {
                status in 300..399 && location != null ->
                    current = runCatching { URLBuilder(current).takeFrom(location).buildString() }.getOrNull()
                        ?: break
                leftTheDrive && (status in 200..299 || status == 416) ->
                    return ResolvedPlayback(current, needsAuthorization = false).also { rememberPlayback(url, it) }
                status == 404 && hop == 0 -> return ResolvedPlayback(url, needsAuthorization = true, missing = true)
                else -> break
            }
        }
        return ResolvedPlayback(url, needsAuthorization = true)
    }

    /** Instant cache hit for [preparePlayback]; null when cold or expired. */
    fun cachedPlayback(url: String): ResolvedPlayback? {
        val now = Clock.System.now().toEpochMilliseconds()
        synchronized(playbackCacheLock) {
            val hit = playbackCache[url] ?: return null
            if (isForgotten(hit.value.url)) {
                playbackCache.remove(url)
                return null
            }
            if (now - hit.atMs > PLAYBACK_CACHE_TTL_MS) {
                playbackCache.remove(url)
                return null
            }
            return hit.value
        }
    }

    private fun rememberPlayback(url: String, value: ResolvedPlayback) {
        synchronized(playbackCacheLock) {
            if (playbackCache.size >= PLAYBACK_CACHE_MAX) {
                val oldest = playbackCache.minByOrNull { it.value.atMs }?.key
                if (oldest != null) playbackCache.remove(oldest)
            }
            playbackCache[url] = PlaybackCacheEntry(value, Clock.System.now().toEpochMilliseconds())
        }
    }

    /**
     * Chase Location hops without reading a body.
     * @return final URL after redirects, or null when HEAD is unsupported (caller tries Range).
     */
    private suspend fun followRedirectChain(
        url: String,
        authorization: String,
        useHead: Boolean,
    ): Probe? {
        var current = url
        var sawUnsupportedHead = false
        for (hop in 0 until PLAYBACK_MAX_REDIRECTS) {
            val withCredentials = sameOrigin(current, url)
            val outcome = try {
                http.prepareRequest(current) {
                    method = if (useHead) HttpMethod.Head else HttpMethod.Get
                    timeout {
                        requestTimeoutMillis = PROBE_REQUEST_TIMEOUT_MS
                        connectTimeoutMillis = PROBE_CONNECT_TIMEOUT_MS
                        socketTimeoutMillis = PROBE_REQUEST_TIMEOUT_MS
                    }
                    if (withCredentials) header("Authorization", authorization)
                    header("User-Agent", BROWSER_UA)
                    header("Accept", "*/*")
                    if (!useHead) header("Range", "bytes=0-0")
                }.execute { response ->
                    val status = response.status.value
                    when {
                        status in 300..399 -> response.headers["Location"]?.let { "redir:$it" } ?: "stop"
                        useHead && (status == 405 || status == 501) -> "unsupported"
                        status in 200..299 -> "ok"
                        // SAMplay 2.0.3: the drive's own answer for the file (not the CDN's).
                        status == 404 && hop == 0 -> "missing"
                        else -> "stop"
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                "stop"
            }
            when {
                outcome == "unsupported" -> {
                    sawUnsupportedHead = true
                    break
                }
                outcome == "missing" -> return Probe(current, missing = true)
                outcome == "ok" || outcome == "stop" -> return Probe(current)
                outcome != null && outcome.startsWith("redir:") -> {
                    val location = outcome.removePrefix("redir:")
                    current = runCatching { URLBuilder(current).takeFrom(location).buildString() }
                        .getOrNull() ?: return Probe(current)
                }
                else -> return Probe(current)
            }
        }
        return if (sawUnsupportedHead) null else Probe(current)
    }

    private class Probe(val url: String, val missing: Boolean = false)

    private data class PlaybackCacheEntry(val value: ResolvedPlayback, val atMs: Long)

    private val playbackCacheLock = SynchronizedObject()
    private val playbackCache = linkedMapOf<String, PlaybackCacheEntry>()

    /** A small file's bytes (following the drive's redirect), or null when there is no such file. */
    suspend fun download(baseUrl: String, path: String, authorization: String, maxBytes: Long = MAX_DOWNLOAD_BYTES): ByteArray? {
        val target = resolvePlayback(resolve(baseUrl, path), authorization)
        return try {
            http.prepareRequest(target.url) {
                method = HttpMethod.Get
                if (target.needsAuthorization) header("Authorization", authorization)
            }.execute { response ->
                val status = response.status.value
                when {
                    status == 404 -> null
                    status in 200..299 -> {
                        if ((response.contentLength() ?: -1L) > maxBytes) throw ServerException(ServerFailure.FAILED, "Too large")
                        response.readRawBytes()
                    }
                    else -> {
                        throwForStatus(status)
                        null
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: ServerException) {
            throw error
        } catch (_: Exception) {
            throw ServerException(ServerFailure.UNREACHABLE)
        }
    }

    /** Uploads [bytes] as the file [path], creating or replacing it. */
    suspend fun put(baseUrl: String, path: String, authorization: String, bytes: ByteArray) {
        val status = try {
            http.prepareRequest(resolve(baseUrl, path)) {
                method = HttpMethod.Put
                header("Authorization", authorization)
                setBody(ByteArrayContent(bytes, ContentType.Application.OctetStream))
            }.execute { it.status.value }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw ServerException(ServerFailure.UNREACHABLE)
        }
        throwForStatus(status)
    }

    /** Creates the folder [path]; fine when it is already there. */
    suspend fun mkcol(baseUrl: String, path: String, authorization: String) {
        val folder = if (path.endsWith('/')) path else "$path/"
        val status = try {
            http.prepareRequest(resolve(baseUrl, folder)) {
                method = MKCOL
                header("Authorization", authorization)
            }.execute { it.status.value }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw ServerException(ServerFailure.UNREACHABLE)
        }
        // 405: the folder already exists.
        if (status == 405 || status in 300..399) return
        throwForStatus(status)
    }

    private fun sameOrigin(a: String, b: String): Boolean = runCatching {
        val ua = Url(a)
        val ub = Url(b)
        ua.protocol == ub.protocol && ua.host.equals(ub.host, ignoreCase = true) && ua.port == ub.port
    }.getOrDefault(false)

    private data class PropfindResponse(val status: Int, val body: String, val location: String?)

    private suspend fun execute(url: String, authorization: String, depth: Int): PropfindResponse = try {
        http.prepareRequest(url) {
            method = PROPFIND
            header("Authorization", authorization)
            header("Depth", depth.toString())
            header("Content-Type", "application/xml; charset=utf-8")
            header("Accept", "application/xml, text/xml")
            header("User-Agent", BROWSER_UA)
            setBody(PROPFIND_BODY)
        }.execute { response ->
            PropfindResponse(
                status = response.status.value,
                body = readBoundedResponseBody(response.bodyAsChannel(), response.contentLength()),
                location = response.headers["Location"],
            )
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        throw ServerException(ServerFailure.UNREACHABLE)
    }

    private fun throwForStatus(status: Int) {
        val failure = when {
            status == 207 || status in 200..299 -> return
            status == 401 -> ServerFailure.AUTH_REQUIRED
            status == 403 -> ServerFailure.FORBIDDEN
            status == 404 || status == 405 -> ServerFailure.NOT_FOUND
            status in 500..599 -> ServerFailure.UNREACHABLE
            else -> ServerFailure.FAILED
        }
        throw ServerException(failure, "HTTP $status")
    }

    companion object {
        private val PROPFIND = HttpMethod("PROPFIND")
        private val MKCOL = HttpMethod("MKCOL")
        private const val MAX_REDIRECTS = 8
        /** Playback probe: stock Nuvio used 4; keep short so a miss falls back to DAV+Auth fast. */
        private const val PLAYBACK_MAX_REDIRECTS = 4
        private const val PROBE_BUDGET_MS = 4_000L
        private const val PROBE_REQUEST_TIMEOUT_MS = 3_000L
        private const val PROBE_CONNECT_TIMEOUT_MS = 2_000L
        /** [signedLink]: nobody is waiting on a player, so PikPak gets more time. */
        private const val LINK_REQUEST_TIMEOUT_MS = 10_000L
        private const val LINK_CONNECT_TIMEOUT_MS = 6_000L
        private const val PLAYBACK_CACHE_TTL_MS = 90_000L
        private const val PLAYBACK_CACHE_MAX = 32
        private const val MAX_DOWNLOAD_BYTES = 8L * 1024 * 1024
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:resourcetype/><d:getcontentlength/><d:getcontenttype/><d:getlastmodified/></d:prop></d:propfind>"""

        fun basicAuthorization(username: String, password: String): String =
            "Basic " + Base64.Default.encode("$username:$password".encodeToByteArray())

        /**
         * Absolute URL of [path] on the server at [baseUrl].
         * Decodes first (so an already-percent-encoded path is not double-encoded by
         * `encodeURLPath`), then builds with path segments so each piece is encoded once —
         * same behaviour as OkHttp `addPathSegment`, which PikPak Arabic folders need.
         */
        fun resolve(baseUrl: String, path: String): String {
            val base = Url(baseUrl)
            val decoded = runCatching {
                path.replace("+", "%2B").decodeURLPart()
            }.getOrDefault(path).ifBlank { "/" }
            return URLBuilder(base).apply {
                parameters.clear()
                fragment = ""
                if (decoded == "/") {
                    encodedPath = "/"
                } else {
                    val segments = decoded.split('/').filter { it.isNotEmpty() }
                    encodedPath = segments.joinToString("/", prefix = "/") { encodeSegment(it) } +
                        if (decoded.endsWith('/')) "/" else ""
                }
            }.buildString()
        }

    /**
     * One name of a path with everything but letters, digits and "-._~" as %XX (from SAMplay 1.8.8).
     * A URL may carry "+", "&", "[", "'"… as they are, but a drive that reads "+" in a path as a
     * space then looks for another name and answers 404: files such as "Show.S01E01.DD+5.1.mkv" or
     * "….HDR10+.mkv" did not open on PikPak. %2B means "+" to every server.
     */
        private val forgottenLock = SynchronizedObject()
        private val forgottenLinks = ArrayDeque<String>()

        /** A signed link the player could not open: no client serves it from its cache again. */
        fun forgetPlaybackLink(link: String) {
            if (link.isBlank()) return
            synchronized(forgottenLock) {
                if (link in forgottenLinks) return
                forgottenLinks.addLast(link)
                while (forgottenLinks.size > 32) forgottenLinks.removeFirst()
            }
        }

        internal fun isForgotten(link: String): Boolean = synchronized(forgottenLock) { link in forgottenLinks }

        /**
         * SAMplay 2.0.3: [link] with the name of the file at [path] after '#' (19 Nov 2026).
         * PikPak's signed links end in "/download/?…" and send no name, so the user's download
         * manager (SMD) saved every copied link as "download". The part after '#' never reaches
         * the server, so the link still works (PikPak answers a changed path with 404 and an added
         * parameter with 403); SMD 1.1.8 names the file from it, other apps ignore it.
         */
        fun withFileName(link: String, path: String): String {
            if ('#' in link) return link
            val decoded = runCatching { path.replace("+", "%2B").decodeURLPart() }.getOrDefault(path)
            val name = decoded.trimEnd('/').substringAfterLast('/')
            if (name.isBlank()) return link
            return link + "#" + encodeSegment(name)
        }

        internal fun encodeSegment(name: String): String = buildString(name.length + 16) {
            for (byte in name.encodeToByteArray()) {
                val c = byte.toInt() and 0xFF
                if (c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code ||
                    c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code
                ) {
                    append(c.toChar())
                } else {
                    append('%').append(SEGMENT_HEX[c shr 4]).append(SEGMENT_HEX[c and 0x0F])
                }
            }
        }

        private const val SEGMENT_HEX = "0123456789ABCDEF"

        /** "dav.pikpak.ai", "http://nas:5005/dav" ... → "https://dav.pikpak.ai", "http://nas:5005/dav". */
        fun normalizeAddress(input: String): String? {
            var value = input.trim().trimEnd('/')
            if (value.isEmpty()) return null
            if (!value.contains("://")) value = "https://$value"
            val url = runCatching { Url(value) }.getOrNull() ?: return null
            if (url.protocol != URLProtocol.HTTP && url.protocol != URLProtocol.HTTPS) return null
            if (url.host.isBlank() || !url.user.isNullOrEmpty() || !url.password.isNullOrEmpty()) return null
            return URLBuilder(url).apply {
                parameters.clear()
                fragment = ""
            }.buildString().trimEnd('/')
        }

        /**
         * The folders above [path] that are inside [rootPath], from the top down:
         * "/a/b/c/" under "/" → "/", "/a/", "/a/b/" (a file's folder is the last one).
         */
        fun parentFolders(path: String, rootPath: String = "/"): List<String> {
            val names = path.trim('/').split('/').filter { it.isNotEmpty() }
            val root = rootPath.trim('/').let { if (it.isEmpty()) "/" else "/$it/" }
            return (0 until names.size)
                .map { count -> "/" + names.take(count).joinToString("") { "$it/" } }
                .filter { it.startsWith(root) }
        }

        /** The server path a base URL points at ("/" for a bare host). */
        fun rootPath(baseUrl: String): String {
            val path = runCatching { Url(baseUrl).encodedPath }.getOrDefault("").decodeURLPart()
            return if (path.isBlank()) "/" else path.trimEnd('/') + "/"
        }

        /**
         * @param requestPath the folder path we PROPFIND'd (decoded). Relative `<href>` values
         * (no leading slash) are resolved against it — some DAV servers, including PikPak on
         * nested Arabic folders, return child names only; treating those as host-root paths made
         * the next catalog page PROPFIND the wrong place and show an empty list.
         */
        internal fun parseMultistatus(xml: String, requestPath: String = "/"): List<WebDavEntry> =
            RESPONSE.findAll(xml).mapNotNull { match ->
                val block = match.groupValues[1]
                val href = tag(block, "href")?.trim()?.let { hrefPath(it, requestPath) } ?: return@mapNotNull null
                val isFolder = COLLECTION.containsMatchIn(block) || href.endsWith('/')
                val path = if (isFolder && !href.endsWith('/')) "$href/" else href
                val display = tag(block, "displayname")?.let(::unescapeXml)?.trim().orEmpty()
                val name = display.ifBlank { path.trimEnd('/').substringAfterLast('/') }
                WebDavEntry(
                    path = path,
                    name = name,
                    isFolder = isFolder,
                    size = tag(block, "getcontentlength")?.trim()?.toLongOrNull(),
                    contentType = tag(block, "getcontenttype")?.trim()?.takeIf { it.isNotEmpty() },
                    lastModified = tag(block, "getlastmodified")?.trim()?.takeIf { it.isNotEmpty() },
                )
            }.toList()

        private fun hrefPath(href: String, requestPath: String): String {
            val raw = unescapeXml(href).trim()
            val joined = when {
                raw.contains("://") -> runCatching { Url(raw).encodedPath }.getOrDefault(raw)
                raw.startsWith("/") -> raw
                else -> {
                    val base = if (requestPath.endsWith('/')) requestPath else "$requestPath/"
                    // "file.mkv" or "./file.mkv" under the folder we asked for
                    base + raw.removePrefix("./")
                }
            }
            return runCatching { joined.decodeURLPart() }.getOrDefault(joined).ifEmpty { "/" }
        }

        private fun tag(block: String, name: String): String? =
            Regex("<(?:[A-Za-z0-9_-]+:)?$name\\b[^>]*?(?:/>|>(.*?)</(?:[A-Za-z0-9_-]+:)?$name\\s*>)", RegexOption.DOT_MATCHES_ALL)
                .find(block)
                ?.groupValues
                ?.getOrNull(1)

        private fun unescapeXml(value: String): String = value
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&amp;", "&")

        private val RESPONSE = Regex(
            "<(?:[A-Za-z0-9_-]+:)?response\\b[^>]*>(.*?)</(?:[A-Za-z0-9_-]+:)?response\\s*>",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val COLLECTION = Regex("<(?:[A-Za-z0-9_-]+:)?collection\\b")
    }
}
