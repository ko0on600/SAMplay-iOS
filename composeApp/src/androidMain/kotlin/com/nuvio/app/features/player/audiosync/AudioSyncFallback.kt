@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player.audiosync

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.CuesWithTiming
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.autosync.AutoSyncSyncedSubtitle
import com.nuvio.app.features.autosync.bubble.AutoSyncBubbleKind
import com.nuvio.app.features.autosync.bubble.showAutoSyncMessage
import com.nuvio.app.features.player.AudioSyncSettings
import com.nuvio.app.features.player.parseSidecarTimedCuesRobust
import com.nuvio.app.features.player.audiosync.asr.AsrModel
import com.nuvio.app.core.diagnostics.Diag
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_audio_sync_adjusted
import nuvio.composeapp.generated.resources.player_audio_sync_estimated
import nuvio.composeapp.generated.resources.player_audio_sync_live_only
import nuvio.composeapp.generated.resources.player_audio_sync_model_downloading
import nuvio.composeapp.generated.resources.player_audio_sync_model_needs_wifi
import nuvio.composeapp.generated.resources.player_audio_sync_synced
import nuvio.composeapp.generated.resources.player_audio_sync_synced_rate
import nuvio.composeapp.generated.resources.player_audio_sync_withdrawn
import org.jetbrains.compose.resources.getString
import java.util.concurrent.atomic.AtomicReference

/**
 * Audio subtitle sync as AutoSync's fallback, for one stream of one player.
 *
 * AutoSync decides first. While it analyses a subtitle the audio is already being listened to
 * ([arm]); when it keeps the subtitle's original timing (no embedded subtitles, no usable
 * reference, or a weak match) the audio takes over ([takeOver]); otherwise nothing more is done
 * ([disarm]). The mapping found is applied by retiming the subtitle AutoSync's sidecar shows, the
 * same way AutoSync applies its own result, so Nuvio's subtitle rendering is untouched.
 */
