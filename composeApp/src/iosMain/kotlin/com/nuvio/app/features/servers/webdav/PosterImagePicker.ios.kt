package com.nuvio.app.features.servers.webdav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.nuvio.app.samplay.IosFiles
import com.nuvio.app.samplay.IosPickers
import platform.Foundation.NSURL

private const val MAX_POSTER_BYTES = 15 * 1024 * 1024

/** SAMplay iOS: the photo library picker; the picture is copied into the app's files. */
@Composable
internal actual fun rememberPosterImagePicker(onPicked: (String) -> Unit): (() -> Unit)? {
    val currentOnPicked by rememberUpdatedState(onPicked)
    return remember {
        {
            IosPickers.pickImage { bytes ->
                val ok = bytes?.takeIf { it.size in 1..MAX_POSTER_BYTES } ?: return@pickImage
                val path = IosFiles.supportDir + "/webdav_posters/poster_${com.nuvio.app.samplay.nowMs()}.img"
                if (IosFiles.writeBytes(path, ok)) {
                    NSURL.fileURLWithPath(path).absoluteString?.let(currentOnPicked)
                }
            }
        }
    }
}
