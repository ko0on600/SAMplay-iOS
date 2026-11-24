package com.nuvio.app.features.servers.webdav

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.createApiHttpClient
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.servers.ServerCandidate
import com.nuvio.app.features.servers.ServerCapability
import com.nuvio.app.features.servers.ServerException
import com.nuvio.app.features.servers.ServerFailure
import com.nuvio.app.features.servers.ServerItemDetails
import com.nuvio.app.features.servers.ServerItemRef
import com.nuvio.app.features.servers.ServerLibrary
import com.nuvio.app.features.servers.ServerMediaKind
import com.nuvio.app.features.servers.ServerPage
import com.nuvio.app.features.servers.ServerPlayMethod
import com.nuvio.app.features.servers.ServerPlayerCapabilities
import com.nuvio.app.features.servers.ServerPlaybackRequest
import com.nuvio.app.features.servers.ServerPlaybackSession
import com.nuvio.app.features.servers.ServerPlaybackTarget
import com.nuvio.app.features.servers.ServerProvider
import com.nuvio.app.features.servers.ServerSession
import com.nuvio.app.features.servers.warmFirstBytes
import com.nuvio.app.features.servers.warmPlaybackConnections
import com.nuvio.app.features.servers.ServerSignIn
import com.nuvio.app.features.servers.ServerTitle
import com.nuvio.app.features.streams.StreamSubtitle
import com.nuvio.app.features.tracking.TrackingExternalIds
import io.ktor.client.HttpClient
import io.ktor.http.Url
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock

/**
 * WebDAV drives as a Nuvio RS media server: PikPak (dav.pikpak.ai, legacy dav.mypikpak.com),
 * Nextcloud/ownCloud, Synology, Alist, rclone and any other standard WebDAV server.
 *
 * Folders are browsed as libraries and titles, video files play directly with the
 * drive's Basic credentials, and files are matched to catalog titles by their names
 * (see [WebDavProvider.matchByName]) so a drive's copy of a film shows up as a stream.
 * The stored session token is the `Authorization` header value.
 */
