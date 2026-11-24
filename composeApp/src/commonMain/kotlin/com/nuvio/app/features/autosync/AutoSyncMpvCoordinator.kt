package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.autosync.bubble.AutoSyncBubbleKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.autosync_toast_failed
import nuvio.composeapp.generated.resources.autosync_toast_failed_audio_fallback
import nuvio.composeapp.generated.resources.autosync_toast_failed_no_reference
import nuvio.composeapp.generated.resources.autosync_toast_analyzing
import nuvio.composeapp.generated.resources.autosync_toast_in_sync
import nuvio.composeapp.generated.resources.autosync_toast_retry_none
import nuvio.composeapp.generated.resources.autosync_toast_retry_synced
import nuvio.composeapp.generated.resources.autosync_toast_synced
import nuvio.composeapp.generated.resources.autosync_toast_synced_replaced
import nuvio.composeapp.generated.resources.autosync_toast_no_fit
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import kotlin.math.roundToLong

internal const val LIBMPV_AUTOSYNC_FILE_PREFIX = "autosync_"

internal interface AutoSyncMpvPlatform {
    val isLiveTv: Boolean get() = false
    suspend fun writeSubtitleFile(fileName: String, stalePrefix: String, content: String): String?
    fun showMessage(text: String)
    fun showMessage(kind: AutoSyncBubbleKind, text: String) = showMessage(text)

    /** The audio sync for this stream, where the platform has one (Android). */
    val audioSync: AutoSyncMpvAudioSync? get() = null

    /** One line of the sync diagnostics log (Settings › share the sync log). */
    fun log(message: String) {}
}

/**
 * Nuvio RS (from SAMplay, where it runs on libVLC): the audio sync on the libmpv engine. It reads
 * the stream's audio itself, syncs a subtitle AutoSync could not time, and watches one it did time
 * for a later drift. Each correction is loaded into mpv as a file in place of the track.
 */
internal interface AutoSyncMpvAudioSync {
    /** Listening or syncing right now. */
    val isActive: Boolean

    /** Called when the audio sync shows another subtitle in place of the one it was given. */
    var onSubtitleReplaced: ((url: String) -> Unit)?

    fun setCandidates(candidates: List<AutoSyncSubtitleCandidate>)

    fun setContent(type: String, videoId: String)

    /** AutoSync started on a subtitle: listen meanwhile, so a takeover starts with audio. */
    fun arm()

    /** AutoSync applied its own result, or gave up: nothing left to listen for. */
    fun disarm()

    /**
     * AutoSync kept the timing of [url] ([body], shown by mpv as the track loaded from
     * [loadedUrl]): sync it to the audio. False when the audio sync is off or cannot read it.
     */
    suspend fun takeOver(
        url: String,
        body: String,
        loadedUrl: String,
        replace: (replacedUrl: String, path: String) -> Unit,
    ): Boolean

    /** AutoSync timed [url] itself ([shownBody]): watch for a confirmed drift later on. */
    suspend fun watchForDrift(
        url: String,
        shownBody: String,
        loadedUrl: String,
        replace: (replacedUrl: String, path: String) -> Unit,
    ): Boolean

    /** Another subtitle or track: stop, leaving the subtitle on its original timing. */
    fun stop()

    /** The user set the delay by hand: stop adjusting, keep the subtitle exactly as shown. */
    fun freeze()

    fun release()
}

