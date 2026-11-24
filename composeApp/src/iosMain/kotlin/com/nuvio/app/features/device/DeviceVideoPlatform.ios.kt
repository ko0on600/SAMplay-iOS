package com.nuvio.app.features.device

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/** SAMplay's device library is Android's; iOS shows no «On this device» source. */
internal actual object DeviceVideoPlatform {
    actual val isSupported: Boolean = false

    actual fun hasPermission(): Boolean = false

    actual fun isPartialAccess(): Boolean = false

    actual suspend fun queryVideos(): List<DeviceVideo> = emptyList()

    actual suspend fun canRead(uri: String): Boolean = false

    actual fun loadState(): String? = null

    actual fun saveState(payload: String) = Unit
}

@Composable
internal actual fun rememberDeviceVideoAccess(): DeviceVideoAccess =
    remember { DeviceVideoAccess(requestPermission = {}, openSettings = {}, openFile = null) }

@Composable
internal actual fun DeviceVideoThumbnail(video: DeviceVideo, modifier: Modifier) = Unit
