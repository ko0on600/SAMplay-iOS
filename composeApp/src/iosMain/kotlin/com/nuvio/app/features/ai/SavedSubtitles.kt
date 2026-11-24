package com.nuvio.app.features.ai


import com.nuvio.app.samplay.Context
import com.nuvio.app.samplay.File
import com.nuvio.app.samplay.Locale

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
            .let { n -> if (n.lowercase().matches(Regex(".+\\.(srt|vtt|ass|ssa|sub|txt|ttml|dfxp)$"))) n.substringBeforeLast('.') else n }
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
            .trim().take(80).ifBlank { "subtitle" }

    /**
     * Writes [text] as [fileName] in the app's Documents/SAMplay/Subtitles, which the Files app shows
     * ("On My iPhone/iPad › SAMplay › SAMplay › Subtitles"), so any player can open it. Returns where it went.
     */
    fun save(context: Context, fileName: String, text: String): String {
        val dir = File(com.nuvio.app.samplay.IosFiles.documentsDir + "/" + FOLDER).apply { mkdirs() }
        val file = unique(File(dir, fileName))
        file.writeBytes(text.encodeToByteArray())
        return "SAMplay/" + FOLDER + "/" + file.name
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
