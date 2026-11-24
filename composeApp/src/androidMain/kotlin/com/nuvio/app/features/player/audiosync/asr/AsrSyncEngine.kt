package com.nuvio.app.features.player.audiosync.asr

import com.nuvio.app.features.player.audiosync.AudioSyncTracker
import com.nuvio.app.features.player.audiosync.SileroVad
import com.nuvio.app.features.player.audiosync.SpeechTimeline
import com.nuvio.app.features.player.audiosync.SubtitleAudioAligner
import com.nuvio.app.features.player.audiosync.SubtitleSpeechTrack
import com.nuvio.app.features.player.audiosync.SubtitleSyncSegment
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.roundToInt

/** Speech recogniser returning (seconds from segment start, word) pairs. */
internal fun interface SpeechToText {
    fun transcribe(samples: FloatArray): List<Pair<Double, String>>
}

/** A same-language (English) subtitle the heard words can be matched against. */
internal class ReferenceSubtitle(
    val key: String,
    cues: List<Triple<Long, Long, String>>,
    /** How the target subtitle maps onto this one; null when the reference is the target itself. */
    val bridge: BridgeFit?,
) {
    val matcher = WordAnchorMatcher(cues)
    private val track = SubtitleSpeechTrack.fromCues(cues)
    private val localShifts = HashMap<Pair<SubtitleSpeechTrack, Int>, Double?>()

    /**
     * The [bridge] shift around [referenceSec] (see [SubtitleBridge.localShiftSec]), falling back
     * to the whole-file one. Kept per [LOCAL_STEP_SEC] of reference time, as regions recur.
     */
    fun bridgeShiftSecAt(target: SubtitleSpeechTrack, referenceSec: Double): Double {
        val bridge = bridge ?: return 0.0
        val step = (referenceSec / LOCAL_STEP_SEC).roundToInt()
        val local = synchronized(localShifts) {
            localShifts.getOrPut(target to step) {
                SubtitleBridge.localShiftSec(target, track, bridge, step * LOCAL_STEP_SEC)
            }
        }
        return local ?: bridge.shiftSec
    }

    private companion object {
        const val LOCAL_STEP_SEC = 5.0
    }
}

/** Target subtitle mapping found from recognised words. */
internal data class AsrLock(
    val scale: Double,
    val shiftMs: Double,
    val referenceKey: String,
    val anchorScore: Double,
    val fineTuned: Boolean,
    /** False while the frame rate is still assumed (short span, no bridge); an update may follow. */
    val final: Boolean,
    /**
     * The full mapping, in media order: one segment normally, several when the release and the
     * subtitle differ by an inserted or removed scene (a different offset from some point on).
     */
    val segments: List<SubtitleSyncSegment> = listOf(SubtitleSyncSegment(0L, scale, shiftMs)),
)

/**
 * Layer 2 of the audio sync: recognises English words in the dialogue and pins subtitles to them.
 *
 * Speech segments arrive from [SpeechSegmenter]s (look-ahead and live), are transcribed on one
 * low-priority worker, and every recognised word is kept for the whole stream, so switching
 * subtitles re-uses what was already heard. Each reference subtitle is matched against the words;
 * the first confident match is turned into a target mapping (through the timing bridge for
 * translated subtitles) and fine-tuned against the speech timeline.
 */
