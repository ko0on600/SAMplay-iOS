package com.nuvio.app.features.player.floating

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SAMplay 2.0.3: the floating window cut the card's shadow off in a straight line, a grey box
 * around the picture on white apps. The window's edge now fades; the fade must stay clear of the
 * card even while it is held and grown.
 */
class ShadowFadeTest {
    // The user's phone: 3.75 px per dp.
    private val dp = 3.75f
    private val pad = 18f * dp
    private val gap = 2f * dp
    private val maxFade = 12f * dp

    private fun fade(cardW: Float, cardH: Float) =
        shadowFadeWidth(pad, cardW, cardH, MotionSpec.LIFT_SCALE, gap, maxFade)

    @Test
    fun aUsualCardFadesMostOfTheShadowRoom() {
        // 700 x 394 grows 17.5 px a side when held: 67.5 - 17.5 - 7.5 = 42.5.
        assertEquals(42.5f, fade(700f, 394f), 0.01f)
    }

    @Test
    fun theFadeNeverReachesTheHeldCard() {
        var size = 120f
        while (size <= 3000f) {
            val grown = size * (MotionSpec.LIFT_SCALE - 1f) / 2f
            val fade = fade(size, size * 9f / 16f)
            val room = pad - grown - gap
            assertTrue(fade >= 0f, "fade $fade for $size")
            // A card too big to grow inside the room gets no fade at all.
            assertTrue(fade <= maxOf(room, 0f) + 0.01f, "fade $fade reaches a $size card grown $grown")
            size += 40f
        }
    }

    @Test
    fun aTallCardCountsItsHeight() {
        assertEquals(fade(900f, 500f), fade(500f, 900f), 0.01f)
    }

    @Test
    fun aSmallCardStopsAtTheMostFade() {
        assertEquals(maxFade, fade(200f, 112f), 0.01f)
    }

    @Test
    fun aCardTooBigToGrowInsideTheRoomGetsNoFade() {
        assertEquals(0f, fade(3000f, 1688f), 0.01f)
    }
}