internal class AutoSyncMpvCoordinator(
    private val scope: CoroutineScope,
    private val sourceUrl: String,
    private val sourceHeaders: Map<String, String>,
    private val getSubtitleHeaders: (String) -> Map<String, String>,
    private val getPreferredLanguage: () -> String?,
    private val platform: AutoSyncMpvPlatform,
) {
    private var job: Job? = null
    private var operationToken = 0L
    private var candidates: List<AutoSyncSubtitleCandidate> = emptyList()
    private var appliedListener: ((subtitleUrl: String, delayMs: Int) -> Unit)? = null
    private val _retryState = MutableStateFlow(AutoSyncRetryUiState())
    val retryState = _retryState.asStateFlow()
    private var retryContext: RetryContext? = null
    private var appliedDelayMs: Int? = null

    init {
        // The audio found a subtitle that fits better and loaded it: the player shows it as chosen.
        platform.audioSync?.onSubtitleReplaced = { url -> appliedListener?.invoke(url, 0) }
    }

    private data class RetryContext(
        val resolved: AutoSyncResolvedTimeline,
        val originalBody: String,
        val language: String?,
        val base: PlayerEngineController,
        val replaceExternalSubtitle: (String, String) -> Unit,
        val rejectedKeys: Set<String> = emptySet(),
    )

    /**
     * [replaceExternalSubtitle] loads a local file in place of the external track that was loaded
     * from a URL, keeping that track's label.
     */
    fun wrap(
        controller: PlayerEngineController,
        replaceExternalSubtitle: (replacedUrl: String, path: String) -> Unit,
    ): PlayerEngineController =
        AutoSyncMpvEngineController(controller, this, replaceExternalSubtitle)

    fun setCandidates(value: List<AutoSyncSubtitleCandidate>) {
        candidates = value.distinctBy { it.url }
        platform.audioSync?.setCandidates(candidates)
    }

    fun setContent(type: String, videoId: String) {
        platform.audioSync?.setContent(type, videoId)
    }

    fun setAppliedListener(listener: ((subtitleUrl: String, delayMs: Int) -> Unit)?) {
        appliedListener = listener
    }

    fun cancel() {
        operationToken++
        job?.cancel()
        job = null
        retryContext = null
        appliedDelayMs = null
        _retryState.value = AutoSyncRetryUiState()
        platform.audioSync?.stop()
    }

    fun onManualSubtitleDelayChanged(delayMs: Int) {
        // The runtime echoes our applied delay back through the controller. That is not a
        // manual adjustment and must keep the successful result available for a reference retry.
        if (appliedDelayMs == delayMs) return
        val audio = platform.audioSync
        // The audio sync retimes the file and keeps the delay at 0: a 0 is the player restoring it.
        if (delayMs == 0 && appliedDelayMs == null && audio?.isActive == true) return
        if (audio?.isActive == true) {
            platform.log(
                "[autosync] manual delay ${delayMs}ms: audio sync stopped, the user's timing is kept " +
                    "until another subtitle or AutoSync is chosen",
            )
        }
        // Nuvio RS (from SAMplay): the user's delay wins; the subtitle stays exactly as shown.
        audio?.freeze()
        cancel()
        AutoSyncSyncedSubtitle.clear()
    }

    fun dispose() {
        cancel()
        appliedListener = null
        platform.audioSync?.release()
    }

    private fun showMessage(token: Long, kind: AutoSyncBubbleKind, resource: StringResource) {
        scope.launch {
            val text = getString(resource)
            if (operationToken == token) platform.showMessage(kind, text)
        }
    }

    private fun showFailure(token: Long, resource: StringResource) =
        showMessage(token, AutoSyncBubbleKind.Failure, resource)

    fun retryWithAnotherReference() {
        val snapshot = retryContext ?: return
        if (_retryState.value.busy || _retryState.value.exhausted) return
        operationToken++
        job?.cancel()
        val retry = snapshot.copy(rejectedKeys = mergeRejectedReferenceKeys(
            snapshot.rejectedKeys,
            snapshot.resolved.reference.key,
            snapshot.resolved.reference.equivalentKeys,
        ))
        retryContext = retry
        platform.audioSync?.disarm()
        _retryState.value = AutoSyncRetryUiState(available = true, busy = true, status = AutoSyncRetryStatus.TRYING)
        runSync(snapshot.resolved.subtitleUrl, AutoSyncCandidateScope.SELECTED_ONLY,
            snapshot.base, snapshot.replaceExternalSubtitle, retry)
    }

    fun start(
        url: String,
        candidateScope: AutoSyncCandidateScope,
        base: PlayerEngineController,
        replaceExternalSubtitle: (replacedUrl: String, path: String) -> Unit,
    ) {
        cancel()
        base.setSubtitleUri(url)
        AutoSyncPreferencesRepository.ensureLoaded()
        if (decideAutoSyncStart(AutoSyncPreferencesRepository.preferredSubtitleAutoSyncOnStart.value) ==
            AutoSyncStartAction.ATTACH_ORIGINAL ||
            platform.isLiveTv ||
            !isAutoSyncSource(sourceUrl)) {
            platform.log(
                if (platform.isLiveTv || !isAutoSyncSource(sourceUrl)) {
                    "[autosync] not started: live TV or a source it cannot read"
                } else {
                    "[autosync] subtitle loaded: not synced, \"Auto Sync Subtitle\" is off in Settings"
                },
            )
            return
        }

        platform.log("[autosync] start $url scope=$candidateScope candidates=${candidates.size} engine=mpv")
        runSync(url, candidateScope, base, replaceExternalSubtitle)
    }

    private fun runSync(
        url: String,
        candidateScope: AutoSyncCandidateScope,
        base: PlayerEngineController,
        replaceExternalSubtitle: (String, String) -> Unit,
        retry: RetryContext? = null,
    ) {
        val token = operationToken
        val subtitleHeaders = retry?.resolved?.subtitleHeaders ?: getSubtitleHeaders(url)
        val subtitleLanguage = retry?.language ?: candidates.firstOrNull { it.url == url }?.language
            ?.takeIf { it.isNotBlank() } ?: getPreferredLanguage()
        EmbeddedSubtitleTimelineLoader.prefetch(scope, sourceUrl, sourceHeaders)
        if (retry == null) platform.audioSync?.arm()

        job = scope.launch {
            try {
                showMessage(token, AutoSyncBubbleKind.Working, Res.string.autosync_toast_analyzing)
                var outcome: AutoSyncAnalysisOutcome? = null
                var searchOutcome: AutoSyncReferenceSearchOutcome? = null
                suspend fun search(searchScope: AutoSyncCandidateScope) = AutomaticSubtitleSync.findTimelineRetime(
                    sourceKey = sourceUrl,
                    sourceHeaders = sourceHeaders,
                    selectedSubtitleUrl = url,
                    selectedSubtitleHeaders = subtitleHeaders,
                    preferredLanguage = subtitleLanguage,
                    alternativeSubtitles = searchScope.alternativeCandidates(candidates),
                    alternativeSubtitlesProvider = if (searchScope.usesAlternativeProvider) {
                        { candidates }
                    } else null,
                    onAnalysisOutcome = { outcome = it },
                    selectedSubtitleBodyDeferred = retry?.let { CompletableDeferred(it.originalBody) },
                    excludedReferenceKeys = retry?.rejectedKeys.orEmpty(),
                    requiredReferenceSource = retry?.resolved?.reference?.source,
                    onReferenceSearchOutcome = { searchOutcome = it },
                    allowLiveReference = false,
                )
                var resolved = search(candidateScope)
                currentCoroutineContext().ensureActive()
                if (retry == null && resolved == null && token == operationToken &&
                    candidateScope.searchAfterSelectedFailed(outcome)
                ) {
                    resolved = search(AutoSyncCandidateScope.STARTUP_SEARCH)
                }
                currentCoroutineContext().ensureActive()
                if (token != operationToken) return@launch
                platform.log("[autosync] analysis done: ${if (resolved == null) "no match" else "match"} outcome=$outcome")
                if (resolved == null) {
                    if (retry != null) {
                        val exhausted = searchOutcome == AutoSyncReferenceSearchOutcome.EXHAUSTED
                        _retryState.value = AutoSyncRetryUiState(
                            available = true,
                            exhausted = exhausted,
                            status = if (exhausted) AutoSyncRetryStatus.EXHAUSTED else AutoSyncRetryStatus.FAILED,
                        )
                        showFailure(token, Res.string.autosync_toast_retry_none)
                        return@launch
                    }
                    // Original timing kept: sync it to the audio instead, when AutoSync found nothing
                    // to align to or only a weak match (not when the subtitle itself failed to load).
                    if (takeOverByAudio(token, url, subtitleHeaders, outcome, replaceExternalSubtitle)) return@launch
                    showFailure(token, when (outcome) {
                        AutoSyncAnalysisOutcome.NO_SUBTITLE_TRACKS,
                        AutoSyncAnalysisOutcome.NO_USABLE_REFERENCE -> Res.string.autosync_toast_failed_no_reference
                        AutoSyncAnalysisOutcome.NO_FIT -> Res.string.autosync_toast_no_fit
                        else -> Res.string.autosync_toast_failed
                    })
                    return@launch
                }

                val chosenUrl = resolved.subtitleUrl
                val tolerance = AutoSyncPreferencesRepository.syncToleranceMs.value
                val withinTolerance = retry == null && tolerance > 0 && chosenUrl == url &&
                    resolved.timeline.maxAlignmentShiftMs() <= tolerance
                // Always retain original timestamps: retries must never retime an already shifted file.
                val originalBody = retry?.originalBody ?: resolved.subtitleBody
                    ?: AutomaticSubtitleSync.downloadSubtitleBody(
                        url = chosenUrl,
                        headers = resolved.subtitleHeaders.ifEmpty { getSubtitleHeaders(chosenUrl) },
                    )
                currentCoroutineContext().ensureActive()
                if (token != operationToken) return@launch
                var shownBody = originalBody
                platform.audioSync?.disarm()
                if (!withinTolerance) {
                    val written = writeRetimedSubtitle(chosenUrl, resolved.copy(subtitleBody = originalBody))
                    currentCoroutineContext().ensureActive()
                    if (token != operationToken) return@launch
                    if (written == null) {
                        platform.log("[autosync] match found but not applied: this subtitle format cannot be retimed")
                        showFailure(token, Res.string.autosync_toast_failed)
                        return@launch
                    }
                    replaceExternalSubtitle(url, written.first)
                    shownBody = written.second
                }
                base.setSubtitleDelayMs(0)
                appliedDelayMs = 0
                retryContext = RetryContext(resolved, originalBody, subtitleLanguage, base,
                    replaceExternalSubtitle, retry?.rejectedKeys.orEmpty())
                _retryState.value = AutoSyncRetryUiState(available = true,
                    status = if (retry == null) AutoSyncRetryStatus.IDLE else AutoSyncRetryStatus.UPDATED)
                appliedListener?.invoke(chosenUrl, 0)
                AutoSyncSyncedSubtitle.mark(chosenUrl)
                // Nuvio RS (from SAMplay): synced; the audio keeps watching from here on and corrects
                // a confirmed drift mid-episode on its own (never after a manual delay).
                val watching = platform.audioSync?.watchForDrift(chosenUrl, shownBody, url, replaceExternalSubtitle) ?: false
                val timeline = resolved.timeline
                platform.log(
                    "[autosync] applied ${timeline.alignmentSource} offset=${timeline.alignmentInterceptMs.roundToLong()}ms " +
                        "scale=${timeline.alignmentScale} shift=${timeline.maxAlignmentShiftMs().roundToLong()}ms " +
                        "replaced=${chosenUrl != url} withinTolerance=$withinTolerance retry=${retry != null} " +
                        "driftWatch=$watching" +
                        timeline.steps.takeIf { it.size > 1 }?.joinToString(prefix = " steps=", separator = ",") { step ->
                            val from = if (step.fromOriginalMs == Long.MIN_VALUE) "0" else "${step.fromOriginalMs / 1_000}"
                            "${from}s:${step.offsetMs.roundToLong()}ms"
                        }.orEmpty(),
                )
                showMessage(token, AutoSyncBubbleKind.Success, when {
                    retry != null -> Res.string.autosync_toast_retry_synced
                    chosenUrl != url -> Res.string.autosync_toast_synced_replaced
                    withinTolerance -> Res.string.autosync_toast_in_sync
                    else -> Res.string.autosync_toast_synced
                })
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (token != operationToken) return@launch
                platform.log("[autosync] analysis failed: $error")
                platform.audioSync?.disarm()
                showFailure(token, Res.string.autosync_toast_failed)
            } finally {
                if (token == operationToken && _retryState.value.busy) {
                    _retryState.value = _retryState.value.copy(busy = false, status = AutoSyncRetryStatus.FAILED)
                }
            }
        }
    }

    /**
     * AutoSync kept the original timing of [url]: hands it to the audio sync. True when the audio
     * took over (its own messages follow); false leaves the failure message to the caller.
     */
    private suspend fun takeOverByAudio(
        token: Long,
        url: String,
        subtitleHeaders: Map<String, String>,
        outcome: AutoSyncAnalysisOutcome?,
        replaceExternalSubtitle: (String, String) -> Unit,
    ): Boolean {
        val audio = platform.audioSync ?: return false
        if (outcome == AutoSyncAnalysisOutcome.SUBTITLE_UNAVAILABLE) {
            platform.log("[autosync] no confident match ($outcome) audio=false engine=mpv")
            audio.disarm()
            return false
        }
        val body = try {
            AutomaticSubtitleSync.downloadSubtitleBody(url = url, headers = subtitleHeaders)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            platform.log("[autosync] audio takeover dropped: subtitle download failed ($error)")
            null
        }
        currentCoroutineContext().ensureActive()
        if (token != operationToken || body == null) {
            audio.disarm()
            return false
        }
        val takesOver = audio.takeOver(url, body, url, replaceExternalSubtitle)
        platform.log("[autosync] no confident match ($outcome) audio=$takesOver engine=mpv")
        if (takesOver) showMessage(token, AutoSyncBubbleKind.Working, Res.string.autosync_toast_failed_audio_fallback)
        return takesOver
    }

    /**
     * Writes the subtitle at [subtitleUrl] with the AutoSync timeline applied, and returns its path
     * and the retimed text, or null when its format cannot be retimed. The name follows the
     * content, so libmpv's `cached` sub-add reuses the track when the same result is applied again.
     */
    private suspend fun writeRetimedSubtitle(
        subtitleUrl: String,
        resolved: AutoSyncResolvedTimeline,
    ): Pair<String, String>? {
        val body = resolved.subtitleBody ?: AutomaticSubtitleSync.downloadSubtitleBody(
            url = subtitleUrl,
            headers = resolved.subtitleHeaders.ifEmpty { getSubtitleHeaders(subtitleUrl) },
        )
        val retimed = withContext(Dispatchers.Default) {
            AutoSyncSubtitleTextRetimer.retime(body, resolved.timeline)
        } ?: return null
        val prefix = "$LIBMPV_AUTOSYNC_FILE_PREFIX${subtitleUrl.hashCode().toUInt().toString(16)}_"
        val extension = when {
            retimed.trimStart().startsWith("WEBVTT") -> "vtt"
            retimed.contains("[Events]", ignoreCase = true) -> "ass"
            else -> "srt"
        }
        val path = platform.writeSubtitleFile("$prefix${retimed.hashCode().toUInt().toString(16)}.$extension", prefix, retimed)
            ?: return null
        return path to retimed
    }
}

