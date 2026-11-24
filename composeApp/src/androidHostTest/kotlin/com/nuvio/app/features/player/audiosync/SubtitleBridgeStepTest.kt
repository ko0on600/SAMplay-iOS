package com.nuvio.app.features.player.audiosync

import com.nuvio.app.features.player.audiosync.asr.SubtitleBridge
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SAMplay 2.0.3: a translation made for another cut is a few seconds later from one scene on. The
 * bridge's local shift must change where the lines do, not where the denser side of a few
 * minutes of dialogue starts to outweigh the other.
 */
class SubtitleBridgeStepTest {
    /** Calmer dialogue up to 15:00, then a busy scene: more lines after the step than before it. */
    private fun reference(random: Random): List<Triple<Long, Long, String>> {
        val cues = ArrayList<Triple<Long, Long, String>>()
        var t = 20_000L
        while (t < 1_500_000L) {
            val busy = t >= STEP_MS
            val length = 1_200L + random.nextLong(if (busy) 1_500L else 2_000L)
            cues += Triple(t, t + length, "line ${cues.size}")
            t += length + if (busy) 400L + random.nextLong(1_000L) else 2_000L + random.nextLong(3_000L)
        }
        return cues
    }

    @Test
    fun theLocalShiftChangesWhereTheSubtitleSteps() {
        val reference = reference(Random(21))
        val target = reference.map { (a, b, text) ->
            if (a >= STEP_MS) Triple(a + 3_000L, b + 3_000L, text) else Triple(a, b, text)
        }
        val targetTrack = SubtitleSpeechTrack.fromCues(target)
        val referenceTrack = SubtitleSpeechTrack.fromCues(reference)
        val bridge = assertNotNull(SubtitleBridge.align(targetTrack, referenceTrack))
        for (sec in listOf(700, 800, 850, 870, 885)) {
            val shift = assertNotNull(SubtitleBridge.localShiftSec(targetTrack, referenceTrack, bridge, sec.toDouble()), "at $sec s")
            assertTrue(abs(shift) < 0.2, "at $sec s, before the step: $shift")
        }
        for (sec in listOf(905, 930, 960, 1_050)) {
            val shift = assertNotNull(SubtitleBridge.localShiftSec(targetTrack, referenceTrack, bridge, sec.toDouble()), "at $sec s")
            assertTrue(abs(shift + 3.0) < 0.2, "at $sec s, after the step: $shift")
        }
    }

    @Test
    fun noStepKeepsTheWholeWindowShift() {
        val reference = reference(Random(22))
        val target = reference.map { (a, b, text) -> Triple(a + 1_500L, b + 1_500L, text) }
        val targetTrack = SubtitleSpeechTrack.fromCues(target)
        val referenceTrack = SubtitleSpeechTrack.fromCues(reference)
        val bridge = assertNotNull(SubtitleBridge.align(targetTrack, referenceTrack))
        for (sec in listOf(400, 880, 920, 1_200)) {
            val shift = assertNotNull(SubtitleBridge.localShiftSec(targetTrack, referenceTrack, bridge, sec.toDouble()), "at $sec s")
            assertTrue(abs(shift + 1.5) < 0.2, "at $sec s: $shift")
        }
    }

    private companion object {
        const val STEP_MS = 900_000L
    }
}
