package com.nuvio.app.features.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Nuvio RS: PikPak-style background audio and floating window.
 *
 * With background audio on, leaving the app keeps the sound playing behind the media
 * notification instead of shrinking the video into a floating window; with it off the video
 * floats as before. The floating-window button floats the video right away.
 */
object PlayerFloatingModes {
    private var loaded = false
    private val _backgroundAudio = MutableStateFlow(false)

    val backgroundAudio: StateFlow<Boolean>
        get() {
            ensureLoaded()
            return _backgroundAudio.asStateFlow()
        }

    /** Floats the video now; null where the device can't (set by the platform player). */
    var enterFloatingWindow: (() -> Unit)? by mutableStateOf(null)

    fun isBackgroundAudioEnabled(): Boolean {
        ensureLoaded()
        return _backgroundAudio.value
    }

    fun toggleBackgroundAudio(): Boolean {
        ensureLoaded()
        val enabled = !_backgroundAudio.value
        _backgroundAudio.value = enabled
        PlayerSettingsStorage.saveBackgroundAudio(enabled)
        return enabled
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        _backgroundAudio.value = PlayerSettingsStorage.loadBackgroundAudio() ?: false
    }
}