/**
 * Nuvio's libmpv controller with AutoSync layered on top. Every call is forwarded to [base]
 * unchanged; subtitle changes first stop any AutoSync run that still targets the old subtitle.
 */
internal open class AutoSyncMpvEngineController(
    private val base: PlayerEngineController,
    private val coordinator: AutoSyncMpvCoordinator,
    private val replaceExternalSubtitle: (replacedUrl: String, path: String) -> Unit,
) : PlayerEngineController by base, AutoSyncPlayerController {
    override val autoSyncRetryState = coordinator.retryState

    override fun retryWithAnotherReference() = coordinator.retryWithAnotherReference()

    override fun setAutoSyncSubtitleCandidates(candidates: List<AutoSyncSubtitleCandidate>) =
        coordinator.setCandidates(candidates)

    override fun setAutoSyncAppliedListener(
        listener: ((subtitleUrl: String, delayMs: Int) -> Unit)?,
    ) = coordinator.setAppliedListener(listener)

    override fun setAutoSyncContent(type: String, videoId: String) = coordinator.setContent(type, videoId)

    override fun setSubtitleUri(url: String) {
        coordinator.cancel()
        base.setSubtitleUri(url)
    }

    override fun setSubtitleUriWithAutoSync(url: String) =
        coordinator.start(url, AutoSyncCandidateScope.STARTUP_SEARCH, base, replaceExternalSubtitle)

    override fun setSubtitleUriWithSelectedAutoSync(url: String) =
        coordinator.start(url, AutoSyncCandidateScope.SELECTED_ONLY, base, replaceExternalSubtitle)

    override fun setSubtitleUriWithRestoredAutoSync(url: String) =
        coordinator.start(url, AutoSyncCandidateScope.SELECTED_THEN_SEARCH, base, replaceExternalSubtitle)

    override fun selectSubtitleTrack(index: Int) {
        coordinator.cancel()
        base.selectSubtitleTrack(index)
    }

    override fun clearExternalSubtitle() {
        coordinator.cancel()
        base.clearExternalSubtitle()
    }

    override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
        coordinator.cancel()
        base.clearExternalSubtitleAndSelect(trackIndex)
    }

    override fun setSubtitleDelayMs(delayMs: Int) {
        coordinator.onManualSubtitleDelayChanged(delayMs)
        base.setSubtitleDelayMs(delayMs)
    }
}
