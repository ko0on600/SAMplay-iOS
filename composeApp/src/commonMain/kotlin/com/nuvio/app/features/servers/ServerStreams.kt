package com.nuvio.app.features.servers

import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.servers.webdav.WebDavProvider
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.jetbrains.compose.resources.getString

internal class ServerStreamSource(
    val addonId: String,
    val addonName: String,
    val preferred: Boolean,
    private val loader: suspend () -> List<StreamItem>,
) {
    fun loadingGroup(): AddonStreamGroup =
        AddonStreamGroup(addonName = addonName, addonId = addonId, streams = emptyList(), isLoading = true)

    suspend fun load(): AddonStreamGroup = try {
        val streams = loader()
        streams.firstOrNull()?.let(ServerPlayback::warm)
        AddonStreamGroup(addonName = addonName, addonId = addonId, streams = streams, isLoading = false)
    } catch (error: Throwable) {
        if (error is CancellationException) currentCoroutineContext().ensureActive()
        AddonStreamGroup(
            addonName = addonName,
            addonId = addonId,
            streams = emptyList(),
            isLoading = false,
            error = getString(error.serverFailure().message()),
        )
    }
}

internal object ServerStreams {
    fun isNativeRequest(videoId: String): Boolean = ServerItemRef.isServerId(videoId)

    fun canServe(type: String, videoId: String): Boolean {
        ServerItemRef.parse(videoId)?.let { ref -> return ServerRepository.connection(ref.connectionId)?.enabled == true }
        val connections = ServerRepository.enabledConnections().ifEmpty { return false }
        if (connections.any { it.matchesByName() } && ServerMediaKind.fromContentType(type) != null) return true
        val request = ServerMatcher.shared.request(type, videoId, season = null, episode = null) ?: return false
        return connections.any { ServerMatcher.shared.supports(it, request.kind) }
    }

    fun isServerSourceId(addonId: String?): Boolean = addonId?.startsWith(GROUP_PREFIX) == true

    fun sources(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        forceRefresh: Boolean = false,
    ): List<ServerStreamSource> {
        ServerItemRef.parse(videoId)?.let { ref ->
            val connection = ServerRepository.connection(ref.connectionId) ?: return emptyList()
            return listOf(source(connection, preferred = true) { candidates(ref) })
        }
        return serverSources(type, videoId, season, episode, forceRefresh)
    }

    private fun serverSources(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        forceRefresh: Boolean,
    ): List<ServerStreamSource> {
        val connections = ServerRepository.enabledConnections().ifEmpty { return emptyList() }
        // WebDAV drives (PikPak, NAS ...) carry no catalog ids: their files are matched by name.
        val byName = connections
            .filter { it.matchesByName() }
            .mapNotNull { connection -> nameMatchSource(connection, type, videoId, season, episode) }
        val request = ServerMatcher.shared.request(type, videoId, season, episode) ?: return byName
        return connections
            .filter { ServerMatcher.shared.supports(it, request.kind) }
            .map { connection ->
                source(connection, preferred = connection.useCatalogMetadata) {
                    ServerMatcher.shared.match(connection, request, forceRefresh).flatMap { candidates(it) }
                }
            } + byName
    }

    private fun ServerConnection.matchesByName(): Boolean =
        providerId == WebDavProvider.ID && selectedLibraries.isNotEmpty()

    private fun nameMatchSource(
        connection: ServerConnection,
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
    ): ServerStreamSource? {
        val kind = ServerMediaKind.fromContentType(type) ?: return null
        val parts = videoId.split(':')
        val parentSize = if (videoId.startsWith("tt")) 1 else 2
        val parentId = parts.take(parentSize).joinToString(":")
        val position = parts.drop(parentSize).map { it.toIntOrNull() }.takeIf { it.size == 2 }
        val wantedSeason = if (kind == ServerMediaKind.SERIES) season ?: position?.get(0) else null
        val wantedEpisode = if (kind == ServerMediaKind.SERIES) episode ?: position?.get(1) ?: return null else null
        return source(connection, preferred = false) {
            val meta = MetaDetailsRepository.peek(type, parentId) ?: return@source emptyList()
            val year = meta.releaseInfo?.let { Regex("(19|20)\\d{2}").find(it)?.value?.toIntOrNull() }
            val label = ServerRepository.sourceLabel(connection)
            ServerRepository.call(connection.id) { provider, session ->
                (provider as? WebDavProvider)?.matchByName(session, meta.name, year, wantedSeason, wantedEpisode).orEmpty()
            }.map { candidate -> candidate.toStreamItem(label, groupId(connection.id)) }
        }
    }

    private fun source(
        connection: ServerConnection,
        preferred: Boolean,
        loader: suspend () -> List<StreamItem>,
    ): ServerStreamSource =
        ServerStreamSource(
            addonId = groupId(connection.id),
            addonName = ServerRepository.sourceLabel(connection),
            preferred = preferred,
            loader = loader,
        )

    suspend fun candidates(ref: ServerItemRef): List<StreamItem> {
        val connection = ServerRepository.connection(ref.connectionId) ?: throw ServerException(ServerFailure.NOT_FOUND)
        val label = ServerRepository.sourceLabel(connection)
        return ServerRepository.call(ref.connectionId) { provider, session -> provider.candidates(session, ref.itemId) }
            .map { candidate -> candidate.toStreamItem(label, groupId(connection.id)) }
    }

    private fun ServerCandidate.toStreamItem(label: String, addonId: String): StreamItem =
        StreamItem(
            name = title,
            description = description,
            sourceName = label,
            addonName = label,
            addonId = addonId,
            behaviorHints = StreamBehaviorHints(
                filename = filename,
                videoSize = sizeBytes,
            ),
            serverTarget = target,
        )

    private fun groupId(connectionId: String) = "$GROUP_PREFIX$connectionId"

    private const val GROUP_PREFIX = "server:"
}
