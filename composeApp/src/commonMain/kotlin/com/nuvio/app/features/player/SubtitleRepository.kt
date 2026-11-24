package com.nuvio.app.features.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_player_no_subtitles_found
import org.jetbrains.compose.resources.getString

object SubtitleRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _addonSubtitles = MutableStateFlow<List<AddonSubtitle>>(emptyList())
    val addonSubtitles: StateFlow<List<AddonSubtitle>> = _addonSubtitles.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadingProgress = MutableStateFlow<SubtitleLoadingProgress?>(null)
    internal val loadingProgress: StateFlow<SubtitleLoadingProgress?> = _loadingProgress.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var activeFetchJob: Job? = null

    /** SAMplay 2.0.4: the add-on requests behind the current list, to ask again for an expired link. */
    private var lastRequests: List<SubtitleAddonRequest> = emptyList()

    /**
     * SAMplay 2.0.4: asks the add-on of the listed subtitle at [listedUrl] for this video's
     * subtitles again and returns that subtitle's fresh link (see [SubtitleLinks]), learning the
     * fresh links of that add-on's other subtitles on the way. Null when it is not listed.
     */
    internal suspend fun freshLinkFor(listedUrl: String): String? {
        val listed = _addonSubtitles.value.firstOrNull { it.url == listedUrl } ?: return null
        val requests = lastRequests.filter { it.addonName == listed.addonName }
        if (requests.isEmpty()) return null
        val freshList = loadAddonSubtitles(requests)
        for (old in _addonSubtitles.value) freshMatch(old, freshList)?.let { SubtitleLinks.remember(old.url, it.url) }
        return freshMatch(listed, freshList)?.url
    }

    /**
     * [withOpenSubtitles] adds Stremio's public OpenSubtitles add-on to the installed ones; the
     * player asks for it for PikPak/WebDAV files matched to a catalogue title.
     */
    fun fetchAddonSubtitles(
        type: String,
        videoId: String,
        extras: SubtitleRequestExtras? = null,
        withOpenSubtitles: Boolean = false,
    ) {
        activeFetchJob?.cancel()
        _loadingProgress.value = null
        activeFetchJob = scope.launch {
            executeSubtitleFetch(type, videoId, extras, withOpenSubtitles)
        }
    }

    /**
     * Like [fetchAddonSubtitles], but first computes the OpenSubtitles video hash
     * when the stream extras do not already contain one.
     */
    fun fetchAddonSubtitlesWithHashComputation(
        type: String,
        videoId: String,
        extras: SubtitleRequestExtras?,
        sourceUrl: String,
        sourceHeaders: Map<String, String>,
        withOpenSubtitles: Boolean = false,
        onExtrasUpdated: (SubtitleRequestExtras) -> Unit,
    ) {
        activeFetchJob?.cancel()
        _loadingProgress.value = null
        activeFetchJob = scope.launch {
            _isLoading.value = true
            _error.value = null
            _addonSubtitles.value = emptyList()

            val enrichedExtras = try {
                val hashResult = computeVideoHash(sourceUrl, sourceHeaders)
                if (hashResult != null) {
                    val (hash, fileSize) = hashResult
                    val updated = (extras ?: SubtitleRequestExtras()).copy(
                        videoHash = hash,
                        videoSize = extras?.videoSize ?: fileSize,
                    )
                    onExtrasUpdated(updated)
                    updated
                } else extras
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                extras
            }

            executeSubtitleLoad(type, videoId, enrichedExtras, withOpenSubtitles)
        }
    }

    /** Subtitles for any catalogue title, without touching the current list (online search). */
    suspend fun search(type: String, videoId: String): List<AddonSubtitle> =
        loadAddonSubtitles(subtitleRequestsWithOpenSubtitles(type, videoId))

    /** A subtitle the user found online or loaded from a file, listed first for this video. */
    fun addExtra(subtitle: AddonSubtitle) {
        _addonSubtitles.update { current -> listOf(subtitle) + current.filterNot { it.id == subtitle.id } }
        _error.value = null
    }

    fun clear() {
        activeFetchJob?.cancel()
        _loadingProgress.value = null
        _addonSubtitles.value = emptyList()
        _isLoading.value = false
        _error.value = null
    }

    private suspend fun executeSubtitleFetch(
        type: String,
        videoId: String,
        extras: SubtitleRequestExtras?,
        withOpenSubtitles: Boolean,
    ) {
        _isLoading.value = true
        _error.value = null
        _addonSubtitles.value = emptyList()
        executeSubtitleLoad(type, videoId, extras, withOpenSubtitles)
    }

    private suspend fun executeSubtitleLoad(
        type: String,
        videoId: String,
        extras: SubtitleRequestExtras?,
        withOpenSubtitles: Boolean,
    ) {
        val requests = if (withOpenSubtitles) {
            subtitleRequestsWithOpenSubtitles(type, videoId, extras)
        } else {
            addonSubtitleRequests(type, videoId, extras)
        }
        lastRequests = requests
        SubtitleLinks.clear()
        if (requests.isEmpty()) {
            _isLoading.value = false
            return
        }

        _loadingProgress.value = SubtitleLoadingProgress(total = requests.size)
        loadAddonSubtitles(requests) { request, subtitles ->
            _addonSubtitles.update { it + subtitles }
            _loadingProgress.update { progress ->
                progress?.copy(completed = progress.completed + 1, addonName = request.addonName)
            }
        }

        if (_addonSubtitles.value.isEmpty()) {
            _error.value = getString(Res.string.compose_player_no_subtitles_found)
        }
        _isLoading.value = false
    }
}

internal data class SubtitleLoadingProgress(
    val total: Int,
    val completed: Int = 0,
    val addonName: String? = null,
)
