package com.nuvio.app.features.player.volumeboost

import kotlin.test.Test
import kotlin.test.assertEquals

/** SAMplay 2.0.3: the volume bar reaches 300%. */
class VolumeBoostLevelTest {
    @Test
    fun eachDoublingAddsTenDecibels() {
        assertEquals(0f, boostGainDbForLevel(1f), 0.001f)
        assertEquals(10f, boostGainDbForLevel(2f), 0.001f)
        assertEquals(VolumeBoost.MAX_GAIN_DB, boostGainDbForLevel(3f), 0.001f)
        assertEquals(15.85f, VolumeBoost.MAX_GAIN_DB, 0.01f)
    }

    @Test
    fun theGainStaysWithinTheBoostRange() {
        assertEquals(0f, boostGainDbForLevel(0.5f), 0.001f)
        assertEquals(VolumeBoost.MAX_GAIN_DB, boostGainDbForLevel(5f), 0.001f)
    }

    @Test
    fun theLevelComesBackFromItsGain() {
        for (level in listOf(1f, 1.25f, 1.5f, 2f, 2.5f, 3f)) {
            assertEquals(level, boostLevelForGainDb(boostGainDbForLevel(level)), 0.001f)
        }
    }

    @Test
    fun theBoostZoneIsAsLongAsZeroToOneHundred() {
        assertEquals(1f, levelToSwipe(1f), 0.001f)
        assertEquals(1.5f, levelToSwipe(2f), 0.001f)
        assertEquals(2f, levelToSwipe(3f), 0.001f)
        assertEquals(0.4f, levelToSwipe(0.4f), 0.001f)
        for (level in listOf(0f, 0.6f, 1f, 1.7f, 3f)) {
            assertEquals(level, swipeToLevel(levelToSwipe(level)), 0.001f)
        }
    }

    @Test
    fun theBarPutsOneHundredPercentHalfWayUp() {
        assertEquals(0.5f, levelBarShare(1f, 3f), 0.001f)
        assertEquals(1f, levelBarShare(3f, 3f), 0.001f)
        assertEquals(0.25f, levelBarShare(0.5f, 3f), 0.001f)
        assertEquals(0.5f, levelBarShare(0.5f, 1f), 0.001f)
        assertEquals(1f, levelBarShare(1.4f, 1f), 0.001f)
    }
}
