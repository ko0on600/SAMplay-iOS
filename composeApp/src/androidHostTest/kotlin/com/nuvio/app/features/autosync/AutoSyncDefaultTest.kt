package com.nuvio.app.features.autosync

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Nuvio RS (from SAMplay 1.8.8): "Auto Sync Subtitle" is on by default. With it off a subtitle
 * opened and was never synced, and nothing on screen said why.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AutoSyncDefaultTest {
    private val preferences = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("autosync-default-test", Context.MODE_PRIVATE)
    private val key = "preferred_subtitle_auto_sync_on_start"

    @Test fun autoSyncIsOnByDefault() {
        assertTrue(AutoSyncPreferencesRepository.DEFAULT_AUTO_SYNC_ON_START)
    }

    @Test fun anOldOffIsTurnedOnOnceForEveryProfile() {
        preferences.edit().clear()
            .putBoolean(key, false)
            .putBoolean("${key}_profile_2", false)
            .putBoolean("auto_sync_aggressive_mode", false)
            .commit()
        AutoSyncPreferencesAndroid.turnOnOnce(preferences, "migrated")
        assertTrue(preferences.getBoolean(key, false))
        assertTrue(preferences.getBoolean("${key}_profile_2", false))
        assertFalse("the other switches are left alone", preferences.getBoolean("auto_sync_aggressive_mode", true))
    }

    @Test fun whatIsChosenAfterwardsIsKept() {
        preferences.edit().clear().commit()
        AutoSyncPreferencesAndroid.turnOnOnce(preferences, "migrated")
        preferences.edit().putBoolean(key, false).commit()
        AutoSyncPreferencesAndroid.turnOnOnce(preferences, "migrated")
        assertFalse(preferences.getBoolean(key, true))
    }
}
