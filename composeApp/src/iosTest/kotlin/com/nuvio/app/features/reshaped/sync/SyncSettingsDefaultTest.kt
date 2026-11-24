package com.nuvio.app.features.reshaped.sync

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SAMplay 2.0.3: settings and the Pro AI keys sync once the viewer links Google (iOS port). */
class SyncSettingsDefaultTest {
    @Test
    fun syncIsOnUntilTheViewerTurnsItOff() {
        assertTrue(ReshapedSyncIos.syncSettingsOn(stored = null))
        assertTrue(ReshapedSyncIos.syncSettingsOn(stored = true))
        assertFalse(ReshapedSyncIos.syncSettingsOn(stored = false))
    }
}