internal class WebDavProvider(
    http: HttpClient = createApiHttpClient(),
) : ServerProvider {
    override val id: String = ID
    override val displayName: String = "WebDAV"
    override val minimumVersion: String = "1"
    override val capabilities: Set<ServerCapability> = setOf(ServerCapability.SEARCH)

    private val client = WebDavClient(http)
    private val listLog = Logger.withTag("WebDavList")
    private val pikpakApi = PikPakApiClient(http)
    private val lock = SynchronizedObject()
    private val listings = mutableMapOf<String, CachedListing>()
    private val indexes = mutableMapOf<String, CachedIndex>()
    /** Short-lived CDN URLs for PikPak media variants keyed by mediaSourceId. */
    private val mediaUrlCache = mutableMapOf<String, CachedMediaUrl>()

    override suspend fun signIn(address: String, username: String, password: String): ServerSignIn {
        val baseUrl = WebDavClient.normalizeAddress(address) ?: throw ServerException(ServerFailure.NOT_FOUND)
        val authorization = WebDavClient.basicAuthorization(username, password)
        // Depth 0 on the root proves the address is WebDAV and the credentials work.
        client.propfind(baseUrl, WebDavClient.rootPath(baseUrl), authorization, depth = 0)
        val host = Url(baseUrl).host
        return ServerSignIn(
            address = baseUrl,
            serverName = if (host.contains("pikpak", ignoreCase = true)) "PikPak" else host,
            serverId = baseUrl.lowercase(),
            userId = username,
            userName = username,
            token = authorization,
        )
    }

    /** The drive itself, browsed from its root exactly like the PikPak app's file list. */
    override suspend fun libraries(session: ServerSession): List<ServerLibrary> {
        val root = WebDavClient.rootPath(session.connection.address)
        listPatiently(session, root)
        // Nuvio RS: poster/details edits made in the TV app, shared through the drive.
        withTimeoutOrNull(DRIVE_SYNC_BUDGET_MS) { WebDavDriveSync.pull(session, force = true) }
        return listOf(ServerLibrary(id = root, name = ROOT_LIBRARY_NAME, kind = ServerMediaKind.MIXED))
    }

    override suspend fun libraryPage(
        session: ServerSession,
        library: ServerLibrary,
        start: Int,
        limit: Int,
    ): ServerPage<ServerTitle> = folderPage(session, library.id, start, limit)

    /** A folder opened from a library or another folder: its own sub-folders and videos. */
    override suspend fun collectionPage(
        session: ServerSession,
        collectionId: String,
        start: Int,
        limit: Int,
    ): ServerPage<ServerTitle> = folderPage(session, collectionId, start, limit)

    /**
     * One folder level, as PikPak lists it: sub-folders first, then the videos, in natural name
     * order so "E2" comes before "E10". A folder holding a show's episodes (or its season folders)
     * becomes a series tile that opens the show with its episode list, so the player can go on to
     * the next episode; other folders open their own page. Tiles get the catalogue's poster.
     */
    private suspend fun folderPage(session: ServerSession, folder: String, start: Int, limit: Int): ServerPage<ServerTitle> {
        if (start == 0) {
            // Opening a folder: drop any stale listing so a prior empty shapeOf() probe cannot stick.
            val path = if (folder.endsWith('/')) folder else "$folder/"
            synchronized(lock) { listings.remove("${session.connection.id}|$path") }
        }
        // The shared edits are read while the folder lists, not before it; tiles wait for both.
        val listed = coroutineScope {
            val sync = if (start == 0) async { withTimeoutOrNull(DRIVE_SYNC_BUDGET_MS) { WebDavDriveSync.pull(session) } } else null
            listPatiently(session, folder).also { sync?.await() }
        }
        val entries = listed
            .filter { it.isFolder || it.looksLikeVideo() }
            .sortedWith(compareBy<WebDavEntry> { !it.isFolder }.thenComparator { a, b -> WebDavNames.naturalCompare(a.name, b.name) })
        val page = entries.drop(start).take(limit)
        val deadline = nowMs() + TILE_BUDGET_MS
        val permits = Semaphore(TILE_PARALLEL_REQUESTS)
        val titles = coroutineScope {
            page.map { entry ->
                async {
                    permits.withPermit {
                        val remaining = deadline - nowMs()
                        val rich = if (remaining <= 0) {
                            null
                        } else {
                            runCatching { withTimeoutOrNull(remaining) { richTitle(session, entry, folder) } }
                                .onFailure { if (it is CancellationException) throw it }
                                .getOrNull()
                        }
                        rich ?: title(session, entry)
                    }
                }
            }.awaitAll()
        }
        return ServerPage(titles, entries.size)
    }

    override suspend fun search(
        session: ServerSession,
        kind: ServerMediaKind,
        libraries: List<ServerLibrary>,
        query: String,
        limit: Int,
    ): List<ServerTitle> {
        val wanted = WebDavNames.normalize(query)
        if (wanted.isEmpty()) return emptyList()
        return index(session, libraries)
            .filter { it.looksLikeVideo() }
            .filter {
                WebDavNames.normalize(it.name).contains(wanted) ||
                    WebDavNames.normalize(it.fileName).contains(wanted)
            }
            .filter { entry ->
                val parsed = WebDavNames.parse(entry.fileName)
                when (kind) {
                    ServerMediaKind.SERIES -> parsed.episode != null
                    ServerMediaKind.MOVIE -> parsed.episode == null
                    else -> true
                }
            }
            .take(limit)
            .map { title(session, it) }
    }

    override suspend fun details(session: ServerSession, itemId: String): ServerItemDetails {
        val parsed = WebDavNames.parse(displayName(itemId))
        if (!itemId.endsWith('/')) {
            val entry = runCatching { entry(session, itemId) }.getOrNull()
            val manual = WebDavInfoOverrides.get(session.connection.id, itemId)
            // The user's IMDb id wins; else a loose episode file shows its show's artwork and
            // anything else is looked up as a film.
            val linked = manual?.imdbId?.let { id ->
                lookup { WebDavMetadata.details(id, "movie") ?: WebDavMetadata.details(id, "series") }
            }
            val catalog = linked ?: lookup { WebDavMetadata.find(displayName(itemId), series = parsed.episode != null) }
            val location = listOfNotNull(
                displayName(itemId),
                WebDavNames.sizeLabel(entry?.size),
                itemId.substringBeforeLast('/').ifBlank { "/" },
            ).joinToString("\n")
            val isFilm = catalog != null && (parsed.episode == null || linked != null)
            val imdbId = manual?.imdbId ?: catalog?.imdbId?.takeIf { isFilm }
            return ServerItemDetails(
                meta = MetaDetails(
                    id = ref(session, itemId),
                    type = ServerMediaKind.MOVIE.contentType,
                    name = manual?.name ?: catalog?.name?.takeIf { isFilm && it.isNotBlank() } ?: WebDavNames.baseName(displayName(itemId)),
                    imdbId = imdbId,
                    poster = manual?.poster ?: catalog?.poster,
                    background = manual?.background ?: catalog?.background,
                    logo = catalog?.logo?.takeIf { isFilm && manual?.name == null },
                    releaseInfo = manual?.year ?: catalog?.releaseInfo?.takeIf { isFilm } ?: parsed.year?.toString(),
                    genres = manual?.genres?.ifEmpty { null } ?: catalog?.genres.orEmpty().takeIf { isFilm }.orEmpty(),
                    description = listOfNotNull(manual?.description ?: catalog?.description?.takeIf { isFilm }, location)
                        .joinToString("\n\n"),
                ),
                externalIds = TrackingExternalIds(imdb = imdbId),
            )
        }
        val episodes = seriesEpisodes(session, itemId)
        val manual = WebDavInfoOverrides.get(session.connection.id, itemId)
        // Only the show the user picked: a folder's name is not looked up.
        val catalog = pickedShowId(session, itemId, manual)?.let { id -> lookup { WebDavMetadata.details(id, "series") } }
        val episodeInfo = catalog?.episodes.orEmpty().associateBy { it.season to it.episode }
        val videos = episodes.map { file ->
            val info = episodeInfo[file.season to file.episode]
            val fileLine = listOfNotNull(file.entry.name, WebDavNames.sizeLabel(file.entry.size)).joinToString(" • ")
            MetaVideo(
                id = ref(session, file.entry.path),
                title = info?.title ?: WebDavNames.baseName(file.entry.name),
                thumbnail = info?.thumbnail,
                season = file.season,
                episode = file.episode,
                overview = listOfNotNull(info?.overview, fileLine).joinToString("\n\n"),
            )
        }
        return ServerItemDetails(
            meta = MetaDetails(
                id = ref(session, itemId),
                type = ServerMediaKind.SERIES.contentType,
                name = manual?.name ?: catalog?.name?.ifBlank { null } ?: parsed.title,
                imdbId = manual?.imdbId ?: catalog?.imdbId,
                poster = manual?.poster ?: catalog?.poster,
                background = manual?.background ?: catalog?.background,
                logo = catalog?.logo?.takeIf { manual?.name == null },
                releaseInfo = manual?.year ?: catalog?.releaseInfo ?: parsed.year?.toString(),
                genres = manual?.genres?.ifEmpty { null } ?: catalog?.genres.orEmpty(),
                description = listOfNotNull(manual?.description ?: catalog?.description, itemId).joinToString("\n\n"),
                videos = videos,
            ),
            externalIds = TrackingExternalIds(imdb = manual?.imdbId ?: catalog?.imdbId),
        )
    }

    override suspend fun candidates(session: ServerSession, itemId: String): List<ServerCandidate> {
        if (itemId.endsWith('/')) throw ServerException(ServerFailure.NOT_FOUND)
        val entry = entry(session, itemId)
        val original = candidate(session, entry)
        if (!PikPakApiClient.isPikPakAddress(session.connection.address)) {
            return listOf(original)
        }
        val variants = runCatching { pikpakQualityCandidates(session, entry) }.getOrElse { emptyList() }
        if (variants.isEmpty()) return listOf(original)
        // Always keep Original first/default, then every other API variant (4K/1080/720/480/…).
        val rest = variants.filterNot { it.title.equals("Original", ignoreCase = true) }
        return listOf(original.copy(title = "Original", description = listOfNotNull(
            "Original",
            WebDavNames.sizeLabel(entry.size),
            WebDavNames.extension(entry.fileName).uppercase().takeIf { it.isNotBlank() },
        ).joinToString(" • "))) + rest
    }

    // Its folder listing and signed link stay cached, and the link's connections stay open.
    // SAMplay 2.0.3: then its first bytes are asked for, which is what makes PikPak fetch a file
    // it has not served lately (20 to 45 s on the phone) while the links are still on screen.
    override suspend fun warmPlayback(session: ServerSession, request: ServerPlaybackRequest) {
        val playback = preparePlayback(session, request)
        warmPlaybackConnections(playback.url, playback.headers)
        warmFirstBytes(playback.url, playback.headers)
    }

    override suspend fun preparePlayback(session: ServerSession, request: ServerPlaybackRequest): ServerPlaybackSession {
        val path = request.target.item.itemId
        val baseUrl = session.connection.address
        val mediaSourceId = request.target.mediaSourceId ?: path
        val parent = path.substringBeforeLast('/') + "/"
        val stem = WebDavNames.baseName(displayName(path)).lowercase()
        return coroutineScope {
            val subtitlesDeferred = async {
                runCatching { list(session, parent) }.getOrDefault(emptyList())
                    .filter { !it.isFolder && WebDavNames.isSubtitle(it.fileName) && it.fileName.lowercase().startsWith(stem) }
                    .map { subtitle ->
                        val language = WebDavNames.baseName(subtitle.fileName).drop(stem.length).trim('.', '_', '-', ' ')
                        StreamSubtitle(
                            url = WebDavClient.resolve(baseUrl, subtitle.path),
                            language = language.ifBlank { "und" },
                            name = subtitle.name.ifBlank { subtitle.fileName },
                            headers = mapOf("Authorization" to session.token),
                        )
                    }
            }
            val mediaUrl = if (mediaSourceId.startsWith(PIKPAK_MEDIA_PREFIX)) {
                resolvePikPakMediaUrl(session, mediaSourceId)
            } else {
                null
            }
            val resolvedDeferred = async {
                if (mediaUrl != null) {
                    // Transcode / media CDN links are already signed — no DAV Authorization.
                    ResolvedPlayback(mediaUrl, needsAuthorization = false)
                } else {
                    val davUrl = WebDavClient.resolve(baseUrl, path)
                    client.cachedPlayback(davUrl)
                        ?: withTimeoutOrNull(2_500L) { client.resolvePlayback(davUrl, session.token) }?.let { first ->
                            if (!first.missing) return@let first
                            // SAMplay 2.0.3: PikPak lost the file's folder (WebDavClient.revisitParents).
                            val listed = client.revisitParents(baseUrl, path, session.token)
                            listLog.i { "$path: not found, listed $listed folders above it again" }
                            withTimeoutOrNull(2_500L) { client.resolvePlayback(davUrl, session.token) } ?: first
                        }
                        ?: ResolvedPlayback(davUrl, needsAuthorization = true)
                }
            }
            val resolved = resolvedDeferred.await()
            // Nuvio RS (2.0.3): a slow folder listing (PikPak can take many seconds) no longer holds
            // the video back; its subtitle files are left out when the listing is that slow.
            val subtitles = withTimeoutOrNull(SIDECAR_WAIT_MS) { subtitlesDeferred.await() }
                ?: emptyList<StreamSubtitle>().also { subtitlesDeferred.cancel() }
            ServerPlaybackSession(
                target = request.target,
                mediaSourceId = mediaSourceId,
                url = resolved.url,
                headers = if (resolved.needsAuthorization) mapOf("Authorization" to session.token) else emptyMap(),
                subtitles = subtitles,
                playSessionId = null,
                playMethod = ServerPlayMethod.DIRECT_PLAY,
            )
        }
    }

    /**
     * Nuvio RS (2.0.3): a download asks the drive's own address each time it starts or resumes, so
     * a signed CDN link that expired during a long download is never used again. OkHttp drops the
     * Authorization header when the drive redirects to another host (PikPak's CDN).
     */
    override suspend fun prepareDownload(session: ServerSession, target: ServerPlaybackTarget): ServerPlaybackSession {
        val playback = preparePlayback(session, ServerPlaybackRequest(target, ServerPlayerCapabilities(directPlayAll = true)))
        val mediaSourceId = target.mediaSourceId ?: target.item.itemId
        // PikPak's own transcodes are signed links with no drive address behind them.
        if (mediaSourceId.startsWith(PIKPAK_MEDIA_PREFIX)) return playback
        return ServerPlaybackSession(
            target = target,
            mediaSourceId = mediaSourceId,
            url = WebDavClient.resolve(session.connection.address, target.item.itemId),
            headers = mapOf("Authorization" to session.token),
            subtitles = playback.subtitles,
            playSessionId = null,
            playMethod = ServerPlayMethod.DIRECT_PLAY,
        )
    }

    /**
     * SAMplay 2.0.3: "Copy stream link" on a PikPak file said "No direct stream link available"
     * (the user's screen recording, 19 Nov 2026: copying it for their download manager, SMD). The
     * drive's own address needs the login, so it was never copied. PikPak sends that address on to
     * a signed CDN link that needs no login for a while; that link is copied now. PikPak sometimes
     * answers 404 for a moment (see [FolderListRetry]), so a PikPak drive is asked a few times.
     * Another drive that does not send its files elsewhere still has no such link.
     */
    override suspend fun prepareLink(session: ServerSession, target: ServerPlaybackTarget): ServerPlaybackSession {
        val path = target.item.itemId
        val mediaSourceId = target.mediaSourceId ?: path
        val davUrl = WebDavClient.resolve(session.connection.address, path)
        val signed = if (mediaSourceId.startsWith(PIKPAK_MEDIA_PREFIX)) resolvePikPakMediaUrl(session, mediaSourceId) else null
        val resolved = signed?.let { ResolvedPlayback(it, needsAuthorization = false) } ?: run {
            val waits = if (PikPakApiClient.isPikPakAddress(session.connection.address)) LINK_RETRY_WAITS_MS else longArrayOf()
            // The answers' statuses only (a signed link is a credential), one try per log line.
            val answers = mutableListOf<Int>()
            fun logAnswers() {
                listLog.i { "$path: link answers ${answers.joinToString(" > ")}" }
                answers.clear()
            }
            var attempt = client.cachedPlayback(davUrl)
                ?: client.signedLink(davUrl, session.token) { answers += it }.also { logAnswers() }
            for (wait in waits) {
                if (!attempt.needsAuthorization) break
                delay(wait)
                // PikPak lost the file's folder: the folders above it are listed again first.
                if (attempt.missing) client.revisitParents(session.connection.address, path, session.token)
                attempt = client.signedLink(davUrl, session.token) { answers += it }
                logAnswers()
            }
            attempt
        }
        if (resolved.needsAuthorization) listLog.w { "$path: no link without the login" }
        return ServerPlaybackSession(
            target = target,
            mediaSourceId = mediaSourceId,
            // SAMplay 2.0.3: the copied link carries the file's name for the download manager.
            url = if (resolved.needsAuthorization) resolved.url else WebDavClient.withFileName(resolved.url, path),
            headers = if (resolved.needsAuthorization) mapOf("Authorization" to session.token) else emptyMap(),
            subtitles = emptyList(),
            playSessionId = null,
            playMethod = ServerPlayMethod.DIRECT_PLAY,
        )
    }

    /**
     * Files on the drive whose names match a catalog title: a film by title (and year
     * when both sides have one), an episode by title plus season/episode, taking the
     * show title and season from parent folders when the file name lacks them.
     */
    suspend fun matchByName(
        session: ServerSession,
        title: String,
        year: Int?,
        season: Int?,
        episode: Int?,
    ): List<ServerCandidate> {
        if (title.isBlank()) return emptyList()
        val roots = session.connection.selectedLibraries
        return index(session, roots)
            .asSequence()
            .filter { it.looksLikeVideo() }
            .filter { entry -> nameMatches(entry, title, year, season, episode) }
            .take(MAX_MATCHES)
            .map { candidate(session, it) }
            .toList()
    }

    private fun nameMatches(entry: WebDavEntry, title: String, year: Int?, season: Int?, episode: Int?): Boolean {
        val file = WebDavNames.parse(entry.fileName)
        val folders = entry.path.trimEnd('/').split('/').dropLast(1).filter { it.isNotBlank() }.reversed()
        if (episode == null) {
            if (file.episode != null) return false
            val titleOk = WebDavNames.titleMatches(file.title, title) ||
                folders.take(1).any { WebDavNames.titleMatches(WebDavNames.parse(it).title, title) }
            val fileYear = file.year ?: folders.firstNotNullOfOrNull { WebDavNames.parse(it).year }
            return titleOk && (year == null || fileYear == null || kotlin.math.abs(fileYear - year) <= 1)
        }
        if (file.episode != episode) return false
        val fileSeason = file.season ?: folders.firstNotNullOfOrNull { WebDavNames.seasonOfFolder(it) } ?: 1
        if (season != null && fileSeason != season) return false
        return WebDavNames.titleMatches(file.title, title) ||
            folders.take(3).any { WebDavNames.titleMatches(WebDavNames.parse(it).title, title) }
    }

    private fun candidate(session: ServerSession, entry: WebDavEntry): ServerCandidate = ServerCandidate(
        target = ServerPlaybackTarget(item = ServerItemRef(session.connection.id, entry.path), mediaSourceId = entry.path),
        title = entry.name.ifBlank { entry.fileName },
        description = listOfNotNull(
            WebDavNames.sizeLabel(entry.size),
            WebDavNames.extension(entry.fileName).uppercase().takeIf { it.isNotBlank() },
            entry.path.substringBeforeLast('/').ifBlank { "/" },
        ).joinToString(" • "),
        filename = entry.fileName,
        sizeBytes = entry.size,
    )

    /** A tile, with the poster and details the user set for it, if any. */
    private fun title(session: ServerSession, entry: WebDavEntry): ServerTitle {
        val plain = plainTitle(session, entry)
        val manual = WebDavInfoOverrides.get(session.connection.id, entry.path) ?: return plain
        return plain.asKind(entry, manual).withManual(manual)
    }

    /** A folder becomes a collection tile (it opens its own page); a video file a playable title. */
    private fun plainTitle(session: ServerSession, entry: WebDavEntry): ServerTitle {
        val parsed = WebDavNames.parse(entry.fileName)
        val kind = if (entry.isFolder) ServerMediaKind.COLLECTION else ServerMediaKind.MOVIE
        val videoLabel = entry.name.takeIf { it.isNotBlank() && WebDavNames.extension(it).isEmpty() }
            ?: WebDavNames.baseName(entry.fileName)
        return ServerTitle(
            preview = MetaPreview(
                id = ref(session, entry.path),
                type = kind.contentType,
                name = if (entry.isFolder) entry.name.ifBlank { entry.fileName } else videoLabel,
                posterShape = if (entry.isFolder) PosterShape.Square else PosterShape.Landscape,
                description = listOfNotNull(
                    entry.fileName.takeIf { !entry.isFolder },
                    WebDavNames.sizeLabel(entry.size),
                ).joinToString(" • ").ifBlank { null },
                releaseInfo = parsed.year?.toString(),
            ),
        )
    }

    /**
     * A tile with the catalogue's poster: a show's folder (episodes or season folders) becomes a
     * series, a folder holding one film shows that film's poster, a video file its own.
     */
    private suspend fun richTitle(session: ServerSession, entry: WebDavEntry, parent: String): ServerTitle {
        val manual = WebDavInfoOverrides.get(session.connection.id, entry.path)
        // A section the user named: its name and picture, opened as a folder, nothing looked up.
        if (manual?.isFolder == true) return plainTitle(session, entry).withManual(manual)
        val auto = autoTitle(session, entry, parent, lookUp = manual?.poster == null && manual?.imdbId == null)
            .asKind(entry, manual)
        if (manual == null) return auto
        // An IMDb id the user gave without a poster of their own brings the catalogue's artwork.
        val linked = if (manual.poster == null && manual.imdbId != null) {
            lookup {
                val series = auto.preview.type == ServerMediaKind.SERIES.contentType
                WebDavMetadata.details(manual.imdbId, if (series) "series" else "movie")
            }
        } else {
            null
        }
        return (if (linked != null) auto.withArtwork(linked, keepName = false) else auto).withManual(manual)
    }

    private fun ServerTitle.withManual(manual: WebDavInfoOverride): ServerTitle {
        val poster = manual.poster ?: preview.poster
        return copy(
            preview = preview.copy(
                name = manual.name?.ifBlank { null } ?: preview.name,
                poster = poster,
                banner = manual.background ?: preview.banner,
                posterShape = if (poster != null) PosterShape.Poster else preview.posterShape,
                releaseInfo = manual.year ?: preview.releaseInfo,
                genres = manual.genres.ifEmpty { preview.genres },
                description = manual.description ?: preview.description,
            ),
        )
    }

    /** A folder the user called a series opens as one; one they called a film stays a folder. */
    private fun ServerTitle.asKind(entry: WebDavEntry, manual: WebDavInfoOverride?): ServerTitle {
        if (!entry.isFolder) return this
        val type = when (manual?.type) {
            "series" -> ServerMediaKind.SERIES.contentType
            "movie" -> ServerMediaKind.COLLECTION.contentType
            else -> return this
        }
        return copy(preview = preview.copy(type = type))
    }

    /**
     * The tile as the drive's layout describes it. Folders are never looked up by name (a folder
     * called "Sam" is not the 1973 film): the user picks a film or series for one in the editor.
     * Video files are looked up when [lookUp].
     */
    private suspend fun autoTitle(session: ServerSession, entry: WebDavEntry, parent: String, lookUp: Boolean): ServerTitle {
        val plain = plainTitle(session, entry)
        if (!entry.isFolder) {
            if (!lookUp) return plain
            val parsed = WebDavNames.parse(entry.fileName)
            val catalog = lookup {
                WebDavMetadata.find(entry.fileName, series = parsed.episode != null)
                    ?: entry.name.takeIf { it.isNotBlank() && it != entry.fileName }?.let {
                        WebDavMetadata.find(it, series = parsed.episode != null)
                    }
            } ?: return plain
            return plain.withArtwork(catalog, keepName = parsed.episode != null)
        }
        return when (shapeOf(session, entry.path)) {
            FolderShape.EPISODES, FolderShape.SEASONS -> {
                // A "Season N" folder takes the show the user set on the folder above.
                val show = if (WebDavNames.isSeasonLabel(entry.name)) {
                    WebDavInfoOverrides.get(session.connection.id, parent)
                        ?.takeIf { !it.isFolder && it.imdbId != null && it.type != "movie" }
                } else {
                    null
                }
                val catalog = if (lookUp && show != null) lookup { WebDavMetadata.details(show.imdbId!!, "series") } else null
                val series = plain.copy(
                    preview = plain.preview.copy(type = ServerMediaKind.SERIES.contentType),
                )
                if (catalog == null) {
                    series
                } else {
                    series.withArtwork(catalog, keepName = false).let {
                        it.copy(preview = it.preview.copy(name = "${show?.name ?: catalog.name} · ${entry.name}"))
                    }
                }
            }
            FolderShape.FILM -> {
                // The film inside may carry the user's poster.
                val video = list(session, entry.path).firstOrNull { it.looksLikeVideo() }
                video?.let { WebDavInfoOverrides.get(session.connection.id, it.path) }?.let { return plain.withManual(it) }
                plain
            }
            FolderShape.PLAIN -> plain
        }
    }

    private fun ServerTitle.withArtwork(catalog: WebDavTitleMeta, keepName: Boolean): ServerTitle = copy(
        preview = preview.copy(
            name = if (keepName || catalog.name.isBlank()) preview.name else catalog.name,
            poster = catalog.poster,
            banner = catalog.background,
            posterShape = PosterShape.Poster,
            releaseInfo = catalog.releaseInfo ?: preview.releaseInfo,
            genres = catalog.genres,
        ),
    )

    /** What a folder holds, judged from its own listing (cached with the listing). */
    private suspend fun shapeOf(session: ServerSession, folder: String): FolderShape {
        val entries = list(session, folder)
        val videos = entries.filter { it.looksLikeVideo() }
        val folders = entries.filter { it.isFolder }
        val seasonFolders = folders.filter { WebDavNames.isSeasonLabel(it.name) }
        if (seasonFolders.isNotEmpty() && seasonFolders.size == folders.size && videos.size <= 2) return FolderShape.SEASONS
        val otherFolders = folders.size - seasonFolders.size
        val numbered = videos.count { WebDavNames.episodeNumber(it.name) != null }
        if (otherFolders <= 2 && videos.size >= 2 && numbered * 10 >= videos.size * 6) return FolderShape.EPISODES
        if (otherFolders == 0 && videos.isNotEmpty() && WebDavNames.isSeasonLabel(displayName(folder))) return FolderShape.EPISODES
        if (folders.isEmpty() && videos.size == 1) return FolderShape.FILM
        return FolderShape.PLAIN
    }

    /**
     * A show folder's episodes: its own videos, or each season folder's. Numbers come from the
     * file names (S01E02, 1x02, "Episode 2", "- 02", "02 …") and fall back to the natural name
     * order when names don't number every file uniquely.
     */
    private suspend fun seriesEpisodes(session: ServerSession, folder: String): List<EpisodeFile> {
        val entries = list(session, folder)
        val seasonFolders = entries.filter { it.isFolder && WebDavNames.isSeasonLabel(it.name) }
        if (shapeOf(session, folder) == FolderShape.SEASONS) {
            val permits = Semaphore(INDEX_PARALLEL_REQUESTS)
            val seasons = coroutineScope {
                seasonFolders.map { seasonFolder ->
                    async {
                        permits.withPermit {
                            val season = WebDavNames.seasonOfFolder(seasonFolder.name) ?: 1
                            val files = runCatching { list(session, seasonFolder.path) }.getOrDefault(emptyList())
                            numberEpisodes(files, season)
                        }
                    }
                }.awaitAll()
            }
            return seasons.flatten().sortedWith(compareBy<EpisodeFile> { it.season }.thenBy { it.episode })
        }
        val season = WebDavNames.seasonOfFolder(displayName(folder)) ?: 1
        return numberEpisodes(entries, season)
    }

    private fun numberEpisodes(entries: List<WebDavEntry>, defaultSeason: Int): List<EpisodeFile> {
        val videos = entries
            .filter { it.looksLikeVideo() }
            .sortedWith(Comparator { a, b -> WebDavNames.naturalCompare(a.name, b.name) })
        val named = videos.map { entry ->
            val episode = WebDavNames.episodeNumber(entry.fileName)
            val season = WebDavNames.parse(entry.fileName).season ?: defaultSeason
            episode?.let { EpisodeFile(entry, season, it) }
        }
        val usable = named.all { it != null } && named.map { it!!.season to it.episode }.toSet().size == named.size
        if (usable) return named.filterNotNull().sortedWith(compareBy<EpisodeFile> { it.season }.thenBy { it.episode })
        return videos.mapIndexed { index, entry -> EpisodeFile(entry, defaultSeason, index + 1) }
    }

    /** The show the user picked for [folder], or for the show folder above a bare "Season 2" folder. */
    private fun pickedShowId(session: ServerSession, folder: String, manual: WebDavInfoOverride?): String? {
        manual?.takeIf { !it.isFolder }?.imdbId?.let { return it }
        if (!WebDavNames.isSeasonLabel(displayName(folder))) return null
        val parent = folder.trimEnd('/').substringBeforeLast('/') + "/"
        return WebDavInfoOverrides.get(session.connection.id, parent)
            ?.takeIf { !it.isFolder && it.type != "movie" }?.imdbId
    }

    /** A catalogue lookup that never fails the page or the details it decorates. */
    private suspend fun <T> lookup(block: suspend () -> T?): T? =
        runCatching { block() }.onFailure { if (it is CancellationException) throw it }.getOrNull()

    private suspend fun entry(session: ServerSession, path: String): WebDavEntry {
        val parent = path.substringBeforeLast('/') + "/"
        list(session, parent).firstOrNull { it.path == path }?.let { return it }
        return client.propfind(session.connection.address, path, session.token, depth = 0).firstOrNull()
            ?: throw ServerException(ServerFailure.NOT_FOUND)
    }

    /**
     * SAMplay 2.0.3: [list] for a page the user opened, asked again while PikPak answers a folder
     * it has with 404 or an error ([FolderListRetry]). Every failed try is logged with the
     * drive's answer, so a folder that still fails can be told apart (404, 5xx, timeout).
     */
    private suspend fun listPatiently(session: ServerSession, folder: String): List<WebDavEntry> {
        val startedMs = nowMs()
        var failed = 0
        val entries = FolderListRetry.run(
            onFailure = { attempt, error ->
                failed = attempt
                listLog.w { "$folder: try $attempt failed after ${nowMs() - startedMs} ms: ${FolderListRetry.describe(error)}" }
            },
            beforeRetry = { error ->
                // PikPak lost the path (see FolderListRetry): the folders above it are listed again.
                if (error is ServerException && error.failure == ServerFailure.NOT_FOUND) {
                    val listed = client.revisitParents(session.connection.address, folder, session.token)
                    listLog.i { "$folder: listed $listed folders above it again" }
                }
            },
        ) { list(session, folder) }
        if (failed > 0) {
            listLog.i { "$folder: listed on try ${failed + 1} after ${nowMs() - startedMs} ms (${entries.size} entries)" }
        }
        return entries
    }

    /** Folder listing without the folder itself, cached briefly per connection. */
    private suspend fun list(session: ServerSession, folder: String): List<WebDavEntry> {
        val path = if (folder.endsWith('/')) folder else "$folder/"
        val key = "${session.connection.id}|$path"
        val now = nowMs()
        synchronized(lock) {
            listings[key]?.let { cached ->
                // Never reuse an empty answer: shapeOf() on parent tiles used to PROPFIND each
                // child and cache a miss, so opening that folder showed «no titles» until TTL.
                if (cached.entries.isNotEmpty() && now - cached.atMs < LISTING_TTL_MS) return cached.entries
            }
        }
        suspend fun children() = client.propfind(session.connection.address, path, session.token, depth = 1)
            .filterNot { it.path.trimEnd('/') == path.trimEnd('/') }
            // The apps' shared-info folder is not part of the drive's content.
            .filterNot { WebDavDriveSync.isSyncFolder(it) }
        // Nuvio RS: PikPak sometimes answers a folder that has files with an empty listing (the
        // misses noted above), and the folder page showed «no titles». An empty answer is asked
        // once more before it is believed.
        val entries = children().ifEmpty {
            delay(EMPTY_LISTING_RETRY_MS)
            children()
        }
        synchronized(lock) {
            if (listings.size > MAX_CACHED_LISTINGS) listings.clear()
            if (entries.isEmpty()) {
                listings.remove(key)
            } else {
                listings[key] = CachedListing(now, entries)
            }
        }
        return entries
    }

    /** Breadth-first walk of the selected libraries, bounded in depth and size. */
    private suspend fun index(session: ServerSession, libraries: List<ServerLibrary>): List<WebDavEntry> {
        val roots = libraries.map { it.id }.ifEmpty { listOf(WebDavClient.rootPath(session.connection.address)) }
        val key = "${session.connection.id}|${roots.sorted().joinToString(",")}"
        val now = nowMs()
        synchronized(lock) { indexes[key]?.takeIf { now - it.atMs < INDEX_TTL_MS }?.let { return it.entries } }
        val result = mutableListOf<WebDavEntry>()
        var level = roots.distinct()
        var depth = 0
        var complete = true
        val permits = Semaphore(INDEX_PARALLEL_REQUESTS)
        while (level.isNotEmpty() && depth <= MAX_INDEX_DEPTH && result.size < MAX_INDEX_ENTRIES) {
            if (nowMs() - now > INDEX_BUDGET_MS) {
                complete = false
                break
            }
            val listed = coroutineScope {
                level.map { folder ->
                    async { permits.withPermit { runCatching { list(session, folder) }.getOrDefault(emptyList()) } }
                }.awaitAll()
            }
            val next = mutableListOf<String>()
            for (entries in listed) {
                result += entries
                next += entries.filter { it.isFolder }.map { it.path }
            }
            level = next.take(MAX_FOLDERS_PER_LEVEL)
            depth++
        }
        // A walk cut short by the time budget is kept only briefly, so the next lookup retries it.
        synchronized(lock) { indexes[key] = CachedIndex(if (complete) now else now - INDEX_TTL_MS + 60_000L, result) }
        return result
    }

    private fun ref(session: ServerSession, path: String): String = ServerItemRef(session.connection.id, path).encode()

    private fun displayName(path: String): String = path.trimEnd('/').substringAfterLast('/')

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    private class CachedListing(val atMs: Long, val entries: List<WebDavEntry>)
    private class CachedIndex(val atMs: Long, val entries: List<WebDavEntry>)
    private class EpisodeFile(val entry: WebDavEntry, val season: Int, val episode: Int)

    private enum class FolderShape {
        /** A show's episodes, directly in the folder. */
        EPISODES,
        /** A show split into "Season N" folders. */
        SEASONS,
        /** One film, possibly with its subtitles. */
        FILM,
        PLAIN,
    }

    private suspend fun pikpakQualityCandidates(
        session: ServerSession,
        entry: WebDavEntry,
    ): List<ServerCandidate> {
        val apiSession = pikpakApiSession(session) ?: return emptyList()
        val detail = pikpakApi.fileDetailForPath(apiSession, entry.path)
        val itemRef = ServerItemRef(session.connection.id, entry.path)
        val playable = detail.medias
            .filter { it.url.isNotBlank() || it.isOrigin }
            .distinctBy { it.qualityLabel().uppercase() }
        // Sort: Original first (handled by caller), then higher resolutions first.
        val ordered = playable.sortedWith(
            compareByDescending<PikPakMedia> { if (it.isOrigin) 1 else 0 }
                .thenByDescending { it.height }
                .thenByDescending { it.qualityLabel() },
        )
        return ordered.mapNotNull { media ->
            val label = media.qualityLabel()
            if (media.isOrigin) {
                // Original is the WebDAV candidate the caller already adds.
                return@mapNotNull null
            }
            if (media.url.isBlank()) return@mapNotNull null
            val sourceId = "$PIKPAK_MEDIA_PREFIX${detail.id}:$label:${media.mediaId}"
            rememberMediaUrl(sourceId, media.url)
            ServerCandidate(
                target = ServerPlaybackTarget(item = itemRef, mediaSourceId = sourceId),
                title = label,
                description = listOfNotNull(
                    label,
                    media.mediaName.takeIf { it.isNotBlank() && !it.equals(label, true) },
                    when {
                        media.width > 0 && media.height > 0 -> "${media.width}×${media.height}"
                        else -> null
                    },
                ).joinToString(" • "),
                filename = entry.fileName,
                sizeBytes = null,
            )
        }
    }

    private suspend fun resolvePikPakMediaUrl(session: ServerSession, mediaSourceId: String): String? {
        cachedMediaUrl(mediaSourceId)?.let { return it }
        val apiSession = pikpakApiSession(session) ?: return null
        val body = mediaSourceId.removePrefix(PIKPAK_MEDIA_PREFIX)
        val parts = body.split(':', limit = 3)
        if (parts.size < 3) return null
        val fileId = parts[0]
        val label = parts[1]
        val mediaId = parts[2]
        val detail = pikpakApi.fileDetail(apiSession, fileId)
        val media = detail.medias.firstOrNull { it.mediaId == mediaId && it.url.isNotBlank() }
            ?: detail.medias.firstOrNull { it.qualityLabel().equals(label, ignoreCase = true) && it.url.isNotBlank() }
            ?: return null
        rememberMediaUrl(mediaSourceId, media.url)
        return media.url
    }

    private suspend fun pikpakApiSession(session: ServerSession): PikPakApiSession? {
        PikPakApiSessions.get(session.connection.id)?.let { stored ->
            return runCatching { pikpakApi.ensureSession(stored) }
                .onSuccess { PikPakApiSessions.putForConnection(session.connection.id, it) }
                .getOrNull()
        }
        // Try WebDAV username/password against the Drive API (works when they match account login).
        val basic = PikPakApiSessions.decodeBasic(session.token) ?: return null
        val (user, pass) = basic
        if (user.isBlank() || pass.isBlank()) return null
        return runCatching { pikpakApi.signIn(user, pass) }
            .onSuccess { PikPakApiSessions.putForConnection(session.connection.id, it) }
            .getOrNull()
    }

    private fun rememberMediaUrl(sourceId: String, url: String) {
        synchronized(lock) {
            if (mediaUrlCache.size >= 64) {
                val oldest = mediaUrlCache.minByOrNull { it.value.atMs }?.key
                if (oldest != null) mediaUrlCache.remove(oldest)
            }
            mediaUrlCache[sourceId] = CachedMediaUrl(url, Clock.System.now().toEpochMilliseconds())
        }
    }

    private fun cachedMediaUrl(sourceId: String): String? {
        synchronized(lock) {
            val hit = mediaUrlCache[sourceId] ?: return null
            // Nuvio RS (2.0.3): a PikPak transcode link the player could not open is asked for anew;
            // it used to come back from here for 90 s, so each retry and each reopen got the same
            // failing link.
            if (Clock.System.now().toEpochMilliseconds() - hit.atMs > MEDIA_URL_TTL_MS ||
                WebDavClient.isForgotten(hit.url)
            ) {
                mediaUrlCache.remove(sourceId)
                return null
            }
            return hit.url
        }
    }

    private data class CachedMediaUrl(val url: String, val atMs: Long)

    companion object {
        const val ID = "webdav"
        private const val PIKPAK_MEDIA_PREFIX = "pikpak-media:"
        private val LINK_RETRY_WAITS_MS = longArrayOf(1_000, 2_000)
        private const val MEDIA_URL_TTL_MS = 90_000L
        private const val SIDECAR_WAIT_MS = 3_000L
        private const val ROOT_LIBRARY_NAME = "Files"
        private const val DRIVE_SYNC_BUDGET_MS = 5_000L
        private const val LISTING_TTL_MS = 2 * 60_000L
        private const val EMPTY_LISTING_RETRY_MS = 700L
        /** Empty PROPFIND answers are not cached (see [list]). */
        private const val INDEX_TTL_MS = 10 * 60_000L
        private const val MAX_CACHED_LISTINGS = 400
        private const val MAX_INDEX_DEPTH = 4
        private const val MAX_INDEX_ENTRIES = 5_000
        private const val MAX_FOLDERS_PER_LEVEL = 200
        private const val INDEX_PARALLEL_REQUESTS = 6
        private const val INDEX_BUDGET_MS = 25_000L
        private const val MAX_MATCHES = 12
        private const val TILE_PARALLEL_REQUESTS = 8
        /** How long a folder page waits for posters before showing the rest as plain tiles. */
        private const val TILE_BUDGET_MS = 6_000L
    }
}
