package com.nuvio.app.features.autosync

import android.content.Context
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.features.autosync.bubble.AutoSyncBubbleToastsAndroid
import com.nuvio.app.features.player.PlaybackBufferAndroid
import com.nuvio.app.features.player.audiosync.AudioSyncFallback

internal object AutoSyncPreferencesAndroid {
    private const val preferencesName = "nuvio_autosync_settings"
    private const val preferredSubtitleAutoSyncOnStartKey =
        "preferred_subtitle_auto_sync_on_start"
    private const val aggressiveModeKey = "auto_sync_aggressive_mode"
    private const val debugLogsEnabledKey = "auto_sync_debug_logs_enabled"
    private const val syncToleranceMsKey = "auto_sync_tolerance_ms"
    // Nuvio RS 0.6.12-sam2 (from SAMplay 1.8.8): the one-time "turn Auto Sync Subtitle on" mark.
    private const val defaultOnMigratedKey = "nuvio_rs_0612_sam2_auto_sync_default_on"

    /**
     * Once per install: every stored "Auto Sync Subtitle" choice becomes on, and [migratedKey]
     * remembers it was done, so whatever is chosen afterwards is kept.
     */
    internal fun turnOnOnce(preferences: android.content.SharedPreferences, migratedKey: String) {
        if (preferences.getBoolean(migratedKey, false)) return
        val editor = preferences.edit().putBoolean(migratedKey, true)
        preferences.all.keys.filter { it.startsWith(preferredSubtitleAutoSyncOnStartKey) }.forEach { editor.putBoolean(it, true) }
        editor.apply()
    }

    fun initialize(context: Context) {
        AudioSyncFallback.initialize(context)
        PlaybackBufferAndroid.initialize(context)
        AutoSyncBubbleToastsAndroid.initialize(context)
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        // Nuvio RS (from SAMplay 1.8.8): the default was off, so an "off" stored by a profile sync
        // was never chosen. It is turned on once; whatever is chosen afterwards is kept.
        turnOnOnce(preferences, defaultOnMigratedKey)
        AutoSyncPreferencesRepository.installPersistence(
            load = {
                val key = ProfileScopedKey.of(preferredSubtitleAutoSyncOnStartKey)
                if (preferences.contains(key)) {
                    preferences.getBoolean(key, false)
                } else {
                    null
                }
            },
            save = { enabled ->
                preferences
                    .edit()
                    .putBoolean(ProfileScopedKey.of(preferredSubtitleAutoSyncOnStartKey), enabled)
                    .apply()
            },
            loadAggressiveMode = {
                val key = ProfileScopedKey.of(aggressiveModeKey)
                if (preferences.contains(key)) {
                    preferences.getBoolean(key, true)
                } else {
                    null
                }
            },
            saveAggressiveMode = { enabled ->
                preferences
                    .edit()
                    .putBoolean(ProfileScopedKey.of(aggressiveModeKey), enabled)
                    .apply()
            },
            loadDebugLogs = {
                val key = ProfileScopedKey.of(debugLogsEnabledKey)
                if (preferences.contains(key)) {
                    preferences.getBoolean(key, false)
                } else {
                    null
                }
            },
            saveDebugLogs = { enabled ->
                preferences
                    .edit()
                    .putBoolean(ProfileScopedKey.of(debugLogsEnabledKey), enabled)
                    .apply()
            },
            loadSyncToleranceMs = {
                val key = ProfileScopedKey.of(syncToleranceMsKey)
                if (preferences.contains(key)) preferences.getInt(key, 0) else null
            },
            saveSyncToleranceMs = { toleranceMs ->
                preferences
                    .edit()
                    .putInt(ProfileScopedKey.of(syncToleranceMsKey), toleranceMs)
                    .apply()
            },
        )
    }
}