internal class AudioSyncFallback(
    context: Context,
    private val scope: CoroutineScope,
    /** ExoPlayer when it plays the video; null on the libVLC engine (SAMplay TV 1.0.2). */
    private val player: ExoPlayer?,
    private val sidecar: AudioSyncSubtitleHost,
    private val sourceUrl: String,
    sourceAudioUrl: String?,
    dataSourceFactory: DataSource.Factory?,
    /** Playhead and length in ms of whichever engine plays; read on the main thread. */
    private val positionMs: () -> Long = { player?.currentPosition ?: 0L },
    private val durationMs: () -> Long = { player?.duration?.takeIf { it != C.TIME_UNSET } ?: 0L },
    private val getSubtitleHeaders: (String) -> Map<String, String>,
    /** The fallback now shows [url] instead of the subtitle it started with (it fits the audio). */
    private val onSubtitleReplaced: (url: String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val controller = AudioSubtitleSyncController(
        context = appContext,
        // Subtitles are retimed in place, so the user's delay simply adds on top.
        manualDelayMs = { 0 },
        onStatus = ::onStatus,
        requestSwitch = ::replaceSubtitle,
    )

    /**
     * The subtitle being synced, with its original cues and the sidecar attachment it lives in.
     * [watching]: AutoSync already timed these cues; the audio only watches for a later drift.
     */
    private class Target(val url: String, val cues: List<CuesWithTiming>, val generation: Long, val watching: Boolean = false)

    /** The playhead as the ticker last saw it, for the reader thread. */
    @Volatile
    private var lastPositionMs = 0L

    /**
     * SAMplay TV 1.0.2: libVLC has no ExoPlayer pipeline to listen to, so the audio is read from
     * the stream itself (see [AudioSyncSourceReader]). Null on ExoPlayer.
     */
    private val reader: AudioSyncSourceReader?

    /** A mapping the audio confirmed was applied at least once in this session. */
    private var lockedOnce = false
    private var lastDriftApplyAtMs = 0L
    private var driftGate = newDriftGate()
    private val refineGate = newRefineGate()
    private var driftWaitLogged = false

    private val target = AtomicReference<Target?>(null)

    // Handed to the engine only once AutoSync starts on a subtitle, so a stream AutoSync syncs on
    // its own never loads the speech model or fetches references for the fallback.
    private var candidates: List<AudioSubtitleSyncController.ReferenceCandidate> = emptyList()
    private var content: Pair<String, String>? = null
    private var armed = false
    private var appliedModel: SubtitleSyncModel? = null
    private var ticker: Job? = null
    private var takeOverJob: Job? = null

    private val trackListener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            controller.onAudioTrackSelected(tracks.selectedAudioFormat())
        }
    }

    init {
        controller.listensBeforeSession = false
        controller.enabled = AudioSyncSettings.fallbackEnabled.value
        controller.samplingOnMobileData = AudioSyncSettings.samplingOnMobileData.value
        controller.onSourceChanged(sourceUrl)
        val audioUrl = sourceAudioUrl?.takeIf { it.isNotBlank() } ?: sourceUrl
        if (dataSourceFactory != null) {
            controller.setSpotSource(
                forSourceKey = sourceUrl,
                url = audioUrl,
                dataSourceFactory = dataSourceFactory,
                extractorsFactory = DefaultExtractorsFactory(),
                localEngine = isLoopback(audioUrl),
            )
        }
        // NuvioTV autosync.20: a 2 GB TV box has no room for the speech model beside the video, and
        // one extra connection is all it should open. Speech detection alone still syncs.
        if (SpotConnections.isLowMemoryTv(appContext)) {
            controller.recognitionAllowed = false
            controller.spotWorkers = 1
            Diag.log("autosync", "audio sync: small-memory device, speech model off, 1 sampling connection")
        }
        player?.addListener(trackListener)
        player?.let { controller.onAudioTrackSelected(it.currentTracks.selectedAudioFormat()) }
        reader = if (player == null && dataSourceFactory != null) {
            runCatching { AudioSyncSourceReader(Uri.parse(audioUrl), dataSourceFactory, controller) { lastPositionMs } }
                .onFailure { Diag.log("autosync", "audio reader unavailable: $it") }.getOrNull()
        } else null
        Diag.log("autosync", "audio sync ready: engine=${if (player == null) "vlc" else "exo"} reader=${reader != null} sampling=${dataSourceFactory != null}")
        AudioSyncTaps.attach(controller)
        // Turned off in Settings mid-playback: the subtitle goes back to AutoSync's (original) timing.
        scope.launch {
            AudioSyncSettings.fallbackEnabled.collect { enabled -> if (!enabled && target.get() != null) stop() }
        }
    }

    /** Listening or syncing right now. */
    val isActive: Boolean get() = ticker?.isActive == true || takeOverJob?.isActive == true

    /** Subtitles offered for this title: same-language alternatives and English references. */
    fun setCandidates(candidates: List<Triple<String, String, String?>>) {
        this.candidates = candidates.map { (url, language, name) ->
            AudioSubtitleSyncController.ReferenceCandidate(
                url = url,
                language = language,
                headers = getSubtitleHeaders(url),
                label = name.orEmpty(),
            )
        }
        if (armed) controller.setReferenceSubtitles(this.candidates)
    }

    /** The title being played, to look up English references beyond the addons' own. */
    fun setContent(type: String, videoId: String) {
        content = type to videoId
        if (armed) controller.setContent(type, videoId)
    }

    /** AutoSync started analysing a subtitle: listen meanwhile, so a takeover starts with audio. */
    fun arm() {
        stop()
        if (!AudioSyncSettings.fallbackEnabled.value) return
        controller.enabled = true
        controller.samplingOnMobileData = AudioSyncSettings.samplingOnMobileData.value
        controller.listensBeforeSession = true
        if (!armed) {
            armed = true
            content?.let { (type, videoId) -> controller.setContent(type, videoId) }
            controller.setReferenceSubtitles(candidates)
        }
        startTicker()
    }

    /** AutoSync applied its own result: nothing is left for the audio to do. */
    fun disarm() {
        stop()
    }

    /**
     * AutoSync kept [url]'s original timing, shown by the sidecar: sync it to the audio instead.
     * Waits briefly for the sidecar's cues, as AutoSync does before applying its own result.
     * Returns false when the fallback is off, so AutoSync reports the failure itself.
     */
    fun takeOver(url: String): Boolean {
        if (!AudioSyncSettings.fallbackEnabled.value || !controller.enabled) {
            disarm()
            return false
        }
        takeOverJob?.cancel()
        takeOverJob = scope.launch {
            var waitedMs = 0L
            while (sidecar.activeSidecarSubtitleKey == url && sidecar.sidecarTimedCues.isEmpty() && waitedMs < CUES_WAIT_MS) {
                delay(CUES_POLL_MS)
                waitedMs += CUES_POLL_MS
            }
            val generation = sidecar.currentGenerationFor(url)
            val cues = sidecar.sidecarTimedCues
            if (generation == null || cues.isEmpty()) {
                Diag.log("autosync", "audio takeover dropped: subtitle no longer attached (cues=${cues.size})")
                return@launch disarm()
            }
            target.set(Target(url, cues, generation))
            appliedModel = null
            resetDrift()
            Diag.log("autosync", "audio takeover: listening, ${cues.size} cues, engine=${if (player == null) "vlc" else "exo"}")
            startTicker()
            SyncLog.i("AutoSync kept the original timing of ${Diag.scrub(url)}; syncing it to the audio")
            controller.startSession(url, cues)
        }
        return true
    }

    /**
     * SAMplay TV 1.0.2: AutoSync timed [url] itself. Keep listening all the same, without moving
     * anything: when the audio later says, confirmed and for a while, that the subtitle has drifted
     * (another cut from some scene on, an offset that changes mid-episode), the new offset is
     * applied on its own. See [watch] for the conditions. Returns false when the fallback is off.
     */
    fun watchForDrift(url: String): Boolean {
        stop()
        if (!AudioSyncSettings.fallbackEnabled.value || !AudioSyncSettings.driftWatchEnabled.value) return false
        val generation = sidecar.currentGenerationFor(url) ?: return false
        val cues = sidecar.sidecarTimedCues
        if (cues.isEmpty()) return false
        controller.enabled = true
        controller.samplingOnMobileData = AudioSyncSettings.samplingOnMobileData.value
        controller.listensBeforeSession = false
        target.set(Target(url, cues, generation, watching = true))
        appliedModel = null
        resetDrift()
        startTicker()
        Diag.log("autosync", "drift watch on: ${cues.size} cues, engine=${if (player == null) "vlc" else "exo"}")
        // Its own key: what was remembered for this subtitle's original timing does not apply to
        // the cues AutoSync has retimed.
        controller.startSession(WATCH_KEY_PREFIX + url, cues)
        return true
    }

    private fun resetDrift() {
        lockedOnce = false
        lastDriftApplyAtMs = 0L
        driftGate = newDriftGate()
        driftWaitLogged = false
    }

    /** The user moved to another subtitle or track, or AutoSync starts over. */
    fun stop() {
        takeOverJob?.cancel()
        takeOverJob = null
        ticker?.cancel()
        ticker = null
        reader?.stop()
        // A watched subtitle keeps the timing it has: that is AutoSync's result, not the audio's.
        target.getAndSet(null)?.takeUnless { it.watching }?.let { previous ->
            AutoSyncSyncedSubtitle.clear()
            // Leave the subtitle on its original timing if it is still the one shown.
            sidecar.commitPreparedSidecarSubtitle(
                expectedCurrentUrl = previous.url,
                newUrl = previous.url,
                cues = previous.cues,
                expectedGeneration = previous.generation,
            )
        }
        appliedModel = null
        controller.listensBeforeSession = false
        controller.stopSession()
    }

    /**
     * SAMplay: the user set the delay by hand. Stop listening and adjusting, but keep the subtitle
     * exactly as it is on screen now ([stop] would put the original timing back): the user's
     * delay was chosen against what they see. Late model updates, a pending subtitle switch and
     * its "offset = 0" reset are all dropped because [target] is gone.
     */
    fun freeze() {
        takeOverJob?.cancel()
        takeOverJob = null
        ticker?.cancel()
        ticker = null
        reader?.stop()
        target.set(null)
        appliedModel = null
        controller.listensBeforeSession = false
        controller.stopSession()
    }

    fun release() {
        stop()
        player?.removeListener(trackListener)
        AudioSyncTaps.detach(controller)
        controller.release()
    }

    /** Feeds the playhead and applies each new mapping to the sidecar, on the main thread. */
    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            var lastStateLogAtMs = 0L
            while (isActive) {
                val position = runCatching(positionMs).getOrDefault(0L)
                // A seek (forward or back): what was being confirmed belongs to the old place; the
                // audio around the new playhead is checked again before anything moves.
                if (lastPositionMs > 0L && abs(position - lastPositionMs) > SEEK_JUMP_MS && target.get() != null) {
                    driftGate.reset()
                    Diag.log("autosync", "seek ${lastPositionMs / 1_000}s -> ${position / 1_000}s: checking the sync again there")
                }
                lastPositionMs = position
                controller.onPlaybackPosition(position, runCatching(durationMs).getOrDefault(0L).coerceAtLeast(0L))
                reader?.start()
                val current = target.get()
                val model = controller.currentModel()
                when {
                    current == null -> Unit
                    current.watching -> watch(current, model, position)
                    model !== appliedModel -> applyTakeover(current, model, position)
                }
                // One line a minute while it works, so a log tells where a sync that never came stopped.
                val now = SystemClock.elapsedRealtime()
                if (current != null && now - lastStateLogAtMs >= STATE_LOG_MS) {
                    lastStateLogAtMs = now
                    Diag.log("autosync", "audio ${if (current.watching) "watch" else "sync"}: pos=${position / 1_000}s heard=${controller.knownAudioSeconds()}s " +
                        "model=${if (model == null) "none" else if (controller.isEstimated) "estimate" else "confirmed"}" +
                        // The audio's own offset here and the one on screen: a watch whose two
                        // disagree for long is a drift on its way (or a noisy scene it waits out).
                        (model?.let { " audioOffset=${it.delayUsAt(position * 1_000L) / 1_000L}ms" } ?: "") +
                        " shown=${(appliedModel?.delayUsAt(position * 1_000L) ?: 0L) / 1_000L}ms" +
                        controller.recognitionRegions().let { if (it.isEmpty()) "" else " regions=[$it]" } +
                        (reader?.let { " reader=${if (it.failure != null) "failed(${it.failure})" else if (it.isRunning) "on" else "off"}" } ?: ""))
                }
                delay(TICK_MS)
            }
        }
    }

    /** The audio sync owns the timing: every new mapping is applied; a later big move waits its turn. */
    private suspend fun applyTakeover(current: Target, model: SubtitleSyncModel?, positionMs: Long) {
        val now = SystemClock.elapsedRealtime()
        val before = appliedModel
        val confirmed = model != null && !controller.isEstimated
        if (lockedOnce && confirmed && before != null && model != null) {
            val moveMs = (model.delayUsAt(positionMs * 1_000L) - before.delayUsAt(positionMs * 1_000L)) / 1_000L
            if (abs(moveMs) >= DRIFT_MIN_MS) {
                // A drift found mid-film: not more often than once per cool-down, so a noisy scene
                // cannot keep moving the subtitle.
                if (now - lastDriftApplyAtMs < DRIFT_COOLDOWN_MS) {
                    if (!driftWaitLogged) {
                        driftWaitLogged = true
                        Diag.log("autosync", "drift ${moveMs}ms found, waiting ${(DRIFT_COOLDOWN_MS - (now - lastDriftApplyAtMs)) / 1_000}s before the next correction")
                    }
                    return
                }
                lastDriftApplyAtMs = now
                driftWaitLogged = false
                Diag.log("autosync", "drift corrected: ${moveMs}ms at ${positionMs / 1_000}s (audio sync)")
            }
        }
        if (confirmed && !lockedOnce) {
            lockedOnce = true
            lastDriftApplyAtMs = now
        }
        appliedModel = model
        apply(current, model)
    }

    /**
     * Drift watch: the cues are AutoSync's own result and stay as they are until the audio is sure.
     * A correction needs a confirmed lock (never an early estimate), at least [DRIFT_MIN_MS] away
     * from what is shown, the same answer for [DRIFT_CONFIRM_MS], and [DRIFT_COOLDOWN_MS] since the
     * last correction.
     */
    private suspend fun watch(current: Target, model: SubtitleSyncModel?, positionMs: Long) {
        if (model == null || controller.isEstimated) {
            driftGate.reset()
            refineGate.reset()
            return
        }
        val shownMs = (appliedModel?.delayUsAt(positionMs * 1_000L) ?: 0L) / 1_000L
        val driftMs = model.delayUsAt(positionMs * 1_000L) / 1_000L - shownMs
        if (!lockedOnce) {
            lockedOnce = true
            Diag.log("autosync", "drift watch: audio locked, ${driftMs}ms from what is shown at ${positionMs / 1_000}s")
        }
        val now = SystemClock.elapsedRealtime()
        val precise = controller.isMaintainedByRecognition || rateDiffers(model, appliedModel, positionMs)
        if (refineMapping(current, model, positionMs, driftMs, now, precise)) {
            driftGate.reset()
            return
        }
        when (driftGate.offer(driftMs, now, precise = precise)) {
            DriftGate.Decision.NONE, DriftGate.Decision.CONFIRMING -> return
            DriftGate.Decision.SUSPECTED -> {
                driftWaitLogged = false
                Diag.log("autosync", "drift suspected: ${driftMs}ms at ${positionMs / 1_000}s, confirming")
                return
            }
            DriftGate.Decision.COOLING_DOWN -> {
                if (!driftWaitLogged) {
                    driftWaitLogged = true
                    Diag.log("autosync", "drift ${driftMs}ms confirmed, waiting ${driftGate.cooldownLeftMs(now) / 1_000}s before the next correction")
                }
                return
            }
            DriftGate.Decision.APPLY -> Unit
        }
        lastDriftApplyAtMs = now
        driftWaitLogged = false
        refineGate.reset()
        appliedModel = model
        Diag.log("autosync", "drift corrected: ${driftMs}ms at ${positionMs / 1_000}s (was synced by AutoSync)")
        apply(current, model)
        if (target.get() === current) {
            AutoSyncSyncedSubtitle.mark(current.url)
            val text = getString(Res.string.player_audio_sync_adjusted, formatOffset(driftMs))
            showAutoSyncMessage(appContext, AutoSyncBubbleKind.Success, text)
        }
    }

    /**
     * SAMplay 2.0.3: the audio's mapping places the lines elsewhere differently from what is shown
     * (a step it now places more exactly, or one it already knows a little ahead): taken once
     * [refineGate] is sure. Where it agrees with what is shown here nothing on screen moves and no
     * message shows; those lines are right when the film or a rewind gets to them. Before, a step
     * found ahead was only corrected after the film passed it and the drift had been confirmed, and
     * one placed too early or late stayed there. True when it was taken.
     */
    private suspend fun refineMapping(
        current: Target,
        model: SubtitleSyncModel,
        positionMs: Long,
        driftMs: Long,
        now: Long,
        precise: Boolean,
    ): Boolean {
        val shown = appliedModel ?: SubtitleSyncModel.UNCHANGED
        if (!refineGate.offer(model, shown, driftMs, now, precise)) return false
        val (changeMs, atMs) = model.largestDifferenceMs(shown)
        appliedModel = model
        Diag.log("autosync", "drift watch: mapping refined: up to ${changeMs}ms near ${atMs / 1_000}s, ${driftMs}ms at ${positionMs / 1_000}s")
        apply(current, model)
        if (target.get() === current) {
            AutoSyncSyncedSubtitle.mark(current.url)
            // The line on screen moved (a step placed better that the film is in): say so, as a
            // drift correction does.
            if (abs(driftMs) >= DRIFT_MIN_MS) {
                showAutoSyncMessage(appContext, AutoSyncBubbleKind.Success, getString(Res.string.player_audio_sync_adjusted, formatOffset(driftMs)))
            }
        }
        return true
    }

    private suspend fun apply(current: Target, model: SubtitleSyncModel?) {
        val retimed = withContext(Dispatchers.Default) { retime(current.cues, model) }
        if (target.get() !== current) return
        val committed = sidecar.commitPreparedSidecarSubtitle(
            expectedCurrentUrl = current.url,
            newUrl = current.url,
            cues = retimed,
            expectedGeneration = current.generation,
        )
        // The sidecar moved on (another subtitle, or it stopped): so does the fallback.
        if (!committed) {
            Diag.log("autosync", "audio result not applied: the subtitle on screen changed")
            stop()
        }
    }

    /** Automatic switch to another subtitle that fits the audio, as AutoSync replaces a subtitle. */
    private fun replaceSubtitle(url: String) {
        scope.launch {
            // While only watching for drift the subtitle AutoSync chose is never swapped.
            val current = target.get()?.takeUnless { it.watching } ?: return@launch
            val cues = withContext(Dispatchers.IO) {
                runCatching {
                    parseSidecarTimedCuesRobust(httpGetTextWithHeaders(url = url, headers = getSubtitleHeaders(url)), url).cues
                }.getOrDefault(emptyList())
            }
            if (cues.isEmpty() || target.get() !== current) return@launch
            val committed = sidecar.commitPreparedSidecarSubtitle(
                expectedCurrentUrl = current.url,
                newUrl = url,
                cues = cues,
                expectedGeneration = current.generation,
            )
            val generation = sidecar.currentGenerationFor(url)
            if (!committed || generation == null) return@launch
            AutoSyncSyncedSubtitle.clear()
            target.set(Target(url, cues, generation))
            appliedModel = null
            onSubtitleReplaced(url)
            // The new session adopts the mapping the switch was decided with.
            controller.startSession(url, cues)
        }
    }

    /** Keeps the subtitle list's "Auto synced" chip in step with the audio sync's result. */
    private fun onStatus(status: AudioSyncStatus) {
        val watching = target.get()?.watching == true
        Diag.log("autosync", "audio${if (watching) " (watch)" else ""}: $status")
        // The drift watch speaks only when it corrects something (see [watch]).
        if (watching) return
        when (status) {
            is AudioSyncStatus.Synced, is AudioSyncStatus.Adjusted ->
                target.get()?.let { AutoSyncSyncedSubtitle.mark(it.url) }
            AudioSyncStatus.Withdrawn -> AutoSyncSyncedSubtitle.clear()
            else -> Unit
        }
        toast(status)
    }

    private fun toast(status: AudioSyncStatus) {
        scope.launch {
            val message = status.message() ?: return@launch
            // The speech model keeps loading after a stop; its news must not come back on screen.
            if (ticker?.isActive != true) return@launch
            showAutoSyncMessage(appContext, status.bubbleKind(), message)
        }
    }

    companion object {
        private const val TICK_MS = 250L
        private const val STATE_LOG_MS = 60_000L
        /** A playhead move this much bigger than one tick is a seek. */
        private const val SEEK_JUMP_MS = 3_000L
        private const val WATCH_KEY_PREFIX = "watch:"

        /** A drift smaller than this is not worth moving a subtitle for. */
        const val DRIFT_MIN_MS = 400L
        /** Two readings of a drift this close are the same drift. */
        const val DRIFT_AGREE_MS = 250L
        /**
         * How long the audio must keep saying the same before the drift watch acts. SAMplay 2.0.3:
         * 8 s (was 12): the answer must still agree within [DRIFT_AGREE_MS] all that time.
         */
        const val DRIFT_CONFIRM_MS = 8_000L
        /** The wait between two automatic corrections (SAMplay 2.0.3: 30 s, was 60). */
        const val DRIFT_COOLDOWN_MS = 30_000L
        /** SAMplay 2.0.4: with recognised words, a drift this small is moved too, after a longer wait. */
        const val DRIFT_PRECISE_MIN_MS = 150L
        const val DRIFT_PRECISE_CONFIRM_MS = 20_000L
        /** A better mapping is taken quietly only where it moves the line on screen less than this. */
        const val REFINE_AT_PLAYHEAD_MS = 120L
        /** ...once the place it changes most stays within this... */
        const val REFINE_PLACE_MS = 2_000L
        /** ...for this long... */
        const val REFINE_CONFIRM_MS = 4_000L
        /** ...and not more often than this. */
        const val REFINE_GAP_MS = 15_000L

        /**
         * SAMplay 2.0.4: the audio's mapping runs at another speed than what is shown (a frame-rate
         * difference such as 23.976 against 24 fps), so the gap grows steadily: 60 ms a minute on
         * Z Nation 3x2, which left the subtitle up to 400 ms off for six minutes before it was moved.
         * Such a mapping is moved to from [DRIFT_PRECISE_MIN_MS], like one from recognised words.
         */
        internal fun rateDiffers(model: SubtitleSyncModel, shown: SubtitleSyncModel?, positionMs: Long): Boolean =
            abs(model.segmentAt(positionMs).scale - (shown?.segmentAt(positionMs)?.scale ?: 1.0)) >= RATE_DIFFERENCE
        private const val RATE_DIFFERENCE = 0.0005

        internal fun newDriftGate() = DriftGate(
            DRIFT_MIN_MS, DRIFT_AGREE_MS, DRIFT_CONFIRM_MS, DRIFT_COOLDOWN_MS,
            preciseMinMs = DRIFT_PRECISE_MIN_MS, preciseConfirmMs = DRIFT_PRECISE_CONFIRM_MS,
        )
        internal fun newRefineGate() =
            RefineGate(
                REFINE_AT_PLAYHEAD_MS, DRIFT_MIN_MS, DRIFT_AGREE_MS, REFINE_PLACE_MS, REFINE_CONFIRM_MS, REFINE_GAP_MS,
                preciseMinChangeMs = DRIFT_PRECISE_MIN_MS,
            )
        private const val CUES_WAIT_MS = 5_000L
        private const val CUES_POLL_MS = 50L

        /** Registers settings persistence, the speech model and the log. Call once at app start. */
        fun initialize(context: Context) {
            val preferences = context.getSharedPreferences("nuvio_audio_sync_settings", Context.MODE_PRIVATE)
            AudioSyncSettings.installPersistence(
                load = { key -> if (preferences.contains(key)) preferences.getBoolean(key, false) else null },
                save = { key, value -> preferences.edit().putBoolean(key, value).apply() },
            )
            AsrModel.initialize(context)
            SyncLog.initialize(context)
        }

        private fun isLoopback(url: String): Boolean = runCatching {
            val host = Uri.parse(url).host.orEmpty().lowercase()
            host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"
        }.getOrDefault(false)

        /**
         * [cues] placed on the media timeline by [model] (subtitle time -> media time); the
         * original cues when there is no mapping. Lines of a scene the release cuts are dropped,
         * as a subtitle delay would never show them either.
         */
        internal fun retime(cues: List<CuesWithTiming>, model: SubtitleSyncModel?): List<CuesWithTiming> {
            if (model == null) return cues
            return cues.mapNotNull { entry ->
                if (entry.startTimeUs == C.TIME_UNSET) return@mapNotNull entry
                val startUs = model.mediaTimeUs(entry.startTimeUs) ?: return@mapNotNull null
                val durationUs = when {
                    entry.durationUs != C.TIME_UNSET -> entry.durationUs
                    entry.endTimeUs != C.TIME_UNSET -> entry.endTimeUs - entry.startTimeUs
                    else -> C.TIME_UNSET
                }
                val scaledUs = if (durationUs == C.TIME_UNSET) durationUs else (durationUs * model.segmentAt(startUs / 1_000L).scale).toLong()
                CuesWithTiming(entry.cues, startUs, scaledUs)
            }.sortedBy { it.startTimeUs }
        }
    }
}

