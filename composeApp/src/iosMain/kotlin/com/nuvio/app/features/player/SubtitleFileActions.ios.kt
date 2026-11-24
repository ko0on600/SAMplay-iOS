package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.nuvio.app.samplay.IosFiles
import com.nuvio.app.samplay.IosPickers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.subtitle_file_source
import nuvio.composeapp.generated.resources.subtitle_load_failed
import nuvio.composeapp.generated.resources.subtitle_save_failed
import nuvio.composeapp.generated.resources.subtitle_save_sync_failed
import nuvio.composeapp.generated.resources.subtitle_saved
import nuvio.composeapp.generated.resources.subtitle_saved_with_sync
import org.jetbrains.compose.resources.stringResource

/**
 * SAMplay iOS: loading picks any file in the Files app and copies it into the cache, where the
 * player reads it as a file:// subtitle. Saving writes the file (with the delay applied when set)
 * and opens the Files app's "save to" folder picker.
 */
@Composable
actual fun rememberSubtitleFileActions(
    onLoaded: (AddonSubtitle) -> Unit,
    headersFor: (String) -> Map<String, String>,
): SubtitleFileActions {
    val scope = rememberCoroutineScope()
    val currentOnLoaded by rememberUpdatedState(onLoaded)
    val currentHeadersFor by rememberUpdatedState(headersFor)
    val fileSource = stringResource(Res.string.subtitle_file_source)
    val loadFailed = stringResource(Res.string.subtitle_load_failed)
    val saved = stringResource(Res.string.subtitle_saved)
    val saveFailed = stringResource(Res.string.subtitle_save_failed)
    val savedWithSync = stringResource(Res.string.subtitle_saved_with_sync)
    val syncFailed = stringResource(Res.string.subtitle_save_sync_failed)

    return remember {
        SubtitleFileActions(
            load = {
                IosPickers.pickFile { path, name ->
                    if (path == null) return@pickFile
                    scope.launch {
                        val subtitle = withContext(Dispatchers.IO) {
                            runCatching { importSubtitleFile(path, name ?: "subtitle", fileSource) }.getOrNull()
                        }
                        if (subtitle == null) IosPickers.toast(loadFailed) else currentOnLoaded(subtitle)
                    }
                }
            },
            save = { subtitle, fileName, offsetMs ->
                scope.launch {
                    val prepared: Pair<String?, String?> = withContext(Dispatchers.IO) {
                        runCatching {
                            val original = subtitleBytes(subtitle.url, currentHeadersFor(subtitle.url))
                            val bytes = if (offsetMs == 0) original else {
                                SubtitleTimeShift.shift(subtitleText(original).text, offsetMs.toLong())
                                    ?.encodeToByteArray()
                                    ?: return@runCatching null to syncFailed
                            }
                            val dir = IosFiles.cacheDir + "/subtitle_export/" + com.nuvio.app.samplay.nowMs()
                            IosFiles.ensureDir(dir)
                            val safe = fileName.replace('/', '_').ifBlank { "subtitle.srt" }
                            val path = "$dir/$safe"
                            if (IosFiles.writeBytes(path, bytes)) path to null else null to saveFailed
                        }.getOrDefault(null to saveFailed)
                    }
                    val path = prepared.first
                    if (path == null) {
                        IosPickers.toast(prepared.second ?: saveFailed)
                        return@launch
                    }
                    IosPickers.exportFile(path) { ok ->
                        if (ok) IosPickers.toast(if (offsetMs == 0) saved else savedWithSync)
                    }
                }
            },
        )
    }
}
