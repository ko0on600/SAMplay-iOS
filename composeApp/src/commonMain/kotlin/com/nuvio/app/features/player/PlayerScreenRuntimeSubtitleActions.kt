package com.nuvio.app.features.player

import com.nuvio.app.core.i18n.localizedNoSubtitleLinesFound
import com.nuvio.app.core.i18n.localizedSubtitleLinesLoadError
import com.nuvio.app.features.autosync.AutoSyncPlayerController
import com.nuvio.app.features.autosync.AutomaticSubtitleSync
import com.nuvio.app.features.streams.StreamLinkCacheRepository
import kotlinx.coroutines.launch

internal fun PlayerScreenRuntime.fetchAddonSubtitlesForActiveItem() {
    if (activeSourceUrl.startsWith("file:") && externalSubtitles.isNotEmpty()) {
        SubtitleRepository.clear()
        return
    }
    // Without a network the addon lookup can only hang; keep the local state untouched.
    if (isNetworkOffline()) return
    val type = activeAddonSubtitleType.takeIf { it.isNotBlank() } ?: return
    val videoId = addonSubtitleVideoId ?: return
    // A PikPak/WebDAV file matched to a catalogue title also asks OpenSubtitles.
    val withOpenSubtitles = videoId != activeVideoId
    // SAMplay 2.0.3: a device file's name and size help OpenSubtitles pick the right release.
    val extras = activeSubtitleExtras
        ?: if (isDeviceVideo) SubtitleRequestExtras(filename = activeSourceFilename) else null
    // For P2P streams activeSourceUrl is a torrent:// sentinel — the real HTTP URL
    // comes from the local engine once the stream is resolved, mirroring NuvioTV where
    // currentStreamUrl is set to the local engine URL for torrents.
    val hashableUrl = p2pResolvedSourceUrl ?: activeSourceUrl
    val hashable = hashableUrl.startsWith("http://", true) || hashableUrl.startsWith("https://", true) ||
        // SAMplay 2.0.3: a device file is hashed where it lies.
        (isDeviceVideo && (hashableUrl.startsWith("content:", true) || hashableUrl.startsWith("file:", true)))
    if (extras?.videoHash == null && hashableUrl.isNotBlank() && hashable) {
        // Compute hash lazily — same pattern as NuvioTV.
        SubtitleRepository.fetchAddonSubtitlesWithHashComputation(
            type = type,
            videoId = videoId,
            extras = extras,
            sourceUrl = hashableUrl,
            sourceHeaders = if (p2pResolvedSourceUrl != null) emptyMap() else activeSourceHeaders,
            withOpenSubtitles = withOpenSubtitles,
            onExtrasUpdated = { enriched ->
                activeSubtitleExtras = enriched
                persistComputedVideoHash(enriched)
            },
        )
    } else {
        SubtitleRepository.fetchAddonSubtitles(type, videoId, extras, withOpenSubtitles)
    }
}

/**
 * After lazy hash computation succeeds, patch the *existing* cached stream link
 * entry with the newly computed hash. Uses a read-modify-write so that we never
 * create a new entry (respecting server-stream exclusions and saveForReuse=false)
 * and never reset cachedAtMs.
 */
private fun PlayerScreenRuntime.persistComputedVideoHash(enriched: SubtitleRequestExtras) {
    if (!playerSettingsUiState.streamReuseLastLinkEnabled) return
    val vid = activeVideoId ?: return
    val cacheKey = StreamLinkCacheRepository.contentKey(
        type = contentType ?: parentMetaType,
        videoId = vid,
        parentMetaId = parentMetaId,
        season = activeSeasonNumber,
        episode = activeEpisodeNumber,
    )
    StreamLinkCacheRepository.updateVideoHash(cacheKey, enriched.videoHash, enriched.videoSize)
}

internal fun PlayerScreenRuntime.setSubtitleDelay(delayMs: Int) {
    val clamped = delayMs.coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
    subtitleDelayMs = clamped
    PlayerTrackPreferenceStorage.saveSubtitleDelayMs(playbackSession.videoId, clamped)
    playerController?.setSubtitleDelayMs(clamped)
}

internal fun PlayerScreenRuntime.loadSubtitleAutoSyncCues(force: Boolean = false) {
    val subtitle = selectedAddonSubtitle ?: return
    if (!force && subtitleAutoSyncState.cues.isNotEmpty()) return
    // Offline: the cue download would only hang, so skip it and leave the state as is.
    if (isNetworkOffline()) return
    subtitleAutoSyncState = subtitleAutoSyncState.copy(isLoading = true, errorMessage = null)
    scope.launch {
        val result = runCatching {
            // SAMplay: the AutoSync loader also reads local files (AI subtitles, device files)
            // and legacy encodings such as Windows-1256.
            val body = AutomaticSubtitleSync.downloadSubtitleBody(
                url = subtitle.url,
                headers = sanitizePlaybackHeaders(activeSourceHeaders),
            )
            PlayerSubtitleCueParser.parse(body, subtitle.url)
        }
        if (selectedAddonSubtitle?.url != subtitle.url) return@launch
        result.fold(
            onSuccess = { cues ->
                subtitleAutoSyncState = subtitleAutoSyncState.copy(
                    cues = cues,
                    isLoading = false,
                    errorMessage = if (cues.isEmpty()) localizedNoSubtitleLinesFound() else null,
                )
            },
            onFailure = { error ->
                subtitleAutoSyncState = subtitleAutoSyncState.copy(
                    isLoading = false,
                    errorMessage = error.message ?: localizedSubtitleLinesLoadError(),
                )
            },
        )
    }
}

