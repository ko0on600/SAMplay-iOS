package com.nuvio.app.features.player

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.nuvio.app.features.addons.httpRequestRaw
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
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

private const val MAX_SUBTITLE_BYTES = 8 * 1024 * 1024

/**
 * Loading picks any document (file managers label subtitle MIME types inconsistently) and copies
 * it into the cache, where the player reads it as a file:// subtitle. Saving goes through the
 * system "save as" dialog, so no storage permission is needed on any Android version.
 */
@Composable
actual fun rememberSubtitleFileActions(
    onLoaded: (AddonSubtitle) -> Unit,
    headersFor: (String) -> Map<String, String>,
): SubtitleFileActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnLoaded by rememberUpdatedState(onLoaded)
    val currentHeadersFor by rememberUpdatedState(headersFor)
    val fileSource = stringResource(Res.string.subtitle_file_source)
    val loadFailed = stringResource(Res.string.subtitle_load_failed)
    val saved = stringResource(Res.string.subtitle_saved)
    val saveFailed = stringResource(Res.string.subtitle_save_failed)
    val savedWithSync = stringResource(Res.string.subtitle_saved_with_sync)
    val syncFailed = stringResource(Res.string.subtitle_save_sync_failed)
    // SAMplay 2.0.3: the subtitle and the delay to write into its times (0: as it came).
    val pendingSave = remember { mutableStateOf<Pair<AddonSubtitle, Int>?>(null) }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val subtitle = withContext(Dispatchers.IO) {
                runCatching { importSubtitle(context, uri, fileSource) }.getOrNull()
            }
            if (subtitle == null) {
                Toast.makeText(context, loadFailed, Toast.LENGTH_LONG).show()
            } else {
                currentOnLoaded(subtitle)
            }
        }
    }
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val (subtitle, offsetMs) = pendingSave.value ?: return@rememberLauncherForActivityResult
        pendingSave.value = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val message = withContext(Dispatchers.IO) {
                runCatching {
                    val original = subtitleBytes(context, subtitle.url, currentHeadersFor(subtitle.url))
                    val bytes = if (offsetMs == 0) original else {
                        // The file as the user synced it, written as UTF-8 (Windows-1256 read too).
                        SubtitleTimeShift.shift(subtitleText(original).text, offsetMs.toLong())
                            ?.toByteArray(Charsets.UTF_8)
                            ?: return@runCatching syncFailed.also { runCatching { deleteDocument(context, uri) } }
                    }
                    if (context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null) {
                        if (offsetMs == 0) saved else savedWithSync
                    } else {
                        saveFailed
                    }
                }.getOrDefault(saveFailed)
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    return remember(openLauncher, saveLauncher) {
        SubtitleFileActions(
            load = { runCatching { openLauncher.launch(arrayOf("*/*")) } },
            save = { subtitle, fileName, offsetMs ->
                pendingSave.value = subtitle to offsetMs
                runCatching { saveLauncher.launch(fileName) }.onFailure { pendingSave.value = null }
            },
        )
    }
}

/** The empty file the "save as" dialog made, when nothing could be written into it. */
private fun deleteDocument(context: Context, uri: Uri) {
    android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri)
}

private fun importSubtitle(context: Context, uri: Uri, sourceLabel: String): AddonSubtitle? {
    val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        ?: uri.lastPathSegment?.substringAfterLast('/')
        ?: "subtitle"
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        input.readBytes().takeIf { it.size in 1..MAX_SUBTITLE_BYTES }
    } ?: return null
    val extension = subtitleExtension(displayName, bytes)
    val folder = File(context.cacheDir, "subtitles").apply { mkdirs() }
    val stem = displayName.substringBeforeLast('.', displayName).replace(Regex("""[^\p{L}\p{N}._ -]"""), "_").take(80)
    val file = File(folder, "${System.currentTimeMillis()}_$stem.$extension")
    val text = subtitleText(bytes)
    // Nuvio RS: an Arabic file saved as Windows-1256 is stored as UTF-8, which both players read.
    file.writeBytes(text.converted ?: bytes)
    return AddonSubtitle(
        id = "file:${file.name}",
        url = Uri.fromFile(file).toString(),
        language = subtitleLanguageFromFileName(displayName).takeIf { it != "und" }
            ?: subtitleLanguageFromText(text.text)
            ?: "und",
        display = displayName,
        addonName = sourceLabel,
    )
}

/** A subtitle's text, plus its UTF-8 bytes when the file had to be converted from Windows-1256. */
internal class SubtitleText(val text: String, val converted: ByteArray?)

internal fun subtitleText(bytes: ByteArray): SubtitleText {
    val strictUtf8 = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    try {
        return SubtitleText(strictUtf8.decode(ByteBuffer.wrap(bytes)).toString(), converted = null)
    } catch (_: CharacterCodingException) {
        // Not UTF-8: try the encoding Arabic subtitles are most often saved in.
    }
    val arabic = runCatching { String(bytes, Charset.forName("windows-1256")) }.getOrNull()
    val looksLikeSubtitle = arabic != null && (
        "-->" in arabic || "[Script Info]" in arabic || "Dialogue:" in arabic || Regex("""\{\d+\}\{\d+\}""").containsMatchIn(arabic)
    )
    if (arabic != null && looksLikeSubtitle && subtitleLanguageFromText(arabic) == "ar") {
        return SubtitleText(arabic, converted = arabic.toByteArray(Charsets.UTF_8))
    }
    return SubtitleText(String(bytes, Charsets.UTF_8), converted = null)
}

internal suspend fun subtitleBytes(context: Context, url: String, headers: Map<String, String>): ByteArray {
    val uri = Uri.parse(url)
    return when (uri.scheme?.lowercase()) {
        "file" -> File(requireNotNull(uri.path)).readBytes()
        "content" -> requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }
        else -> {
            val response = httpRequestRaw(
                method = "GET",
                url = url,
                headers = headers,
                body = "",
                maxResponseBodyBytes = MAX_SUBTITLE_BYTES,
            )
            check(response.status in 200..299 && response.bodyBytes.isNotEmpty())
            response.bodyBytes
        }
    }
}
