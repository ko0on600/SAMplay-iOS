package com.nuvio.app.features.device

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** SAMplay: the platform side of the device library (Android's media store). */
internal expect object DeviceVideoPlatform {
    /** False where the platform has no device library (iOS). */
    val isSupported: Boolean

    /** The viewer let the app read the device's videos (all, or the ones they picked). */
    fun hasPermission(): Boolean

    /** Android 14+: the viewer allowed only the videos they picked. */
    fun isPartialAccess(): Boolean

    /** Every video the app may read, newest first. Runs off the main thread. */
    suspend fun queryVideos(): List<DeviceVideo>

    /** True when the file can still be opened (a file another app shared may not be, later). */
    suspend fun canRead(uri: String): Boolean

    fun loadState(): String?

    fun saveState(payload: String)
}

/** What the device library asks of the platform's screens. */
internal class DeviceVideoAccess(
    /** Asks for the videos permission; the library reloads itself with the answer. */
    val requestPermission: () -> Unit,
    /** The app's page in the system settings, for a permission that was refused for good. */
    val openSettings: () -> Unit,
    /** The system file picker for one video; null where there is none. */
    val openFile: (() -> Unit)?,
)

/** Also reloads the library when the screen comes back (a permission given in Settings). */
@Composable
internal expect fun rememberDeviceVideoAccess(): DeviceVideoAccess

/** The video's frame picture, or nothing when the platform has none for it. */
@Composable
internal expect fun DeviceVideoThumbnail(video: DeviceVideo, modifier: Modifier = Modifier)
