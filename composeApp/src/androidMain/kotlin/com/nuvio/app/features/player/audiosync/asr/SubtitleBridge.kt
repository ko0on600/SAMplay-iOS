package com.nuvio.app.features.player.audiosync.asr

import com.nuvio.app.features.player.audiosync.SpeechTimeline
import com.nuvio.app.features.player.audiosync.SubtitleAudioAligner
import com.nuvio.app.features.player.audiosync.SubtitleSpeechTrack
import kotlin.math.abs
import kotlin.math.roundToInt

/** Reference time = target subtitle time * [scale] + [shiftSec]. */
internal data class BridgeFit(val scale: Double, val shiftSec: Double, val peak: Double, val prominence: Double)

/**
 * Aligns a subtitle in any language to a reference subtitle of the same film by their timing
 * patterns alone. Translations are usually made from an English file and keep its line breaks, so
 * the two on-screen patterns match almost exactly. No audio is needed, so this runs in a few
 * milliseconds as soon as both files are downloaded.
 */
internal object SubtitleBridge {
    private const val MIN_PROMINENCE = 0.15
    private const val MIN_PEAK = 0.35

    fun align(target: SubtitleSpeechTrack, reference: SubtitleSpeechTrack): BridgeFit? {
        if (target.size == 0 || reference.size == 0) return null
        val frameMs = SpeechTimeline.FRAME_DURATION_MS
        val endMs = reference.endsMs.max()
        val frames = (endMs / frameMs).toInt() + 1
        // The reference's on-screen coverage stands in for "speech" in the audio aligner.
        val coverage = reference.render(0, frames, 1.0)
        val probabilities = FloatArray(frames) { coverage[it].toFloat() }
        val estimate = SubtitleAudioAligner.estimate(
            probabilities = probabilities,
            fromFrame = 0,
            track = target,
            minShiftMs = -WordAnchorMatcher.MAX_SHIFT_SEC * 1_000.0,
            maxShiftMs = WordAnchorMatcher.MAX_SHIFT_SEC * 1_000.0,
            detectorBiasMs = 0.0,
        ) ?: return null
        if (estimate.atSearchEdge || estimate.peak < MIN_PEAK || estimate.prominence < MIN_PROMINENCE) return null
        return BridgeFit(estimate.scale, estimate.shiftMs / 1_000.0, estimate.peak, estimate.prominence)
    }

    /**
     * The bridge's shift around [referenceSec] only: the lines within a few minutes of it, at the
     * whole-file [bridge]'s scale. It differs from the bridge's own shift where one file has a
     * scene the other lacks (another cut of the film). Null when the lines there don't match
     * clearly, e.g. little dialogue nearby.
     *
     * SAMplay 2.0.3: when those minutes hold such a step (the lines before [referenceSec] agree on
     * one shift and the lines after it on another), the answer is the shift on [referenceSec]'s
     * side of the step, which the line timing places to within a gap between two lines. The whole
     * window used to decide by which side had more dialogue, so near a step the shift changed up
     * to a minute or two early or late, and the drift watch moved the subtitle that far from where
     * the subtitle really changes.
     */
    fun localShiftSec(
        target: SubtitleSpeechTrack,
        reference: SubtitleSpeechTrack,
        bridge: BridgeFit,
        referenceSec: Double,
    ): Double? {
        val whole = windowShiftSec(target, reference, bridge, referenceSec - LOCAL_WINDOW_SEC, referenceSec + LOCAL_WINDOW_SEC)
        val before = windowShiftSec(target, reference, bridge, referenceSec - LOCAL_WINDOW_SEC, referenceSec)
        val after = windowShiftSec(target, reference, bridge, referenceSec, referenceSec + LOCAL_WINDOW_SEC)
        if (before != null && after != null && abs(before - after) >= STEP_MIN_SEC) {
            val stepSec = stepSec(
                target = target,
                reference = reference,
                scale = bridge.scale,
                fromSec = referenceSec - LOCAL_WINDOW_SEC,
                toSec = referenceSec + LOCAL_WINDOW_SEC,
                oldShiftSec = before,
                newShiftSec = after,
            )
            if (stepSec != null) return if (referenceSec < stepSec) before else after
        }
        return whole
    }

