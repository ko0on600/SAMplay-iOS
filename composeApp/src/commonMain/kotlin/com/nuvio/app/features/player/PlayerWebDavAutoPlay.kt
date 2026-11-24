package com.nuvio.app.features.player

import com.nuvio.app.features.servers.ServerItemRef
import com.nuvio.app.features.servers.ServerRepository
import com.nuvio.app.features.servers.webdav.WebDavProvider

/** True while the player plays a file from a PikPak/WebDAV drive. */
internal val PlayerScreenRuntime.isWebDavPlayback: Boolean
    get() = ServerItemRef.parse(parentMetaId)
        ?.let { ref -> ServerRepository.connection(ref.connectionId)?.providerId == WebDavProvider.ID } == true

/**
 * Nuvio RS: the player settings the next-episode autoplay follows. A PikPak/WebDAV show folder
 * always goes on to its next episode, like the folder's playlist, whatever the add-on setting says.
 * SAMplay 2.0.3: so does a series downloaded in the app, when its next episode is downloaded too.
 */
internal val PlayerScreenRuntime.autoPlaySettings: PlayerSettingsUiState
    get() = if (
        !playerSettingsUiState.streamAutoPlayNextEpisodeEnabled &&
        alwaysPlaysNextEpisode(
            webDavPlayback = isWebDavPlayback,
            deviceVideo = isDeviceVideo,
            sourceUrl = activeSourceUrl,
            nextEpisodeDownloaded = nextEpisodeDownloaded,
        )
    ) {
        playerSettingsUiState.copy(streamAutoPlayNextEpisodeEnabled = true)
    } else {
        playerSettingsUiState
    }

/**
 * True when the next episode plays by itself whatever the auto-play setting says: the show is a
 * PikPak/WebDAV folder, or the playing episode is a download and the next one is downloaded too.
 */
internal fun alwaysPlaysNextEpisode(
    webDavPlayback: Boolean,
    deviceVideo: Boolean,
    sourceUrl: String,
    nextEpisodeDownloaded: Boolean,
): Boolean {
    if (webDavPlayback) return true
    if (deviceVideo || !nextEpisodeDownloaded) return false
    val scheme = sourceUrl.trim().substringBefore(':').lowercase()
    return scheme == "file" || scheme == "content"
}
