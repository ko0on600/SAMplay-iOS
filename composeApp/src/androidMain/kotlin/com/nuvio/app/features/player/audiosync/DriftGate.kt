package com.nuvio.app.features.player.audiosync

import kotlin.math.abs

/**
 * SAMplay TV 1.0.2: when the drift watch may move a subtitle that already looked synced. Kept free
 * of Android so it can be unit-tested.
 *
 * A drift is acted on only when it is at least [minMs], the audio has kept giving the same answer
 * (within [agreeMs]) for [confirmMs], and [cooldownMs] has passed since the last correction, so a
 * noisy scene cannot keep moving the subtitle back and forth.
 */
internal class DriftGate(
    private val minMs: Long,
    private val agreeMs: Long,
    private val confirmMs: Long,
    private val cooldownMs: Long,
    /**
     * SAMplay 2.0.4: the smallest drift moved when the answer comes from recognised words, which
     * place a line far more exactly than speech detection. Z Nation kept a subtitle 343 ms early
     * for its last 11 minutes: the words said so, but anything under [minMs] was left alone.
     */
    private val preciseMinMs: Long = minMs,
    /** How long a drift under [minMs] (precise answers only) must hold before it is moved. */
    private val preciseConfirmMs: Long = confirmMs,
) {
    enum class Decision {
        /** Too small to matter (or nothing to compare). */
        NONE,
        /** A new drift value: the confirmation wait starts over. */
        SUSPECTED,
        /** The same drift, not yet held for long enough. */
        CONFIRMING,
        /** Confirmed, but the previous correction was too recent. */
        COOLING_DOWN,
        /** Move the subtitle now. */
        APPLY,
    }

    private var candidateMs: Long? = null
    private var seenAtMs = 0L
    private var lastApplyAtMs: Long? = null

    /** The audio has no confirmed answer (or the user seeked away): start over. */
    fun reset() {
        candidateMs = null
    }

    /** Milliseconds left in the cool-down at [nowMs]. */
    fun cooldownLeftMs(nowMs: Long): Long = lastApplyAtMs?.let { (cooldownMs - (nowMs - it)).coerceAtLeast(0L) } ?: 0L

    /** [precise]: the answer comes from recognised words (see [preciseMinMs]). */
    fun offer(driftMs: Long, nowMs: Long, precise: Boolean = false): Decision {
        if (abs(driftMs) < if (precise) minOf(minMs, preciseMinMs) else minMs) {
            candidateMs = null
            return Decision.NONE
        }
        val candidate = candidateMs
        if (candidate == null || abs(candidate - driftMs) > agreeMs) {
            candidateMs = driftMs
            seenAtMs = nowMs
            return Decision.SUSPECTED
        }
        if (nowMs - seenAtMs < if (abs(driftMs) < minMs) preciseConfirmMs else confirmMs) return Decision.CONFIRMING
        if (cooldownLeftMs(nowMs) > 0L) return Decision.COOLING_DOWN
        lastApplyAtMs = nowMs
        candidateMs = null
        return Decision.APPLY
    }
}

/**
 * SAMplay 2.0.3: when the drift watch may take a better mapping without a word. As the audio hears
 * more, its mapping places a step (a scene one cut has and the other lacks) more exactly, e.g. 20 s
 * later than it first thought, or already knows of one a little ahead. Such a mapping that agrees
 * with what is shown at the playhead (within [atPlayheadMs]) moves nothing on screen now, so it is
 * taken once it differs elsewhere by at least [minChangeMs] and has kept saying the same for
 * [confirmMs] (the largest difference within [agreeMs], at a place within [placeMs]): the lines
 * there are then right when the film gets to them or the user goes back to them, with no drift
 * to wait out. At most once per [gapMs].
 *
 * A mapping with the very steps already shown, only placed elsewhere, is taken the same way even
 * where it moves the line on screen (the playhead is between the two places): its shifts were
 * confirmed when they were first applied, so only the place is new, and the drift watch's longer
 * wait and cool-down would leave those lines a few seconds off meanwhile.
 */
internal class RefineGate(
    private val atPlayheadMs: Long,
    private val minChangeMs: Long,
    private val agreeMs: Long,
    private val placeMs: Long,
    private val confirmMs: Long,
    private val gapMs: Long,
    /**
     * SAMplay 2.0.4: the smallest change taken when the mapping comes from recognised words: the
     * small steps of a slowly moving offset (AsrSyncEngine.driftLevels) are
     * then in place before the film gets to them.
     */
    private val preciseMinChangeMs: Long = minChangeMs,
) {
    /** The change being confirmed: (largest difference, where), in ms. */
    private var held: Pair<Long, Long>? = null
    private var seenAtMs = 0L
    private var lastTakenAtMs: Long? = null

    fun reset() {
        held = null
    }

    /**
     * True when [model] should replace [shown] now; [driftMs] is how far apart they are at the
     * playhead. [precise]: the mapping comes from recognised words (see [preciseMinChangeMs]).
     */
    fun offer(model: SubtitleSyncModel, shown: SubtitleSyncModel, driftMs: Long, nowMs: Long, precise: Boolean = false): Boolean {
        if (model === shown || (abs(driftMs) > atPlayheadMs && !model.sameShiftsAs(shown, agreeMs))) {
            held = null
            return false
        }
        val change = model.largestDifferenceMs(shown)
        if (change.first < if (precise) minOf(minChangeMs, preciseMinChangeMs) else minChangeMs) {
            held = null
            return false
        }
        val before = held
        if (before == null || abs(before.first - change.first) > agreeMs || abs(before.second - change.second) > placeMs) {
            held = change
            seenAtMs = nowMs
            return false
        }
        if (nowMs - seenAtMs < confirmMs) return false
        if (lastTakenAtMs?.let { nowMs - it < gapMs } == true) return false
        held = null
        lastTakenAtMs = nowMs
        return true
    }
}
