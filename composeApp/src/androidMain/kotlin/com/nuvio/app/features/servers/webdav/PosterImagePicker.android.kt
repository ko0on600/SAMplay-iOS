package com.nuvio.app.features.servers.webdav

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_POSTER_BYTES = 15 * 1024 * 1024

/**
 * The system photo picker (no storage permission). The picked image is copied into the app's
 * files, since the picker's own link stops working once the app restarts.
 */
@Composable
internal actual fun rememberPosterImagePicker(onPicked: (String) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnPicked by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val copied = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?.takeIf { it.size in 1..MAX_POSTER_BYTES } ?: return@runCatching null
                    val folder = File(context.filesDir, "webdav_posters").apply { mkdirs() }
                    val file = File(folder, "poster_${System.currentTimeMillis()}.img")
                    file.writeBytes(bytes)
                    Uri.fromFile(file).toString()
                }.getOrNull()
            }
            copied?.let(currentOnPicked)
        }
    }
    return remember(launcher) {
        { runCatching { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } }
    }
}
