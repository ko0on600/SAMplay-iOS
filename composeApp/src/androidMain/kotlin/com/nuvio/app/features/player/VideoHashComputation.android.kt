package com.nuvio.app.features.player

import com.nuvio.app.features.device.DeviceVideoPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal actual suspend fun computeVideoHash(
    url: String,
    headers: Map<String, String>,
): Pair<String, Long>? {
    // SAMplay 2.0.3: a file on this device is hashed where it lies.
    if (url.startsWith("content:", ignoreCase = true) || url.startsWith("file:", ignoreCase = true)) {
        return withContext(Dispatchers.IO) { DeviceVideoPlatform.localVideoHash(url) }
    }
    val result = OpenSubtitlesHasher.compute(url, headers) ?: return null
    return result.hash to result.fileSize
}
