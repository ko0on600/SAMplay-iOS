package com.nuvio.app.features.home.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PosterGridColumnsTest {
    private fun columns(width: Dp, height: Dp) =
        posterGridColumnsFittingHeight(posterGridColumnCountForWidth(width), width, height)

    // SAMplay 2.0.3: the user's phone held sideways is 832 x 384 dp.
    @Test
    fun aPhoneHeldSidewaysGetsPostersShorterThanTheScreen() {
        val count = columns(832.dp, 384.dp)
        assertEquals(5, count)
        val posterWidth = (832.dp - 32.dp - 12.dp * (count - 1)) / count
        assertTrue(posterWidth * 1.5f < 384.dp * 0.7f, "a poster $posterWidth wide is too tall")
    }

    @Test
    fun aPhoneHeldUprightKeepsItsColumns() {
        assertEquals(3, columns(384.dp, 832.dp))
    }

    @Test
    fun aTabletKeepsItsColumns() {
        assertEquals(6, columns(1280.dp, 800.dp))
        assertEquals(4, columns(900.dp, 1200.dp))
    }

    @Test
    fun anUnboundedHeightKeepsTheWidthRule() {
        assertEquals(3, columns(832.dp, Dp.Infinity))
    }
}
