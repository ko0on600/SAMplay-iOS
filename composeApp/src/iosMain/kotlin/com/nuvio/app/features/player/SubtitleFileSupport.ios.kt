package com.nuvio.app.features.player

import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.autosync.autoSyncDecodeBytes
import com.nuvio.app.samplay.IosFiles
import platform.Foundation.NSURL

internal const val MAX_SUBTITLE_BYTES = 8 * 1024 * 1024

/** A subtitle's text, plus its UTF-8 bytes when the file had to be converted from Windows-1256. */
internal class SubtitleText(val text: String, val converted: ByteArray?)

internal fun subtitleText(bytes: ByteArray): SubtitleText {
    autoSyncDecodeBytes(bytes, 0, bytes.size, "UTF-8", strict = true)?.let { return SubtitleText(it, converted = null) }
    // Not UTF-8: try the encoding Arabic subtitles are most often saved in.
    val arabic = autoSyncDecodeBytes(bytes, 0, bytes.size, "windows-1256", strict = false)
    val looksLikeSubtitle = arabic != null && (
        "-->" in arabic || "[Script Info]" in arabic || "Dialogue:" in arabic || Regex("""\{\d+\}\{\d+\}""").containsMatchIn(arabic)
    )
    if (arabic != null && looksLikeSubtitle && subtitleLanguageFromText(arabic) == "ar") {
        return SubtitleText(arabic, converted = arabic.encodeToByteArray())
    }
    return SubtitleText(bytes.decodeToString(), converted = null)
}

/** The path of a file:// link (or a plain path), else null. */
internal fun localPathOf(url: String): String? = when {
    url.startsWith("file:", ignoreCase = true) -> NSURL.URLWithString(url)?.path ?: url.removePrefix("file://")
    url.startsWith("/") -> url
    else -> null
}

internal suspend fun subtitleBytes(url: String, headers: Map<String, String>): ByteArray {
    localPathOf(url)?.let { path -> return requireNotNull(IosFiles.readBytes(path)) { "cannot read $path" } }
    val response = httpRequestRaw(
        method = "GET",
        url = url,
        headers = headers,
        body = "",
        maxResponseBodyBytes = MAX_SUBTITLE_BYTES,
    )
    check(response.status in 200..299 && response.bodyBytes.isNotEmpty())
    return response.bodyBytes
}

/** Copies a picked subtitle into the cache (as UTF-8 when it was Windows-1256), like Android's import. */
internal fun importSubtitleFile(path: String, displayName: String, sourceLabel: String): AddonSubtitle? {
    val bytes = IosFiles.readBytes(path)?.takeIf { it.size in 1..MAX_SUBTITLE_BYTES } ?: return null
    val extension = subtitleExtension(displayName, bytes)
    val folder = IosFiles.cacheDir + "/subtitles"
    IosFiles.ensureDir(folder)
    val stem = displayName.substringBeforeLast('.', displayName).replace(Regex("""[^\p{L}\p{N}._ -]"""), "_").take(80)
    val file = "$folder/${com.nuvio.app.samplay.nowMs()}_$stem.$extension"
    val text = subtitleText(bytes)
    if (!IosFiles.writeBytes(file, text.converted ?: bytes)) return null
    return AddonSubtitle(
        id = "file:${file.substringAfterLast('/')}",
        url = NSURL.fileURLWithPath(file).absoluteString ?: "file://$file",
        language = subtitleLanguageFromFileName(displayName).takeIf { it != "und" }
            ?: subtitleLanguageFromText(text.text)
            ?: "und",
        display = displayName,
        addonName = sourceLabel,
    )
}
