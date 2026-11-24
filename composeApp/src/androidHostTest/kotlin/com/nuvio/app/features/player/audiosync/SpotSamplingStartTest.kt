package com.nuvio.app.features.player.audiosync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SAMplay 2.0.3: when sampling across the film may start. */
class SpotSamplingStartTest {
    private val fiveSeconds = (5_000 / SpeechTimeline.FRAME_DURATION_MS).toInt()

    @Test fun aStreamWaitsForPlaybackToHaveBuffered() {
        assertFalse(spotSamplingMayStart(localFile = false, knownFrames = 0))
        assertFalse(spotSamplingMayStart(localFile = false, knownFrames = fiveSeconds - 1))
        assertTrue(spotSamplingMayStart(localFile = false, knownFrames = fiveSeconds))
    }

    // A subtitle picked mid-episode, with the player buffered far ahead and not reading.
    @Test fun aFileOnTheDeviceStartsAtOnce() {
        assertTrue(spotSamplingMayStart(localFile = true, knownFrames = 0))
    }
}
