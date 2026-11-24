package com.nuvio.app.features.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings of the audio subtitle sync fallback (see AutoSync). Persistence is injected by the
 * platform, so Nuvio's own player settings are left untouched.
 */
internal object AudioSyncSettings {
    private val _fallbackEnabled = MutableStateFlow(true)

    /** Sync to the audio when AutoSync keeps a subtitle's original timing. */
    val fallbackEnabled: StateFlow<Boolean> = _fallbackEnabled.asStateFlow()

    private val _samplingOnMobileData = MutableStateFlow(false)

    /** Read short parts of the film ahead of playback on metered networks too. */
    val samplingOnMobileData: StateFlow<Boolean> = _samplingOnMobileData.asStateFlow()

    private val _showStatistics = MutableStateFlow(false)

    /** Show the live statistics panel in the player's top-left corner while audio sync runs. */
    val showStatistics: StateFlow<Boolean> = _showStatistics.asStateFlow()

    private val _driftWatchEnabled = MutableStateFlow(true)

    /**
     * SAMplay TV 1.0.2: keep comparing a synced subtitle with the audio while the video plays, and
     * correct it on its own when it drifts mid-episode.
     */
    val driftWatchEnabled: StateFlow<Boolean> = _driftWatchEnabled.asStateFlow()

    private var save: (key: String, value: Boolean) -> Unit = { _, _ -> }

    fun installPersistence(load: (key: String) -> Boolean?, save: (key: String, value: Boolean) -> Unit) {
        this.save = save
        _fallbackEnabled.value = load(FALLBACK_ENABLED) ?: true
        _samplingOnMobileData.value = load(SAMPLING_ON_MOBILE_DATA) ?: false
        _showStatistics.value = load(SHOW_STATISTICS) ?: false
        _driftWatchEnabled.value = load(DRIFT_WATCH) ?: true
    }

    fun setDriftWatchEnabled(enabled: Boolean) {
        _driftWatchEnabled.value = enabled
        save(DRIFT_WATCH, enabled)
    }

    fun setFallbackEnabled(enabled: Boolean) {
        _fallbackEnabled.value = enabled
        save(FALLBACK_ENABLED, enabled)
    }

    fun setSamplingOnMobileData(enabled: Boolean) {
        _samplingOnMobileData.value = enabled
        save(SAMPLING_ON_MOBILE_DATA, enabled)
    }

    fun setShowStatistics(enabled: Boolean) {
        _showStatistics.value = enabled
        save(SHOW_STATISTICS, enabled)
    }

    private const val FALLBACK_ENABLED = "audio_sync_fallback_enabled"
    private const val SAMPLING_ON_MOBILE_DATA = "audio_sync_sampling_on_mobile_data"
    private const val SHOW_STATISTICS = "audio_sync_show_statistics"
    private const val DRIFT_WATCH = "audio_sync_drift_watch"
}