internal fun PlayerScreenRuntime.captureSubtitleAutoSyncTime() {
    subtitleAutoSyncState = subtitleAutoSyncState.copy(
        capturedPositionMs = playbackSnapshot.positionMs.coerceAtLeast(0L),
        errorMessage = null,
    )
    loadSubtitleAutoSyncCues()
}

internal fun PlayerScreenRuntime.applySubtitleAutoSyncCue(cue: SubtitleSyncCue) {
    val capturedPositionMs = subtitleAutoSyncState.capturedPositionMs ?: return
    val newDelayMs = (capturedPositionMs - cue.startTimeMs - SUBTITLE_AUTO_SYNC_REACTION_COMPENSATION_MS)
        .toInt()
        .coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
    setSubtitleDelay(newDelayMs)
}

internal fun PlayerScreenRuntime.selectAddonSubtitle(addon: AddonSubtitle) {
    isUserExplicitSubtitleSelection = true
    selectedAddonSubtitleId = addon.selectionKey
    selectedSubtitleIndex = -1
    useCustomSubtitles = true
    preferredSubtitleSelectionApplied = true
    persistAddonSubtitlePreference(addon)
    attachSelectedAddonSubtitleWithAutoSync(addon.url) // AutoSync hook
    if (hasBurnedInServerSubtitle) clearServerSubtitleTrack()
}

/** A subtitle found by the online search or loaded from a file: listed first, then selected. */
internal fun PlayerScreenRuntime.selectExtraSubtitle(subtitle: AddonSubtitle) {
    SubtitleRepository.addExtra(subtitle)
    selectAddonSubtitle(subtitle)
}

/**
 * A result of the AI subtitle tools (from SAMplay). Subtitles made from the video's audio arrive
 * in parts while the rest is processed: each part replaces the listed one and is shown, unless the
 * user has picked another subtitle meanwhile. They are in time already, so they skip AutoSync and
 * the delay. Translations keep the timing of the subtitle they come from and go through AutoSync.
 */
internal fun PlayerScreenRuntime.selectAiSubtitle(subtitle: AddonSubtitle) {
    if (!subtitle.id.startsWith(AI_GENERATED_SUBTITLE_PREFIX)) {
        selectExtraSubtitle(subtitle)
        return
    }
    val listed = SubtitleRepository.addonSubtitles.value.any { it.id == subtitle.id }
    val showing = useCustomSubtitles && selectedAddonSubtitle?.id == subtitle.id
    SubtitleRepository.addExtra(subtitle)
    if (listed && !showing) return
    isUserExplicitSubtitleSelection = true
    selectedAddonSubtitleId = subtitle.selectionKey
    selectedSubtitleIndex = -1
    useCustomSubtitles = true
    preferredSubtitleSelectionApplied = true
    subtitleAutoSyncState = SubtitleAutoSyncUiState()
    setSubtitleDelay(0)
    playerController?.setSubtitleUri(subtitle.url)
    if (hasBurnedInServerSubtitle) clearServerSubtitleTrack()
}

/** Headers a subtitle URL needs: a WebDAV drive's sidecar subtitles carry its credentials. */
internal fun PlayerScreenRuntime.subtitleRequestHeaders(url: String): Map<String, String> =
    externalSubtitles.firstOrNull { it.url == url }?.headers.orEmpty()

/** The playing title as the online subtitle search should first look for it. */
internal val PlayerScreenRuntime.subtitleSearchContext: SubtitleSearchContext
    get() {
        // SAMplay 2.0.3: a device file starts from its cleaned name (or its matched title), not the
        // raw file name the catalogue search found nothing for.
        if (isDeviceVideo) {
            val match = deviceVideoMatch
            val query = DeviceVideoMatcher.query(activeSourceFilename ?: activeStreamFilename ?: title, activeStreamSubtitle)
            return SubtitleSearchContext(
                title = match?.title ?: query?.title ?: title,
                type = match?.type ?: if (query?.series == true) "series" else "movie",
                season = match?.season ?: query?.season,
                episode = match?.episode ?: query?.episode,
                imdbId = match?.imdbId,
            )
        }
        val meta = (metaUiState.meta ?: playerMeta)?.takeIf { it.id == parentMetaId }
        val imdbId = matchedServerImdbId
            ?: parentMetaId.takeIf { it.startsWith("tt") }
            ?: meta?.imdbId?.takeIf { it.startsWith("tt") }
        return SubtitleSearchContext(
            title = meta?.name?.takeIf { it.isNotBlank() } ?: title,
            type = if (activeAddonSubtitleType.equals("movie", ignoreCase = true)) "movie" else "series",
            season = activeSeasonNumber,
            episode = activeEpisodeNumber,
            imdbId = imdbId?.substringBefore(':'),
        )
    }