/**
 * Routes audio the player demuxes or plays to the fallback of the stream being played. The
 * player's extractors and audio output are created before the fallback, so they look it up here.
 */
internal object AudioSyncTaps {
    @Volatile
    private var active: AudioSubtitleSyncController? = null

    fun attach(controller: AudioSubtitleSyncController) {
        active = controller
    }

    fun detach(controller: AudioSubtitleSyncController) {
        if (active === controller) active = null
    }

    /** Copies the audio of [sourceKey]'s stream while it is demuxed (look-ahead). */
    fun wrapExtractors(factory: ExtractorsFactory, sourceKey: String): ExtractorsFactory =
        AudioSyncExtractorsFactory(factory, object : AudioSampleSink {
            private fun current() = active?.takeIf { it.currentSourceKey == sourceKey }

            override fun wantsSamples(format: Format): Boolean = current()?.wantsSamples(format) == true

            override fun onSample(format: Format, timeUs: Long, data: ByteArray, offset: Int, size: Int) {
                current()?.onSample(format, timeUs, data, offset, size)
            }

            override fun onDiscontinuity() {
                current()?.onDiscontinuity()
            }
        })

    /** Hears the player's own decoded audio where the look-ahead copy can't be decoded. */
    fun wrapAudioSink(sink: AudioSink): AudioSink = PlaybackAudioTap(sink, object : PlaybackPcmListener {
        override fun wantsPlaybackPcm(mediaTimeUs: Long, durationUs: Long): Boolean =
            active?.wantsPlaybackPcm(mediaTimeUs, durationUs) == true

        override fun onPlaybackPcm(mono: FloatArray, frames: Int, sampleRate: Int, mediaTimeUs: Long) {
            active?.onPlaybackPcm(mono, frames, sampleRate, mediaTimeUs)
        }
    })
}

