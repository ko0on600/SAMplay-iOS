package com.nuvio.app.features.ai

import android.content.Context
import java.io.File
import java.util.Locale

/**
 * XPlayer's Pro translation result: a `translate_<Language>_<subtitle name>_Pro.srt` file kept for
 * the video, so the same translation is loaded again instead of being redone, and is listed with
 * the video's subtitles. Nuvio keeps them in its own folder (one per video), which needs no
 * storage permission.
 */
object ProTranslationFiles {
    private const val PREFIX = "translate_"
    private const val SUFFIX = "_Pro.srt"

    /** "Arabic", "English"… (XPlayer's file names use the English language name). */
    fun languageName(code: String): String =
        Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifBlank { code }.replace(' ', '_')

    fun fileName(target: String, subtitleName: String?): String {
        val base = subtitleName?.substringBeforeLast('.')?.trim().orEmpty()
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_").take(80).ifBlank { "subtitle" }
        return PREFIX + languageName(target) + "_" + base + SUFFIX
    }

    private fun dir(context: Context, videoKey: String): File {
        val root = context.getExternalFilesDir("translations") ?: File(context.filesDir, "translations")
        return File(root, videoKey.hashCode().toUInt().toString(16))
    }

    fun file(context: Context, videoKey: String, target: String, subtitleName: String?): File =
        File(dir(context, videoKey), fileName(target, subtitleName))

    /** The video's Pro translations, newest first. */
    fun list(context: Context, videoKey: String?): List<File> {
        if (videoKey == null) return emptyList()
        return dir(context, videoKey).listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }
            .orEmpty().sortedByDescending { it.lastModified() }
    }

    /** Target language of a file this object named (null if it can't tell). */
    fun targetOf(file: File): String? {
        val lang = file.name.removePrefix(PREFIX).substringBefore('_')
        return Locale.getAvailableLocales().firstOrNull { it.getDisplayLanguage(Locale.ENGLISH).replace(' ', '_') == lang }?.language
    }

    /** Writes the translated lines (the cue timing of the original). */
    fun save(context: Context, videoKey: String, target: String, subtitleName: String?, cues: List<AiCue>): File? = runCatching {
        val f = file(context, videoKey, target, subtitleName)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(AiSrt.toSrt(cues, translated = true))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        f
    }.getOrNull()

    /**
     * The saved translation of [cues] ([subtitleName] into [target]), as cues with
     * [AiCue.translated] filled, when it still matches them line for line.
     */
    fun load(context: Context, videoKey: String, target: String, subtitleName: String?, cues: List<AiCue>): List<AiCue>? {
        val f = file(context, videoKey, target, subtitleName)
        if (!f.isFile) return null
        val saved = runCatching { AiSrt.parse(f.readText()) }.getOrNull() ?: return null
        if (saved.size != cues.size || saved.indices.any { saved[it].startMs != cues[it].startMs }) return null
        return cues.mapIndexed { i, c -> c.copy(translated = saved[i].text) }
    }
}
