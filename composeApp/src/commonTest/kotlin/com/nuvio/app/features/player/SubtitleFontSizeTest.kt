package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class SubtitleFontSizeTest {
    @Test
    fun subtitlesGoUpToThreeTimesTheDefaultSize() {
        assertEquals(54, subtitleFontSizeRangeSp.last)
        assertEquals(300, subtitleFontSizePercent(subtitleFontSizeRangeSp.last))
        assertEquals(100, subtitleFontSizePercent(SubtitleStyleState.DEFAULT.fontSizeSp))
        // The settings slider and the player buttons move in steps of 2 and still reach the top.
        assertEquals(54, (subtitleFontSizeRangeSp.first..subtitleFontSizeRangeSp.last step 2).last)
    }

    @Test
    fun percentIsRoundedToTheNearestWhole() {
        assertEquals(67, subtitleFontSizePercent(12))
        assertEquals(111, subtitleFontSizePercent(20))
        assertEquals(200, subtitleFontSizePercent(36))
    }
}
