package com.nuvio.app.features.player.audiosync

import android.content.Context

/** Device limits for the audio sync (from NuvioTV Reshaped 1.0.0-autosync.20). */
internal object SpotConnections {
    /** 2 GB boxes report a little under 2 GB; the same line NuvioTV uses (see DeviceMemory). */
    fun isLowMemoryTv(context: Context): Boolean = com.nuvio.app.core.diagnostics.DeviceMemory.isLow(context)
}