internal class AsrSyncEngine(
    private val timeline: SpeechTimeline,
    private val onLock: (AsrLock) -> Unit,
    private val log: (String) -> Unit = {},
    /** Runs on the worker thread before it starts (e.g. to lower its priority). */
    private val workerSetup: () -> Unit = {},
) {
    /** [spread]: sampled away from the playhead, recognised first (see [nextSegment]). */
    private class Segment(val startFrame: Int, val samples: FloatArray, val spread: Boolean) {
        val endFrame: Int get() = startFrame + samples.size / SileroVad.CHUNK_SAMPLES
    }

    private val lock = Object()

    @Volatile
    private var playheadFrame = 0

    /** Nearest upcoming speech first: that is what will be on screen soonest. */
    private val queue = PriorityQueue<Segment>(compareBy { segmentPriority(it) })
    private var queuedSamples = 0L
    private val covered = ArrayList<IntRange>()
    private val heard = ArrayList<HeardWord>()
    private var segmentCounter = 0

    @Volatile
    private var target: SubtitleSpeechTrack? = null

    @Volatile
    private var references: List<ReferenceSubtitle> = emptyList()

    /** A final lock was reported; from then on only speech near the playhead is recognised. */
    @Volatile
    private var locked = false

    /** The reference-to-video ratio [mostLikelyRate] chose, for how many words and in what situation. */
    private class ChosenRate(val situation: String, val words: Int, val rate: Double)

    @Volatile
    private var chosenRate: ChosenRate? = null

    /** Last reported (not yet final) lock, to report only real changes. */
    private var provisional: AsrLock? = null

    @Volatile
    private var stt: SpeechToText? = null

    @Volatile
    private var released = false
    private var worker: Thread? = null

    /** False while recognition cannot help (no recogniser, no usable reference, foreign audio). */
    @Volatile
    private var listening = true

    /** See [setWordsUsedElsewhere]. */
    @Volatile
    private var wordsUsedElsewhere = false

    /** Heard words so far; exposed for diagnostics. */
    val heardWordCount: Int get() = synchronized(lock) { heard.size }

    /**
     * SAMplay 2.0.4, for the log: the last regions of the film the words placed, as
     * "media s:offset s(word segments)", so a step that was never found shows why.
     */
    @Volatile
    var regionsSummary: String = ""
        private set

    /** Snapshot of every word heard so far, in time order. */
    fun heardWords(): List<HeardWord> = synchronized(lock) { heard.toList() }

    fun setRecognizer(recognizer: SpeechToText?) {
        stt = recognizer
        synchronized(lock) { lock.notifyAll() }
    }

    /**
     * Whether speech is worth keeping for recognition. Off, nothing is queued (queued speech is
     * dropped), so a stream that can never be recognised holds no audio; words already heard stay.
     */
    fun setListening(on: Boolean) {
        synchronized(lock) {
            if (listening == on) return
            listening = on
            if (!on) {
                queue.clear()
                queuedSamples = 0
            }
            lock.notifyAll()
        }
    }

    /**
     * Nuvio RS: heard words also serve the check of the other subtitle files
     * ([com.nuvio.app.features.player.audiosync.SubtitleCandidatePool]), which places an English
     * file by its words even when no whole-file bridge to the chosen subtitle was found. While on,
     * speech is recognised without a reference of this engine's own.
     */
    fun setWordsUsedElsewhere(on: Boolean) {
        synchronized(lock) {
            if (wordsUsedElsewhere == on) return
            wordsUsedElsewhere = on
            lock.notifyAll()
        }
    }

    fun onPlayhead(positionMs: Long) {
        playheadFrame = (positionMs / SpeechTimeline.FRAME_DURATION_MS).toInt()
    }

    /**
     * Queues a speech segment for recognition. [spread] marks speech sampled across the film: words
     * from far-apart places pin a reference fastest, so it goes first.
     */
    fun offerSegment(startFrame: Int, samples: FloatArray, spread: Boolean = false) {
        val segment = Segment(startFrame, samples, spread)
        synchronized(lock) {
            if (released || !listening) return
            val range = segment.startFrame until segment.endFrame
            // The look-ahead and live paths can deliver the same stretch; skip what is mostly known.
            val overlap = covered.sumOf { overlap(it, range) } + queue.sumOf { overlap(it.startFrame until it.endFrame, range) }
            if (overlap * 2 > range.count()) return
            while (queuedSamples + samples.size > MAX_QUEUED_SAMPLES && queue.isNotEmpty()) {
                val dropped = queue.maxByOrNull(::segmentPriority) ?: break
                queue.remove(dropped)
                queuedSamples -= dropped.samples.size
            }
            queue.add(segment)
            queuedSamples += samples.size
            ensureWorker()
            lock.notifyAll()
        }
    }

    /** Starts matching for a new target subtitle; previously heard words are kept. */
    fun startSession(targetTrack: SubtitleSpeechTrack, referenceSubtitles: List<ReferenceSubtitle>) {
        synchronized(lock) {
            target = targetTrack
            references = referenceSubtitles
            locked = false
            provisional = null
            chosenReferenceKey = null
            lock.notifyAll()
        }
        evaluate()
    }

    /** Adds a reference that finished downloading after the session started. */
    fun addReference(reference: ReferenceSubtitle) {
        synchronized(lock) {
            if (references.any { it.key == reference.key }) return
            references = references + reference
            lock.notifyAll()
        }
        evaluate()
    }

    fun stopSession() {
        synchronized(lock) {
            target = null
            references = emptyList()
            locked = false
            provisional = null
        }
    }

    /** New stream: forget everything that was heard. */
    fun clear() {
        synchronized(lock) {
            queue.clear()
            queuedSamples = 0
            covered.clear()
            heard.clear()
            locked = false
            provisional = null
        }
    }

    fun release() {
        synchronized(lock) {
            released = true
            queue.clear()
            lock.notifyAll()
        }
    }

    private fun segmentPriority(segment: Segment): Long {
        if (segment.spread) return SPREAD_PRIORITY
        val distance = segment.startFrame - playheadFrame
        // Upcoming speech first (nearest first), then the most recent past speech.
        return if (distance >= -BEHIND_GRACE_FRAMES) distance.toLong() else 1_000_000L - distance
    }

    /**
     * The next segment to recognise. Sampled speech first, taking the place with the fewest
     * recognised segments around it, so words arrive from every sampled place early; then the
     * nearest upcoming speech. Call with [lock] held and the queue not empty.
     */
    private fun nextSegment(): Segment {
        if (queue.peek()?.spread != true) return queue.poll()
        val next = queue.filter { it.spread }.minWith(
            compareBy<Segment>({ segment -> covered.count { abs(it.first - segment.startFrame) <= SPREAD_RADIUS_FRAMES } })
                .thenBy { it.startFrame },
        )
        queue.remove(next)
        return next
    }

    private fun ensureWorker() {
        if (worker != null) return
        worker = Thread({ runWorker() }, "NuvioAsrSync").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    private fun runWorker() {
        runCatching(workerSetup)
        while (true) {
            val (segment, recognizer) = synchronized(lock) {
                // Nothing to match words against yet: recognising now would be wasted work.
                while (
                    !released &&
                    (queue.isEmpty() || stt == null || target == null || (references.isEmpty() && !wordsUsedElsewhere))
                ) {
                    lock.wait()
                }
                if (released) return
                nextSegment().also { queuedSamples -= it.samples.size } to stt!!
            }
            // Once synced, keep listening just ahead of the playhead: a scene the release adds or
            // cuts shows up as a new offset there before it is on screen.
            if (locked && !segment.spread && !nearPlayhead(segment)) continue
            val words = try {
                recognizer.transcribe(segment.samples)
            } catch (error: Throwable) {
                log("recognition failed: ${error.message}")
                emptyList()
            }
            val startSec = segment.startFrame * SpeechTimeline.FRAME_DURATION_MS / 1_000.0
            synchronized(lock) {
                covered += segment.startFrame until segment.endFrame
                val id = segmentCounter++
                words.forEach { (offsetSec, text) -> heard += HeardWord(startSec + offsetSec, text, id) }
                heard.sortBy { it.timeSec }
            }
            evaluate()
        }
    }

    private fun nearPlayhead(segment: Segment): Boolean {
        val distance = segment.startFrame - playheadFrame
        return distance >= -BEHIND_GRACE_FRAMES && distance <= MAINTAIN_AHEAD_FRAMES
    }

    private fun evaluate() {
        val (words, refs, targetTrack) = synchronized(lock) {
            Triple(heard.toList(), references, target ?: return)
        }
        if (words.isEmpty()) return
        var best: Pair<ReferenceSubtitle, AnchorFit>? = null
        var bestLocals: List<AnchorFit> = emptyList()
        var kept: Triple<ReferenceSubtitle, AnchorFit, List<AnchorFit>>? = null
        for (reference in refs) {
            val fit = reference.matcher.fit(words) ?: continue
            val locals = reference.matcher.localFits(words, fit.scale)
            // A scene the release adds splits the words into two strong clusters, so one fit over
            // all words looks ambiguous; regions that are each confident explain it.
            if (!fit.isConfident && !explainsSteps(locals)) continue
            if (reference.key == chosenReferenceKey) kept = Triple(reference, fit, locals)
            if (best == null || fit.score > best.second.score) {
                best = reference to fit
                bestLocals = locals
            }
        }
        // SAMplay 2.0.4: the reference chosen last time stays while it scores nearly as well as the
        // best. References a little apart in frame rate (x0.999 and x1 against the subtitle) took
        // turns every few words, and each turn moved the whole mapping (Z Nation 3x8).
        val topScore = best?.second?.score
        if (kept != null && topScore != null && kept.second.score >= topScore * (1.0 - REFERENCE_SWITCH_MARGIN)) {
            best = kept.first to kept.second
            bestLocals = kept.third
        }
        val (reference, fit) = best ?: return
        chosenReferenceKey = reference.key
        val bridge = reference.bridge
        // Words over a short stretch pin where the reference is, not its frame rate relative to the
        // video: that is only known from a long span of words (or the words chose another rate).
        val localSpanSec = if (bestLocals.size >= 3) bestLocals.last().anchorSec - bestLocals.first().anchorSec else 0.0
        val rateKnown = fit.spanSec >= FINAL_SPAN_SEC || fit.scale != 1.0 || localSpanSec >= FINAL_SPAN_SEC
        val (scale, coarseShiftMs, fine) = if (rateKnown) {
            // Compose target -> reference -> media.
            val scale = fit.scale * (bridge?.scale ?: 1.0)
            val coarseShiftMs = (fit.scale * (bridge?.shiftSec ?: 0.0) + fit.shiftSec) * 1_000.0
            Triple(scale, coarseShiftMs, fineTune(targetTrack, scale, coarseShiftMs))
        } else {
            mostLikelyRate(targetTrack, words, fit, bridge)
        }
        val trustedFine = fine?.takeIf { isTrustedMove(it, coarseShiftMs) }
        val shiftMs = trustedFine?.shiftMs ?: coarseShiftMs
        val segments = piecewise(targetTrack, words, reference, fit, scale, shiftMs)
            ?: listOf(SubtitleSyncSegment(0L, scale, shiftMs))
        val evidence = bestLocals.map { it.anchorSec to targetShiftMs(targetTrack, reference, it, scale) }
        regionsSummary = bestLocals.zip(evidence).takeLast(REGIONS_LOGGED).joinToString(" ") { (local, placed) ->
            "${local.anchorSec.roundToInt()}s:${"%.1f".format(java.util.Locale.ROOT, placed.second / 1_000.0)}s(${local.segments})"
        }
        val result = AsrLock(
            scale = scale,
            shiftMs = segments.first().shiftMs,
            referenceKey = reference.key,
            anchorScore = fit.score,
            fineTuned = trustedFine != null,
            final = rateKnown || locked,
            segments = segments,
        )
        var keptStep: SubtitleSyncSegment? = null
        synchronized(lock) {
            if (target !== targetTrack) return
            val previous = provisional
            val tolerance = if (previous?.final == true) SETTLED_UPDATE_MIN_MS else UPDATE_MIN_MS
            if (previous != null && previous.final == result.final && sameMapping(previous, result, tolerance)) return
            // SAMplay 2.0.6: a settled mapping is not redrawn for what is already behind the playhead,
            // nor back to a mapping it already left. Each few words moved the step a little (1989 s,
            // 2003 s, 2031 s) or added and dropped a 0.3 s step, and every redraw moved the subtitles
            // back and forth against the drift watch's correction (Z Nation 3x7, minute 34).
            if (previous != null && previous.final && result.final && previous.referenceKey == result.referenceKey) {
                val playheadMs = (playheadFrame * SpeechTimeline.FRAME_DURATION_MS).toLong()
                if (placesAlikeAhead(previous.segments, result.segments, playheadMs, UPDATE_MIN_MS)) return
                if (placesAlikeAhead(previous.segments, result.segments, playheadMs, FLIP_MAX_MS) &&
                    appliedLocks.any {
                        it !== previous && it.referenceKey == result.referenceKey &&
                            placesAlikeAhead(it.segments, result.segments, playheadMs, UPDATE_MIN_MS)
                    }
                ) return
                // On the phone the steps in 3x7 changed by 0.42 s at a time, just past the flip range: a
                // settled mapping changes only where the words heard there place the subtitles better.
                if (!fitsWordsBetterAhead(previous.segments, result.segments, evidence, bestLocals.map { it.segments }, scale, playheadMs)) return
            }
            if (previous != null && previous.final && result.final && previous.referenceKey == result.referenceKey &&
                keepsLaterSteps(previous.segments, result.segments, evidence)
            ) {
                keptStep = previous.segments[result.segments.size]
            } else {
                if (result.final) locked = true
                provisional = result
                appliedLocks.addLast(result)
                while (appliedLocks.size > APPLIED_LOCKS_KEPT) appliedLocks.removeFirst()
            }
        }
        keptStep?.let { step ->
            if (step != lastKeptStep) {
                lastKeptStep = step
                log("step at ${step.fromMediaMs / 1_000}s kept: no words after it say otherwise (regions $regionsSummary)")
            }
            return
        }
        log(
            "words=${words.size} reference=${reference.key} fit=$fit bridge=$bridge " +
                "coarse=${coarseShiftMs.toLong()}ms fine=${fine?.shiftMs?.toLong()} rateKnown=$rateKnown",
        )
        onLock(result)
    }

    /** SAMplay 2.0.4: the reference [evaluate] chose last; another must score clearly better to replace it. */
    @Volatile
    private var chosenReferenceKey: String? = null

    /** SAMplay 2.0.6: the last mappings [evaluate] applied (under [lock]), to tell a flip back. */
    private val appliedLocks = ArrayDeque<AsrLock>()

    /** The step [evaluate] last kept (logged once). */
    @Volatile
    private var lastKeptStep: SubtitleSyncSegment? = null

    /**
     * Target -> media shift (ms) that the words of [local] imply at [scale], through its anchor. The
     * target is placed against the reference by the lines around there, not the whole file.
     */
    private fun targetShiftMs(track: SubtitleSpeechTrack, reference: ReferenceSubtitle, local: AnchorFit, scale: Double): Double {
        val bridgeScale = reference.bridge?.scale ?: 1.0
        val referenceSec = (local.anchorSec - local.shiftSec) / local.scale
        val targetSec = (referenceSec - reference.bridgeShiftSecAt(track, referenceSec)) / bridgeScale
        return (local.anchorSec - scale * targetSec) * 1_000.0
    }

    /** Confident regions in at least two places, with enough evidence to trust a step. */
    private fun explainsSteps(locals: List<AnchorFit>): Boolean =
        locals.size >= 2 && locals.sumOf { it.segments } >= 2 * MIN_PART_SEGMENTS

    /** Whether two results would place subtitles the same (within [toleranceMs] everywhere). */
    private fun sameMapping(a: AsrLock, b: AsrLock, toleranceMs: Double): Boolean {
        if (a.segments.size != b.segments.size) return false
        return a.segments.zip(b.segments).all { (x, y) ->
            x.scale == y.scale && abs(x.shiftMs - y.shiftMs) < toleranceMs &&
                abs(x.fromMediaMs - y.fromMediaMs) < CHANGE_POINT_TOLERANCE_MS
        }
    }

    /**
     * When the words in different parts of the film put the subtitle at clearly different offsets
     * (the release adds or cuts a scene the subtitle was not made for, or the subtitle and the
     * reference were made for different cuts), maps each part with its own offset, switching where
     * the detected speech shows the change. Null when the whole-film mapping ([shiftMs]) fits
     * everywhere the words were heard.
     */
    private fun piecewise(
        track: SubtitleSpeechTrack,
        words: List<HeardWord>,
        reference: ReferenceSubtitle,
        fit: AnchorFit,
        scale: Double,
        shiftMs: Double,
    ): List<SubtitleSyncSegment>? {
        val confidentLocals = reference.matcher.localFits(words, fit.scale)
        if (confidentLocals.isEmpty()) return null

        // Target -> media shift a fit implies at the chosen scale, through its anchor. The target
        // is placed against the reference by the lines around there, not the whole file.
        fun impliedShiftMs(local: AnchorFit): Double = targetShiftMs(track, reference, local, scale)
        // Words from several far-apart places (short sampled spots) can agree on an offset while
        // few of the places are confident alone. That agreement is a part of its own, over the
        // stretch its words come from (standing for the confident regions there that agree with
        // it); otherwise one strong region (say the opening, heard in full) would decide the whole
        // film. Not when a confident region inside that stretch says otherwise.
        val spread = reference.matcher.fitByPlace(words, fit.scale)?.takeIf { spread ->
            val shift = impliedShiftMs(spread)
            val inside = confidentLocals.filter { it.anchorSec in spread.firstSec..spread.lastSec }
            spread.segments >= MIN_PART_SEGMENTS && spread.spanSec >= SPREAD_MIN_SPAN_SEC &&
                inside.none { abs(impliedShiftMs(it) - shift) > STEP_TOLERANCE_MS } &&
                // SAMplay 2.0.4: nor when the confident regions there already show the offset moving
                // a little at a time: one fit over all of them would average those levels away
                // (a slow drift became one offset 0.36 s off at 200 s).
                driftLevels(inside.map(::impliedShiftMs), inside.map { it.segments }, inside.map { it.anchorSec }).size < 2
        }
        val locals = if (spread == null) {
            confidentLocals
        } else {
            (confidentLocals.filter { it.anchorSec !in spread.firstSec..spread.lastSec } + spread).sortedBy { it.anchorSec }
        }
        val targetShifts = locals.associateWith(::impliedShiftMs)
        fun targetShiftMs(local: AnchorFit): Double = targetShifts.getValue(local)
        // Where a part's words begin and end (SAMplay 2.0.4: a stretch's first and last agreeing word).
        fun startSec(local: AnchorFit): Double = local.firstSec
        fun endSec(local: AnchorFit): Double = local.lastSec

        class Part(val fits: MutableList<AnchorFit>, var shiftMs: Double, val group: Int = 0, val drift: Boolean = false)
        val parts = ArrayList<Part>()
        for (local in locals) {
            val shift = targetShiftMs(local)
            val last = parts.lastOrNull()
            if (last != null && abs(last.shiftMs - shift) <= STEP_TOLERANCE_MS) {
                last.fits += local
                last.shiftMs = last.fits.sumOf { targetShiftMs(it) * it.score } / last.fits.sumOf { it.score }
            } else {
                parts += Part(mutableListOf(local), shift)
            }
        }
        // A step needs solid evidence on both sides; stray regions are ignored.
        parts.removeAll { part -> part.fits.sumOf { it.segments } < MIN_PART_SEGMENTS }
        if (parts.isEmpty()) return null
        // SAMplay 2.0.4: inside one part the offset can also move a little at a time (Z Nation 3x3:
        // +0.2 s at 5 min, -0.25 s at 12 to 18 min, -0.5 s from 19 min), never a step of
        // [STEP_TOLERANCE_MS] at once, so the subtitle stayed half a second late. Each part is cut
        // into levels where enough words say the offset moved ([driftLevels]).
        val levels = parts.flatMapIndexed { group, part ->
            driftLevels(part.fits.map { targetShiftMs(it) }, part.fits.map { it.segments }, part.fits.map { it.anchorSec }).mapIndexed { k, run ->
                val fits = part.fits.subList(run.first, run.last + 1).toMutableList()
                Part(fits, fits.sumOf { targetShiftMs(it) * it.score } / fits.sumOf { it.score }, group, drift = k > 0)
            }
        }
        // One part: words heard in one place only. Its offset wins over the whole-film mapping
        // where the subtitle and the reference differ there.
        if (levels.size == 1 && abs(levels[0].shiftMs - shiftMs) <= STEP_TOLERANCE_MS) return null
        val frameMs = SpeechTimeline.FRAME_DURATION_MS
        val fine = levels.mapIndexed { i, part ->
            // SAMplay 2.0.4: the speech around a part, but not past the words of its neighbours: a
            // step is usually under the 3 s searched, so the neighbour's speech pulled a new part's
            // offset back towards the old one.
            val fromSec = maxOf(startSec(part.fits.first()) - PART_CONTEXT_SEC, levels.getOrNull(i - 1)?.let { endSec(it.fits.last()) } ?: 0.0)
            val toSec = minOf(endSec(part.fits.last()) + PART_CONTEXT_SEC, levels.getOrNull(i + 1)?.let { startSec(it.fits.first()) } ?: Double.MAX_VALUE)
            val fromFrame = (fromSec * 1_000 / frameMs).toInt().coerceAtLeast(0)
            val toFrame = (toSec * 1_000 / frameMs).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
            fineTune(track, scale, part.shiftMs, fromFrame, toFrame)
                ?.takeIf { isTrustedMove(it, part.shiftMs) }?.shiftMs
        }
        val tuned = calibratedLevels(levels.map { it.shiftMs }, fine, levels.map { it.group })
        val segments = ArrayList<SubtitleSyncSegment>()
        segments += SubtitleSyncSegment(0L, scale, tuned.first())
        var shownLevel = 0
        for (i in 1 until levels.size) {
            val step = tuned[i] - segments.last().shiftMs
            // SAMplay 2.0.4: a step between two parts (words at least [STEP_TOLERANCE_MS] apart) is
            // taken from [DRIFT_STEP_MIN_MS] on, as one between two levels of a part is. It needed
            // [STEP_TOLERANCE_MS] after fine-tuning, so a step the speech put at about 0.6 s came and
            // went with every few words (Z Nation 3x7 at 34 min), moving the subtitle back and
            // forth by 0.7 s.
            if (abs(step) <= DRIFT_STEP_MIN_MS) continue
            // A small step goes the way the words say: fine-tuning a short level alone can wander.
            if (step * (levels[i].shiftMs - levels[shownLevel].shiftMs) <= 0.0) continue
            // Between the last word on the old offset and the first on the new one. SAMplay 2.0.4:
            // regions next to the step go to the side whose offset they are closer to; a region
            // 0.4 s off that one of them kept made the step jump a minute and back (Z Nation 3x8).
            val around = levels[i - 1].fits + levels[i].fits
            val cut = stepBoundary(
                levels[i - 1].fits.map { targetShiftMs(it) },
                levels[i - 1].fits.map { it.segments },
                levels[i].fits.map { targetShiftMs(it) },
                levels[i].fits.map { it.segments },
            )
            val afterMs = endSec(around[cut - 1]) * 1_000
            val beforeMs = startSec(around[cut]) * 1_000
            val split = stepPlace(track, scale, afterMs, beforeMs, segments.last().shiftMs, tuned[i])
            segments += SubtitleSyncSegment(split.coerceAtLeast(segments.last().fromMediaMs + 1), scale, tuned[i])
            shownLevel = i
        }
        return segments.takeIf { it.size >= 2 || abs(it[0].shiftMs - shiftMs) > STEP_TOLERANCE_MS }
    }

    /**
     * Where the subtitle switches from [oldShiftMs] to [newShiftMs], between the last word heard on
     * the old offset ([afterMs]) and the first on the new one ([beforeMs]): where the detected
     * speech shows the change.
     *
     * SAMplay 2.0.4: when part of that gap was not analysed yet (the words on the new offset came
     * from a spot sampled further on), the speech cannot tell, and the guess jumped between the end
     * of what was heard and that spot with every new word (Z Nation 2x15 on the TV). The step then
     * goes just before the first word on the new offset: everything heard so far is on the old one,
     * and the words heard next (ahead of the playhead) find the real place before it is on screen.
     */
    private fun stepPlace(
        track: SubtitleSpeechTrack,
        scale: Double,
        afterMs: Double,
        beforeMs: Double,
        oldShiftMs: Double,
        newShiftMs: Double,
    ): Long {
        val frameMs = SpeechTimeline.FRAME_DURATION_MS
        val from = (afterMs / frameMs).toInt()
        val to = (beforeMs / frameMs).toInt()
        val unknownMs = timeline.snapshot(from, to).count { it.isNaN() } * frameMs
        if (unknownMs > UNHEARD_GAP_MAX_MS) return maxOf((beforeMs - LATE_STEP_LEAD_MS).toLong(), afterMs.toLong() + 1)
        return AudioSyncTracker.changePoint(
            timeline = timeline,
            track = track,
            from = from,
            to = to,
            scale = scale,
            oldShiftMs = oldShiftMs,
            newShiftMs = newShiftMs,
        ) ?: ((afterMs + beforeMs) / 2).toLong()
    }

    /**
     * While the reference's frame rate relative to the video is unknown, the target keeps its own
     * rate (the usual case: a subtitle made for this release), anchored where the words were heard.
     * Another common ratio is used only when the detected speech clearly confirms it over plenty
     * of audio: a wrong stretch drifts further with every minute. Returns the target mapping
     * (scale, coarse shift) and its fine-tuned estimate, if any.
     */
    private fun mostLikelyRate(
        track: SubtitleSpeechTrack,
        words: List<HeardWord>,
        fit: AnchorFit,
        bridge: BridgeFit?,
    ): Triple<Double, Double, SubtitleAudioAligner.Estimate?> {
        val anchorMs = fit.anchorSec * 1_000.0
        val referenceAtAnchorMs = anchorMs - fit.shiftSec * 1_000.0
        val bridgeScale = bridge?.scale ?: 1.0

        /** Target -> media mapping when the reference runs at [rate] relative to the video. */
        fun hypothesis(rate: Double): Triple<Double, Double, SubtitleAudioAligner.Estimate?> {
            // Reference -> media at this rate, passing through the anchor; then target -> media.
            val referenceShiftMs = anchorMs - rate * referenceAtAnchorMs
            val scale = rate * bridgeScale
            val coarseShiftMs = rate * (bridge?.shiftSec ?: 0.0) * 1_000.0 + referenceShiftMs
            return Triple(scale, coarseShiftMs, fineTune(track, scale, coarseShiftMs))
        }

        val unstretched = hypothesis(1.0 / bridgeScale)
        val knownFrames = timeline.segments(maxFrames = FINE_TUNE_MAX_FRAMES).sumOf { it.knownFrames }
        if (knownFrames < STRETCH_MIN_KNOWN_FRAMES) return unstretched
        // Trying every ratio is the expensive part: keep the ratio chosen last time until clearly
        // more words or audio are in (or the target or bridge changed), then judge again.
        val situation = "${track.hashCode()}|${bridge?.shiftSec}|${knownFrames / STRETCH_RECHECK_FRAMES}"
        chosenRate?.let { last ->
            if (last.situation == situation && words.size < last.words * 5 / 4) return hypothesis(last.rate)
        }
        val floor = unstretched.third?.peak?.plus(OTHER_RATE_MARGIN) ?: Double.NEGATIVE_INFINITY
        var best = unstretched
        var bestPeak = floor
        for (rate in SubtitleAudioAligner.CANDIDATE_SCALES) {
            val candidate = hypothesis(rate)
            if (abs(candidate.first - 1.0) < 1e-9) continue
            val fine = candidate.third ?: continue
            if (fine.atSearchEdge || fine.prominence < STRETCH_MIN_PROMINENCE) continue
            if (fine.peak > bestPeak) {
                bestPeak = fine.peak
                best = candidate
            }
        }
        chosenRate = ChosenRate(situation, words.size, best.first / bridgeScale)
        return best
    }

    /**
     * Word anchors are exact about *which* line is spoken but only approximately about when inside
     * it; the speech timeline pins the edges. Search a narrow window so it cannot jump elsewhere.
     */
    private fun fineTune(
        track: SubtitleSpeechTrack,
        scale: Double,
        coarseShiftMs: Double,
        fromFrame: Int = 0,
        toFrame: Int = Int.MAX_VALUE,
    ): SubtitleAudioAligner.Estimate? {
        val segments = timeline.segments(fromFrame = fromFrame, toFrame = toFrame, maxFrames = FINE_TUNE_MAX_FRAMES)
        if (segments.isEmpty()) return null
        val estimate = SubtitleAudioAligner.estimate(
            segments = segments,
            track = track,
            scales = doubleArrayOf(scale),
            minShiftMs = coarseShiftMs - FINE_TUNE_WINDOW_MS,
            maxShiftMs = coarseShiftMs + FINE_TUNE_WINDOW_MS,
        ) ?: return null
        if (estimate.atSearchEdge || estimate.cueCount < FINE_TUNE_MIN_CUES) return null
        if (abs(estimate.shiftMs - coarseShiftMs) > FINE_TUNE_MAX_MOVE_MS) return null
        return estimate
    }

    /**
     * Whether a fine-tune estimate may move the word-based offset [coarseShiftMs]. Moves within the
     * words' own timing uncertainty are taken as before; a larger one needs a peak that stands out,
     * since a flat speech pattern (dense dialogue, music) could otherwise pull a good word-based
     * offset by up to [FINE_TUNE_MAX_MOVE_MS].
     */
    private fun isTrustedMove(estimate: SubtitleAudioAligner.Estimate, coarseShiftMs: Double): Boolean =
        abs(estimate.shiftMs - coarseShiftMs) <= FINE_TUNE_FREE_MOVE_MS ||
            estimate.prominence >= FINE_TUNE_MIN_PROMINENCE

    private fun overlap(a: IntRange, b: IntRange): Int =
        (minOf(a.last, b.last) - maxOf(a.first, b.first) + 1).coerceAtLeast(0)

    companion object {
        /**
         * SAMplay 2.0.6: whether [old] and [new] place every subtitle from [fromMs] on (and for
         * [PLACE_HORIZON_MS] past the last step of either) within [toleranceMs] of each other.
         */
        internal fun placesAlikeAhead(
            old: List<SubtitleSyncSegment>,
            new: List<SubtitleSyncSegment>,
            fromMs: Long,
            toleranceMs: Double,
        ): Boolean {
            fun target(segments: List<SubtitleSyncSegment>, t: Long): Double {
                val s = segments.lastOrNull { it.fromMediaMs <= t } ?: segments.first()
                return (t - s.shiftMs) / s.scale
            }
            val end = maxOf(old.last().fromMediaMs, new.last().fromMediaMs, fromMs) + PLACE_HORIZON_MS
            val steps = (old + new).map { it.fromMediaMs }.filter { it in fromMs..end }.flatMap { listOf(it - 1, it) }
            return (generateSequence(fromMs) { it + PLACE_STEP_MS }.takeWhile { it <= end } + steps)
                .all { t -> abs(target(old, t) - target(new, t)) < toleranceMs }
        }

        /**
         * SAMplay 2.0.6: whether [new] places the subtitles nearer than [old] to where the words heard
         * from [WORDS_LOOKBACK_MS] before [fromMs] on put them ([evidence]: media second -> shift at
         * [evidenceScale], weighted by [weights]), counting only where the two differ, and no worse
         * for the next [WORDS_NEAR_AHEAD_MS]. With no words there it does not hold the change back.
         */
        internal fun fitsWordsBetterAhead(
            old: List<SubtitleSyncSegment>,
            new: List<SubtitleSyncSegment>,
            evidence: List<Pair<Double, Double>>,
            weights: List<Int>,
            evidenceScale: Double,
            fromMs: Long,
        ): Boolean {
            fun target(segments: List<SubtitleSyncSegment>, t: Double): Double {
                val s = segments.lastOrNull { it.fromMediaMs <= t } ?: segments.first()
                return (t - s.shiftMs) / s.scale
            }
            var oldError = 0.0
            var newError = 0.0
            var weight = 0
            var oldNear = 0.0
            var newNear = 0.0
            var nearWeight = 0
            evidence.forEachIndexed { i, (anchorSec, shiftMs) ->
                val t = anchorSec * 1_000.0
                if (t < fromMs - WORDS_LOOKBACK_MS) return@forEachIndexed
                val placedOld = target(old, t)
                val placedNew = target(new, t)
                if (abs(placedOld - placedNew) < UPDATE_MIN_MS) return@forEachIndexed
                val heard = (t - shiftMs) / evidenceScale
                val w = weights.getOrElse(i) { 1 }
                oldError += w * abs(placedOld - heard)
                newError += w * abs(placedNew - heard)
                weight += w
                if (t <= fromMs + WORDS_NEAR_AHEAD_MS) {
                    oldNear += w * abs(placedOld - heard)
                    newNear += w * abs(placedNew - heard)
                    nearWeight += w
                }
            }
            if (weight == 0) return true
            // What is shown next must not get worse for a better place further on (2.0.7 on the
            // phone took a step at 40:24 with 0.4 s off at 38:30, and took it back 40 s later).
            if (nearWeight > 0 && newNear / nearWeight > oldNear / nearWeight + WORDS_NEAR_WORSE_MAX_MS) return false
            return newError / weight < oldError / weight - WORDS_BETTER_MARGIN_MS
        }

        /**
         * SAMplay 2.0.4: whether [result] only drops steps [previous] found later in the film while
         * nothing heard after the first dropped step agrees with the offset before it. [evidence]:
         * (media s, target shift ms) of each confident region of words. A new step rests on a few
         * lines at first; one more word could tip its region below "confident" for a moment, and the
         * drift watch then started to move the subtitle back by the step (Z Nation at 30 min). Such
         * a result is not taken: the step stays until the words after it say otherwise.
         */
        internal fun keepsLaterSteps(
            previous: List<SubtitleSyncSegment>,
            result: List<SubtitleSyncSegment>,
            evidence: List<Pair<Double, Double>>,
        ): Boolean {
            if (result.isEmpty() || result.size >= previous.size) return false
            for (i in result.indices) {
                if (result[i].scale != previous[i].scale) return false
                if (abs(result[i].shiftMs - previous[i].shiftMs) >= SETTLED_UPDATE_MIN_MS) return false
                if (i > 0 && abs(result[i].fromMediaMs - previous[i].fromMediaMs) >= CHANGE_POINT_TOLERANCE_MS) return false
            }
            val dropped = previous.subList(result.size, previous.size)
            val oldShiftMs = result.last().shiftMs
            // SAMplay 2.0.4: a small step (a level of [driftLevels]) is told from the offset before it
            // by half its size; a whole step by [STEP_TOLERANCE_MS] as before.
            val toleranceMs = minOf(STEP_TOLERANCE_MS, abs(dropped.first().shiftMs - oldShiftMs) / 2)
            return evidence.none { (atSec, shiftMs) ->
                atSec * 1_000.0 >= dropped.first().fromMediaMs &&
                    abs(shiftMs - oldShiftMs) <= toleranceMs &&
                    dropped.none { abs(it.shiftMs - shiftMs) <= toleranceMs }
            }
        }

        /**
         * SAMplay 2.0.4: the levels one part of the film moves through a little at a time, as runs
         * (index ranges) of its regions in media order. [shiftsMs]: each region's target shift,
         * [segments]: its speech segments, [atSec]: where it is. The fewest runs that explain the
         * shifts (least squares, each extra run paying [DRIFT_SPLIT_COST]); a run needs
         * [DRIFT_MIN_SEGMENTS] segments and differs from the next by at least [DRIFT_STEP_MIN_MS]. A
         * region far from its neighbours counts only [DRIFT_CLIP_MS] away from them, and a run
         * shorter than [DRIFT_EXCURSION_SEC] between two that agree is part of them, so one misheard
         * minute moves nothing.
         */
        internal fun driftLevels(shiftsMs: List<Double>, segments: List<Int>, atSec: List<Double>): List<IntRange> {
            val n = shiftsMs.size
            if (n < 2) return listOf(0 until n)
            val y = DoubleArray(n) { i ->
                val around = (maxOf(0, i - 2)..minOf(n - 1, i + 2)).map { shiftsMs[it] }.sorted()
                val median = around[around.size / 2]
                shiftsMs[i].coerceIn(median - DRIFT_CLIP_MS, median + DRIFT_CLIP_MS)
            }
            val w = DoubleArray(n + 1)
            val wy = DoubleArray(n + 1)
            val wyy = DoubleArray(n + 1)
            for (i in 0 until n) {
                val weight = segments[i].toDouble()
                w[i + 1] = w[i] + weight
                wy[i + 1] = wy[i] + weight * y[i]
                wyy[i + 1] = wyy[i] + weight * y[i] * y[i]
            }
            fun spread(a: Int, b: Int): Double = (wyy[b] - wyy[a]) - (wy[b] - wy[a]) * (wy[b] - wy[a]) / (w[b] - w[a])
            fun mean(run: IntRange): Double = (wy[run.last + 1] - wy[run.first]) / (w[run.last + 1] - w[run.first])
            val best = DoubleArray(n + 1) { Double.POSITIVE_INFINITY }
            val from = IntArray(n + 1) { -1 }
            best[0] = 0.0
            for (b in 1..n) {
                for (a in 0 until b) {
                    if (best[a].isInfinite() || w[b] - w[a] < DRIFT_MIN_SEGMENTS) continue
                    val cost = best[a] + spread(a, b) + if (a == 0) 0.0 else DRIFT_SPLIT_COST
                    if (cost < best[b]) {
                        best[b] = cost
                        from[b] = a
                    }
                }
            }
            if (best[n].isInfinite()) return listOf(0 until n)
            val runs = ArrayList<IntRange>()
            var end = n
            while (end > 0) {
                runs.add(0, from[end] until end)
                end = from[end]
            }
            do {
                var merged = false
                // A short run between two that agree: a misheard stretch, not two steps.
                var i = 1
                while (i < runs.size - 1) {
                    val spanSec = atSec[runs[i].last] - atSec[runs[i].first]
                    if (spanSec < DRIFT_EXCURSION_SEC && abs(mean(runs[i - 1]) - mean(runs[i + 1])) < DRIFT_STEP_MIN_MS) {
                        runs[i - 1] = runs[i - 1].first..runs[i + 1].last
                        runs.removeAt(i + 1)
                        runs.removeAt(i)
                        merged = true
                    } else {
                        i++
                    }
                }
                // Neighbouring levels closer than a small step are one level.
                while (runs.size > 1) {
                    val j = (0 until runs.size - 1).minByOrNull { abs(mean(runs[it]) - mean(runs[it + 1])) }!!
                    if (abs(mean(runs[j]) - mean(runs[j + 1])) >= DRIFT_STEP_MIN_MS) break
                    runs[j] = runs[j].first..runs[j + 1].last
                    runs.removeAt(j + 1)
                    merged = true
                }
            } while (merged)
            return runs
        }

        /**
         * SAMplay 2.0.4: the shift of each level: its fine-tuned one where the speech gives one that
         * agrees with its words. The levels of one part ([groups]) are placed by their words, moved
         * by how far the speech puts that part from its words ([fine] - [words], the median over its
         * levels), so a level the speech cannot place, or places against its words (a short stretch
         * alone can wander), still sits where the words put it relative to the others.
         */
        internal fun calibratedLevels(words: List<Double>, fine: List<Double?>, groups: List<Int>): List<Double> =
            words.indices.map { i ->
                val group = groups[i]
                val members = words.indices.filter { groups[it] == group }
                if (members.size == 1) return@map fine[i] ?: words[i]
                val biases = members.mapNotNull { m -> fine[m]?.let { it - words[m] } }.sorted()
                val bias = if (biases.isEmpty()) 0.0 else biases[biases.size / 2]
                val placed = words[i] + bias
                fine[i]?.takeIf { abs(it - placed) <= DRIFT_FINE_TOLERANCE_MS } ?: placed
            }

        /**
         * SAMplay 2.0.4: where a step goes between two levels, given the offsets ([oldShiftsMs],
         * [newShiftsMs]) and speech segments of their regions in order: the number of regions,
         * counted from the old level's first, that stay on the old offset. Regions next to the step
         * that are closer to the other level's offset than to the rest of their own go to the other
         * side, so a region the levels were cut on either side of cannot move the step by its
         * whole length from one result to the next. Each level keeps at least one region.
         */
        internal fun stepBoundary(oldShiftsMs: List<Double>, oldSegments: List<Int>, newShiftsMs: List<Double>, newSegments: List<Int>): Int {
            fun mean(shifts: List<Double>, segments: List<Int>): Double {
                val weights = segments.map { maxOf(it, 1).toDouble() }
                return shifts.indices.sumOf { shifts[it] * weights[it] } / weights.sum()
            }
            val newMs = mean(newShiftsMs, newSegments)
            var cut = oldShiftsMs.size
            while (cut > 1) {
                val restMs = mean(oldShiftsMs.subList(0, cut - 1), oldSegments.subList(0, cut - 1))
                val shift = oldShiftsMs[cut - 1]
                if (abs(shift - newMs) < abs(shift - restMs)) cut-- else break
            }
            if (cut < oldShiftsMs.size) return cut
            val oldMs = mean(oldShiftsMs, oldSegments)
            var lead = 0
            while (lead < newShiftsMs.size - 1) {
                val restMs = mean(newShiftsMs.subList(lead + 1, newShiftsMs.size), newSegments.subList(lead + 1, newSegments.size))
                val shift = newShiftsMs[lead]
                if (abs(shift - oldMs) < abs(shift - restMs)) lead++ else break
            }
            return oldShiftsMs.size + lead
        }

        /** Smallest step between the levels of one part (see [driftLevels]). */
        private const val DRIFT_STEP_MIN_MS = 150.0

        /** Speech segments a level needs. */
        private const val DRIFT_MIN_SEGMENTS = 10.0

        /**
         * What an extra level must save in squared error (ms² per segment): a step must stand out
         * about 3.5 times from word timing noise of 0.2 s per segment.
         */
        private const val DRIFT_SPLIT_COST = 490_000.0

        /** Shortest level between two that agree with each other (as in WordAnchorMatcher.localFits). */
        private const val DRIFT_EXCURSION_SEC = 120.0

        /** How far a region counts from the median of the regions around it. */
        private const val DRIFT_CLIP_MS = 200.0

        /** How far a level's fine-tune may move it from where its words put it. */
        private const val DRIFT_FINE_TOLERANCE_MS = 150.0

        /** Regions of words named in [regionsSummary]. */
        private const val REGIONS_LOGGED = 6

        /** About 8 minutes of speech at 16 kHz. */
        private const val MAX_QUEUED_SAMPLES = 16_000L * 60 * 8
        private const val BEHIND_GRACE_FRAMES = 94 // 3 s
        private const val SPREAD_PRIORITY = -2_000_000L
        private const val SPREAD_RADIUS_FRAMES = 2_813 // 90 s
        private val FINE_TUNE_MAX_FRAMES = (10 * 60 * 1_000 / SpeechTimeline.FRAME_DURATION_MS).toInt()
        /** Searched either side of the coarse shift; the aligner distrusts the outer 1.5 s of any range. */
        private const val FINE_TUNE_WINDOW_MS = 3_000.0
        private const val FINE_TUNE_MAX_MOVE_MS = 1_200.0
        private const val FINE_TUNE_MIN_CUES = 5

        /** Fine-tune moves up to this need no clear peak: about how far off a word's time can be. */
        private const val FINE_TUNE_FREE_MOVE_MS = 500.0

        /** Peak lead over any offset more than 2.5 s away that a larger move needs. */
        private const val FINE_TUNE_MIN_PROMINENCE = 0.05
        private const val FINAL_SPAN_SEC = 180.0

        /** Correlation a stretched mapping must win by over the unstretched one. */
        private const val OTHER_RATE_MARGIN = 0.04

        /** How much lower than the best the reference chosen last may score and still stay. */
        private const val REFERENCE_SWITCH_MARGIN = 0.15

        /** Like a speech-detection lock at a non-standard rate: 0.12 plus the 0.04 penalty. */
        private const val STRETCH_MIN_PROMINENCE = 0.16

        /** More heard audio that makes the ratio worth judging again (30 s). */
        private val STRETCH_RECHECK_FRAMES = (30 * 1_000 / SpeechTimeline.FRAME_DURATION_MS).toInt()

        /** Heard audio needed before a stretch can be judged at all (5 minutes). */
        private val STRETCH_MIN_KNOWN_FRAMES = (5 * 60 * 1_000 / SpeechTimeline.FRAME_DURATION_MS).toInt()
        private const val UPDATE_MIN_MS = 150.0

        /** Once synced, only a real change moves the subtitles, not fine-tuning jitter. */
        private const val SETTLED_UPDATE_MIN_MS = 300.0

        /** SAMplay 2.0.6: a return to an earlier mapping this close to the current one is a flip. */
        private const val FLIP_MAX_MS = 400.0
        private const val APPLIED_LOCKS_KEPT = 6
        private const val PLACE_HORIZON_MS = 900_000L
        private const val PLACE_STEP_MS = 2_000L
        private const val WORDS_LOOKBACK_MS = 60_000.0
        private const val WORDS_BETTER_MARGIN_MS = 50.0
        private const val WORDS_NEAR_AHEAD_MS = 60_000.0
        private const val WORDS_NEAR_WORSE_MAX_MS = 100.0

        /** Offsets within this of each other are the same part of the film. */
        private const val STEP_TOLERANCE_MS = 600.0

        /** Word segments a part of the film needs before its offset counts. */
        private const val MIN_PART_SEGMENTS = 4

        /** Audio around a part's words used to fine-tune its offset. */
        private const val PART_CONTEXT_SEC = 150.0

        /** Agreement between places counts as a part only when its words span this much of the film. */
        private const val SPREAD_MIN_SPAN_SEC = 180.0
        private const val CHANGE_POINT_TOLERANCE_MS = 5_000L

        /** Speech not yet analysed between two offsets beyond which [stepPlace] cannot place the step. */
        private const val UNHEARD_GAP_MAX_MS = 5_000.0

        /** How far before the first word on a new offset [stepPlace] puts a step it cannot see. */
        private const val LATE_STEP_LEAD_MS = 2_000.0

        /** After syncing, speech up to this far ahead of the playhead is still recognised (3 min). */
        private const val MAINTAIN_AHEAD_FRAMES = 5_625
    }
}