    /** The shift that lines up the lines in reference time [fromSec, toSec); null when unclear. */
    internal fun windowShiftSec(
        target: SubtitleSpeechTrack,
        reference: SubtitleSpeechTrack,
        bridge: BridgeFit,
        fromSec: Double,
        toSec: Double,
    ): Double? {
        val frameMs = SpeechTimeline.FRAME_DURATION_MS
        val fromFrame = (fromSec * 1_000 / frameMs).toInt().coerceAtLeast(0)
        val toFrame = (toSec * 1_000 / frameMs).toInt()
        if (toFrame <= fromFrame) return null
        val coverage = reference.render(fromFrame, toFrame, 1.0)
        val probabilities = FloatArray(coverage.size) { coverage[it].toFloat() }
        val estimate = SubtitleAudioAligner.estimate(
            probabilities = probabilities,
            fromFrame = fromFrame,
            track = target,
            scales = doubleArrayOf(bridge.scale),
            minShiftMs = (bridge.shiftSec - LOCAL_SEARCH_SEC) * 1_000.0,
            maxShiftMs = (bridge.shiftSec + LOCAL_SEARCH_SEC) * 1_000.0,
            detectorBiasMs = 0.0,
        ) ?: return null
        if (estimate.atSearchEdge || estimate.peak < LOCAL_MIN_PEAK || estimate.prominence < MIN_PROMINENCE ||
            estimate.cueCount < LOCAL_MIN_CUES
        ) {
            return null
        }
        return estimate.shiftMs / 1_000.0
    }

    /**
     * Reference time within [fromSec, toSec) where the target stops following [oldShiftSec] and
     * starts following [newShiftSec]: the split after which the lines overlap best with the new
     * shift and before which with the old one (the speech version of this is
     * [com.nuvio.app.features.player.audiosync.AudioSyncTracker.changePoint]). Null when switching
     * gains nothing anywhere.
     */
    private fun stepSec(
        target: SubtitleSpeechTrack,
        reference: SubtitleSpeechTrack,
        scale: Double,
        fromSec: Double,
        toSec: Double,
        oldShiftSec: Double,
        newShiftSec: Double,
    ): Double? {
        val frameMs = SpeechTimeline.FRAME_DURATION_MS
        val from = (fromSec * 1_000 / frameMs).toInt().coerceAtLeast(0)
        val to = (toSec * 1_000 / frameMs).toInt()
        if (to <= from) return null
        val coverage = reference.render(from, to, 1.0)
        val mean = coverage.average()
        val oldLag = (oldShiftSec * 1_000 / frameMs).roundToInt()
        val newLag = (newShiftSec * 1_000 / frameMs).roundToInt()
        val oldTrack = target.render(from - oldLag, to - oldLag, scale)
        val newTrack = target.render(from - newLag, to - newLag, scale)
        var suffix = 0.0
        var bestSuffix = 0.0
        var bestIndex = -1
        for (i in coverage.indices.reversed()) {
            suffix += (coverage[i] - mean) * (newTrack[i] - oldTrack[i])
            if (suffix > bestSuffix) {
                bestSuffix = suffix
                bestIndex = i
            }
        }
        if (bestIndex < 0) return null
        return (from + bestIndex) * frameMs / 1_000.0
    }

    /** Lines within this distance of the place asked about decide the local shift. */
    private const val LOCAL_WINDOW_SEC = 150.0

    /** How far the local shift may be from the whole-file one: an added or cut scene or two. */
    private const val LOCAL_SEARCH_SEC = 240.0
    private const val LOCAL_MIN_PEAK = 0.5
    private const val LOCAL_MIN_CUES = 20

    /** Halves of the window this far apart hold a step (an added or cut scene), not noise. */
    private const val STEP_MIN_SEC = 0.5
}
