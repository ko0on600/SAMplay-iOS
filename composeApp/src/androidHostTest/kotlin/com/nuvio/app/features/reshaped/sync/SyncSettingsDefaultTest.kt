package com.nuvio.app.features.reshaped.sync

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SAMplay 2.0.3: settings and the Pro AI keys sync once the viewer links Google, as on the TV. */
class SyncSettingsDefaultTest {
    @Test
    fun syncIsOnUntilTheViewerTurnsItOff() {
        assertTrue(ReshapedSyncAndroid.syncSettingsOn(stored = null))
        assertTrue(ReshapedSyncAndroid.syncSettingsOn(stored = true))
        assertFalse(ReshapedSyncAndroid.syncSettingsOn(stored = false))
    }
}