private suspend fun AudioSyncStatus.message(): String? = when (this) {
    AudioSyncStatus.Listening -> null // AutoSync's takeover toast already said so
    AudioSyncStatus.Withdrawn -> getString(Res.string.player_audio_sync_withdrawn)
    is AudioSyncStatus.ModelDownloading -> getString(Res.string.player_audio_sync_model_downloading, megabytes)
    is AudioSyncStatus.ModelNeedsWifi -> getString(Res.string.player_audio_sync_model_needs_wifi, megabytes)
    is AudioSyncStatus.LiveOnly -> getString(
        Res.string.player_audio_sync_live_only,
        mimeType.substringAfter('/').uppercase(),
    )
    is AudioSyncStatus.Estimated -> getString(Res.string.player_audio_sync_estimated, formatOffset(offsetMs))
    is AudioSyncStatus.Synced -> getString(
        if (rateCorrected) Res.string.player_audio_sync_synced_rate else Res.string.player_audio_sync_synced,
        formatOffset(offsetMs),
    )
    is AudioSyncStatus.Adjusted -> getString(Res.string.player_audio_sync_adjusted, formatOffset(offsetMs))
}

/** How the AutoSync bubble shows [this]: the audio sync is still at it, done, or gave up. */
private fun AudioSyncStatus.bubbleKind(): AutoSyncBubbleKind = when (this) {
    is AudioSyncStatus.Synced, is AudioSyncStatus.Adjusted -> AutoSyncBubbleKind.Success
    AudioSyncStatus.Withdrawn -> AutoSyncBubbleKind.Failure
    else -> AutoSyncBubbleKind.Working
}

private fun formatOffset(offsetMs: Long): String {
    val sign = if (offsetMs < 0) "-" else "+"
    val tenths = (abs(offsetMs) + 50) / 100
    return "$sign${tenths / 10}.${tenths % 10}s"
}
