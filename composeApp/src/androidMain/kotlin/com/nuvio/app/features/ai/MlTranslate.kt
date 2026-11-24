package com.nuvio.app.features.ai

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Free, on-device translation with Google ML Kit (Translate + Language ID). */
object MlTranslate {

    suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }

    fun languages(): List<String> = TranslateLanguage.getAllLanguages().sortedBy { displayName(it) }

    fun displayName(code: String, inLocale: Locale = Locale.getDefault()): String {
        val l = Locale.forLanguageTag(code)
        return l.getDisplayLanguage(inLocale).replaceFirstChar { it.titlecase(inLocale) }
    }

    fun mlCode(tag: String?): String? = tag?.let { TranslateLanguage.fromLanguageTag(normalize(it)) }

    private fun normalize(tag: String) = when (tag.lowercase()) {
        "iw" -> "he"
        "jw" -> "jv"
        "zh-hans", "zh-hant", "zh-cn", "zh-tw" -> "zh"
        "fil" -> "tl"
        else -> tag.lowercase().substringBefore('-')
    }

    /** Detects the language of a sample of subtitle text; returns a BCP-47 code or null. */
    suspend fun identify(text: String): String? {
        val client = LanguageIdentification.getClient(
            LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.4f).build()
        )
        return try {
            val code = client.identifyLanguage(text.take(2000)).await()
            if (code == "und") null else code
        } finally {
            client.close()
        }
    }

    suspend fun detectCues(cues: List<AiCue>): String? {
        if (cues.isEmpty()) return null
        val step = maxOf(1, cues.size / 40)
        val sample = cues.filterIndexed { i, _ -> i % step == 0 }.take(40).joinToString(" ") { it.text }
        return identify(sample)
    }

    private val translators = HashMap<String, Translator>()

    /** Get a translator, downloading the language models if needed. */
    suspend fun translator(src: String, tgt: String, onDownloading: () -> Unit = {}): Translator {
        val key = "$src>$tgt"
        translators[key]?.let { return it }
        val t = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build())
        onDownloading()
        t.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        translators[key] = t
        return t
    }

    suspend fun translateCues(
        cues: List<AiCue>, src: String, tgt: String,
        onDownloading: () -> Unit, onProgress: (Int) -> Unit,
    ): List<AiCue> {
        val t = translator(src, tgt, onDownloading)
        val out = ArrayList<AiCue>(cues.size)
        cues.forEachIndexed { i, c ->
            coroutineContext.ensureActive()
            val joined = c.text.replace('\n', ' ')
            val tr = runCatching { t.translate(joined).await() }.getOrNull()
            out += c.copy(translated = tr)
            if (i % 5 == 0) onProgress(i * 100 / cues.size)
        }
        onProgress(100)
        return out
    }
}
