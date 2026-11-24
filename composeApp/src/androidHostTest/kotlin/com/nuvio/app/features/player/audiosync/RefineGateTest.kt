package com.nuvio.app.features.player.audiosync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SAMplay 2.0.3: the drift watch takes a better mapping quietly, only where nothing on screen moves. */
class RefineGateTest {
    private fun gate() = AudioSyncFallback.newRefineGate()

    /** [beforeMs] up to [stepMs], then [afterMs]: a subtitle made for another cut. */
    private fun stepAt(stepMs: Long, beforeMs: Double = 2_917.0, afterMs: Double = -131.0) = SubtitleSyncModel(
        listOf(
            SubtitleSyncSegment(fromMediaMs = 0L, scale = 1.0, shiftMs = beforeMs),
            SubtitleSyncSegment(fromMediaMs = stepMs, scale = 1.0, shiftMs = afterMs),
        ),
    )

    @Test fun theLargestDifferenceIsBetweenTheTwoSteps() {
        val (difference, atMs) = stepAt(882_000L).largestDifferenceMs(stepAt(901_600L))
        assertEquals(3_048L, difference)
        assertTrue("at $atMs", atMs in 882_000L until 901_600L)
        assertEquals(0L, stepAt(901_600L).largestDifferenceMs(stepAt(901_600L)).first)
        assertEquals(2_917L, stepAt(901_600L).largestDifferenceMs(SubtitleSyncModel.UNCHANGED).first)
    }

    @Test fun aStepPlacedBetterBehindThePlayheadIsTakenOnceSteady() {
        val g = gate()
        val shown = stepAt(882_000L)
        // The playhead is past both steps: there the two agree.
        assertFalse(g.offer(stepAt(901_600L), shown, 0L, 0L))
        assertFalse("the step 100 ms on is the same answer", g.offer(stepAt(901_700L), shown, 0L, 3_999L))
        assertTrue(g.offer(stepAt(901_650L), shown, 0L, 4_000L))
    }

    // SAMplay 2.0.4: a small step ahead from recognised words is taken before the film gets there.
    @Test fun aSmallStepAheadFromWordsIsTakenBeforeTheFilmGetsThere() {
        val ahead = stepAt(905_000L, beforeMs = 0.0, afterMs = -200.0)
        val g = gate()
        assertFalse(g.offer(ahead, SubtitleSyncModel.UNCHANGED, 40L, 0L, precise = true))
        assertTrue(g.offer(ahead, SubtitleSyncModel.UNCHANGED, 40L, 4_000L, precise = true))
        // From speech detection alone it is too small.
        val plain = gate()
        repeat(10) { assertFalse(plain.offer(ahead, SubtitleSyncModel.UNCHANGED, 40L, it * 1_000L)) }
    }

    @Test fun aStepAheadIsTakenBeforeTheFilmGetsThere() {
        val g = gate()
        val ahead = stepAt(905_000L, beforeMs = 0.0, afterMs = -3_000.0)
        assertFalse(g.offer(ahead, SubtitleSyncModel.UNCHANGED, 40L, 0L))
        assertTrue(g.offer(ahead, SubtitleSyncModel.UNCHANGED, 40L, 4_000L))
    }

    @Test fun aNewShiftThatMovesTheLineOnScreenIsLeftToTheDriftWatch() {
        val g = gate()
        val shown = stepAt(882_000L)
        repeat(30) { assertFalse(g.offer(stepAt(901_600L, afterMs = 1_000.0), shown, 300L, it * 1_000L)) }
    }

    @Test fun aStepMovedPastThePlayheadIsTakenWithoutTheDriftWait() {
        val g = gate()
        // Shown: the step at 956.7 s (the audio's first guess); the film is at 920 s, where the
        // audio now says the step was at 901.6 s: the same two shifts, 3 s apart here.
        val shown = stepAt(956_672L, beforeMs = 2_947.0, afterMs = -219.0)
        val placed = stepAt(901_600L, beforeMs = 2_949.0, afterMs = -213.0)
        assertTrue(placed.sameShiftsAs(shown, 250L))
        assertFalse(g.offer(placed, shown, -3_162L, 0L))
        assertTrue(g.offer(placed, shown, -3_162L, 4_000L))
    }

    @Test fun smallDifferencesAreLeftAlone() {
        val g = gate()
        val shown = stepAt(882_000L)
        repeat(30) { assertFalse(g.offer(stepAt(882_000L, afterMs = 200.0), shown, 0L, it * 1_000L)) }
    }

    @Test fun aChangingAnswerStartsTheWaitOver() {
        val g = gate()
        val shown = stepAt(882_000L)
        assertFalse(g.offer(stepAt(901_600L), shown, 0L, 0L))
        // The audio now places the step half a minute later: another answer.
        assertFalse(g.offer(stepAt(931_000L), shown, 0L, 3_000L))
        assertFalse(g.offer(stepAt(931_000L), shown, 0L, 6_999L))
        assertTrue(g.offer(stepAt(931_000L), shown, 0L, 7_000L))
    }

    @Test fun refinementsKeepTheirGap() {
        val g = gate()
        g.offer(stepAt(901_600L), stepAt(882_000L), 0L, 0L)
        assertTrue(g.offer(stepAt(901_600L), stepAt(882_000L), 0L, 4_000L))
        val shown = stepAt(901_600L)
        val later = stepAt(901_600L).let { SubtitleSyncModel(it.segments + SubtitleSyncSegment(1_500_000L, 1.0, 1_000.0)) }
        assertFalse(g.offer(later, shown, 0L, 5_000L))
        assertFalse(g.offer(later, shown, 0L, 18_999L))
        assertTrue(g.offer(later, shown, 0L, 19_000L))
    }
}
