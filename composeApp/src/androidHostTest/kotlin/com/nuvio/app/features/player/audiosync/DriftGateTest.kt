package com.nuvio.app.features.player.audiosync

import com.nuvio.app.features.player.audiosync.DriftGate.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SAMplay TV 1.0.2: the drift watch moves a synced subtitle only on a steady, confirmed drift. */
class DriftGateTest {
    private fun gate() = AudioSyncFallback.newDriftGate()

    @Test fun smallDriftIsLeftAlone() {
        val g = gate()
        repeat(100) { assertEquals(Decision.NONE, g.offer(350L, it * 1_000L)) }
        assertEquals(Decision.NONE, g.offer(-399L, 200_000L))
    }

    // SAMplay 2.0.3: 8 s of the same answer (was 12) and 30 s between two corrections (was 60).
    @Test fun aSteadyDriftIsCorrectedAfterTheConfirmationWait() {
        val g = gate()
        assertEquals(Decision.SUSPECTED, g.offer(2_000L, 0L))
        assertEquals(Decision.CONFIRMING, g.offer(2_100L, 5_000L))
        assertEquals(Decision.CONFIRMING, g.offer(1_950L, 7_999L))
        assertEquals(Decision.APPLY, g.offer(2_000L, 8_000L))
    }

    @Test fun aChangingAnswerRestartsTheConfirmation() {
        val g = gate()
        assertEquals(Decision.SUSPECTED, g.offer(2_000L, 0L))
        assertEquals("300 ms apart is another drift", Decision.SUSPECTED, g.offer(2_300L, 10_000L))
        assertEquals(Decision.CONFIRMING, g.offer(2_300L, 15_000L))
        assertEquals(Decision.APPLY, g.offer(2_300L, 18_000L))
    }

    @Test fun correctionsWaitForTheCoolDown() {
        val g = gate()
        g.offer(-1_500L, 0L)
        assertEquals(Decision.APPLY, g.offer(-1_500L, 8_000L))
        // The audio now says the subtitle drifted again, right away.
        assertEquals(Decision.SUSPECTED, g.offer(900L, 9_000L))
        assertEquals(Decision.COOLING_DOWN, g.offer(900L, 20_000L))
        assertEquals(18_000L, g.cooldownLeftMs(20_000L))
        assertEquals(Decision.COOLING_DOWN, g.offer(900L, 37_999L))
        assertEquals(Decision.APPLY, g.offer(900L, 38_000L))
    }

    // SAMplay 2.0.4: with recognised words a 150-399 ms drift moves too, after 20 s of the same answer.
    @Test fun aSmallDriftFoundByWordsIsMovedAfterALongerWait() {
        val g = gate()
        assertEquals(Decision.NONE, g.offer(149L, 0L, precise = true))
        assertEquals(Decision.SUSPECTED, g.offer(-180L, 500L, precise = true))
        assertEquals(Decision.SUSPECTED, g.offer(343L, 1_000L, precise = true))
        assertEquals(Decision.CONFIRMING, g.offer(343L, 9_000L, precise = true))
        assertEquals(Decision.CONFIRMING, g.offer(350L, 20_999L, precise = true))
        assertEquals(Decision.APPLY, g.offer(343L, 21_000L, precise = true))
        // Speech detection alone still leaves it alone.
        assertEquals(Decision.NONE, gate().offer(343L, 0L))
    }

    @Test fun aSeekStartsTheConfirmationOver() {
        val g = gate()
        g.offer(3_000L, 0L)
        g.offer(3_000L, 7_000L)
        g.reset()
        assertEquals(Decision.SUSPECTED, g.offer(3_000L, 7_500L))
        assertEquals(Decision.CONFIRMING, g.offer(3_000L, 15_000L))
        assertEquals(Decision.APPLY, g.offer(3_000L, 15_500L))
    }

    // SAMplay 2.0.4: Z Nation 3x2 played at 23.976 against a 24 fps subtitle; the audio's mapping
    // had the rate, what was shown did not, and the gap grew 60 ms a minute.
    @Test fun aMappingAtAnotherSpeedIsMovedToFromASmallerGap() {
        val shown = SubtitleSyncModel(listOf(SubtitleSyncSegment(0L, 1.0, 0.0)))
        val faster = SubtitleSyncModel(listOf(SubtitleSyncSegment(0L, 24.0 / 23.976, 0.0)))
        val nudged = SubtitleSyncModel(listOf(SubtitleSyncSegment(0L, 1.0, -300.0)))
        assertTrue(AudioSyncFallback.rateDiffers(faster, shown, 240_000L))
        assertTrue("nothing shown yet runs at 1x", AudioSyncFallback.rateDiffers(faster, null, 240_000L))
        assertFalse(AudioSyncFallback.rateDiffers(nudged, shown, 240_000L))
        assertFalse(AudioSyncFallback.rateDiffers(faster, faster, 240_000L))
        val g = gate()
        assertEquals(Decision.SUSPECTED, g.offer(-250L, 0L, precise = true))
        assertEquals(Decision.CONFIRMING, g.offer(-258L, 8_000L, precise = true))
        assertEquals(Decision.APPLY, g.offer(-270L, 20_000L, precise = true))
    }
}
