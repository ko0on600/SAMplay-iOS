package com.nuvio.app.features.ai

import com.nuvio.app.samplay.IOException
import com.nuvio.app.samplay.Locale

/**
 * SAMplay iOS: the languages the AI tools offer (the same list as Android's ML Kit Translate) and
 * their names. iOS has no ML Kit, so translation always goes through Google's online translation
 * ([GoogleTranslate]); the on-device fallback that Android uses when offline is not available.
 */
object MlTranslate {
    private val ALL = listOf(
        "af", "ar", "be", "bg", "bn", "ca", "cs", "cy", "da", "de", "el", "en", "eo", "es", "et", "fa",
        "fi", "fr", "ga", "gl", "gu", "he", "hi", "hr", "ht", "hu", "id", "is", "it", "ja", "ka", "kn",
        "ko", "lt", "lv", "mk", "mr", "ms", "mt", "nl", "no", "pl", "pt", "ro", "ru", "sk", "sl", "sq",
        "sv", "sw", "ta", "te", "th", "tl", "tr", "uk", "ur", "vi", "zh",
    )

    fun languages(): List<String> = ALL.sortedBy { displayName(it) }

    fun displayName(code: String, inLocale: Locale = Locale.getDefault()): String =
        Locale.forLanguageTag(code).getDisplayLanguage(inLocale).ifBlank { code }.replaceFirstChar { it.titlecase() }

    fun mlCode(tag: String?): String? = tag?.let { normalize(it) }?.takeIf { it in ALL }

    private fun normalize(tag: String) = when (tag.lowercase()) {
        "iw" -> "he"
        "jw" -> "jv"
        "nb", "nn" -> "no"
        "zh-hans", "zh-hant", "zh-cn", "zh-tw" -> "zh"
        "fil" -> "tl"
        else -> tag.lowercase().substringBefore('-').substringBefore('_')
    }

    /** No on-device language identification on iOS: Google's reply names the language instead. */
    suspend fun detectCues(cues: List<AiCue>): String? = null

    suspend fun translateCues(
        cues: List<AiCue>, src: String, tgt: String,
        onDownloading: () -> Unit, onProgress: (Int) -> Unit,
    ): List<AiCue> = throw IOException("On-device translation is not available on iOS")
}
