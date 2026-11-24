package com.nuvio.app.features.ai

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.Locale

/**
 * SAMplay: a translated subtitle kept on the device, where any player or file manager finds it:
 * Download/SAMplay/Subtitles. Names follow XPlayer: translate_Arabic_<subtitle>[_Pro].srt, and
 * asr_translate_… for a translation of what was heard in the video.
 */
object SavedSubtitles {
    const val FOLDER = "SAMplay/Subtitles"

    fun fileName(target: String, subtitleName: String?, pro: Boolean, heard: Boolean): String {
        val language = Locale.forLanguageTag(target).getDisplayLanguage(Locale.ENGLISH).ifBlank { target }.replace(' ', '_')
        val base = cleanBase(subtitleName)
        return (if (heard) "asr_" else "") + "translate_" + language + "_" + base + (if (pro) "_Pro" else "") + ".srt"
    }

    internal fun cleanBase(name: String?): String =
        name?.trim().orEmpty()
            .let { n -> if (n.lowercase(Locale.ROOT).matches(Regex(".+\\.(srt|vtt|ass|ssa|sub|txt|ttml|dfxp)$"))) n.substringBeforeLast('.') else n }
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
            .trim().take(80).ifBlank { "subtitle" }

    /** Writes [text] as [fileName]; returns where it went (for the message), or throws. */
    fun save(context: Context, fileName: String, text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val relative = Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER
            // A type MediaStore keeps the ".srt" for; some versions add ".txt" to text/plain.
            for (mime in listOf("application/x-subrip", "application/octet-stream")) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = runCatching { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }.getOrNull() ?: continue
                try {
                    resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("no output stream")
                    resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                } catch (e: Exception) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw e
                }
                val shown = runCatching {
                    resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    }
                }.getOrNull() ?: fileName
                return "$relative/$shown"
            }
        }
        // Android 9 and older (storage permission), else the app's own folder.
        @Suppress("DEPRECATION")
        val public = runCatching {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER).apply { mkdirs() }
        }.getOrNull()?.takeIf { it.isDirectory && it.canWrite() }
        val dir = public ?: File(context.getExternalFilesDir(null) ?: context.filesDir, "Subtitles").apply { mkdirs() }
        val file = unique(File(dir, fileName))
        file.writeBytes(bytes)
        return file.absolutePath
    }

    private fun unique(f: File): File {
        if (!f.exists()) return f
        val base = f.name.substringBeforeLast('.')
        val ext = f.name.substringAfterLast('.', "")
        var n = 1
        while (true) {
            val c = File(f.parentFile, "$base ($n)" + if (ext.isNotEmpty()) ".$ext" else "")
            if (!c.exists()) return c
            n++
        }
    }
}
