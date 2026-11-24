package com.nuvio.app.samplay

/** SAMplay iOS: starts the SAMplay-only parts (Google Drive sync, AI settings) once. */
object SamplayStartup {
    private var started = false

    fun start() {
        if (started) return
        started = true
        runCatching { com.nuvio.app.features.ai.AiSettings.initialize(IosContext) }
            .onFailure { samplayLog("SamplayStartup", "AI settings", it) }
        runCatching { com.nuvio.app.features.reshaped.sync.ReshapedSyncIos.install() }
            .onFailure { samplayLog("SamplayStartup", "sync", it) }
    }
}
