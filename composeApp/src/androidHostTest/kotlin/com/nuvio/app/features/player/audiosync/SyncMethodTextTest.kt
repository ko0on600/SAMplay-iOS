package com.nuvio.app.features.player.audiosync

import org.junit.Assert.assertEquals
import org.junit.Test

/** SAMplay 2.0.3: the statistics time the first lock, not the latest correction. */
class SyncMethodTextTest {
    @Test fun theFirstLockIsTimed() {
        assertEquals("speech recognition in 1s", syncMethodText("speech recognition", 1_900L))
        assertEquals("speech detection in 0s", syncMethodText("speech detection", 0L))
    }

    @Test fun beforeAnyLockOnlyTheMethodShows() {
        assertEquals("speech recognition", syncMethodText("speech recognition", -1L))
    }
}
