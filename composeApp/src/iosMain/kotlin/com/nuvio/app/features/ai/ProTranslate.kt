package com.nuvio.app.features.ai

import kotlin.concurrent.Volatile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import com.nuvio.app.samplay.JSONArray
import com.nuvio.app.samplay.JSONObject
import com.nuvio.app.samplay.IOException
import com.nuvio.app.samplay.Locale
import com.nuvio.app.samplay.blockingHttp
import com.nuvio.app.samplay.AtomicInteger
import com.nuvio.app.samplay.AtomicReference
import kotlin.coroutines.coroutineContext

/**
 * "Pro AI" subtitle translation: a large language model translates the dialogue in context (whole
 * scenes, the title of the film, the lines just before), like a human subtitler, instead of line by
 * line. The service is the user's pick (Claude, ChatGPT, Gemini or Grok, [ProProvider]), on the
 * user's own API key for it.
 *
 * Cues go out in batches, several at a time; each batch also sees the original lines just before
 * it, so a sentence split across cues or a running joke keeps its meaning across batch borders. The
 * reply is a JSON object (structured output) with exactly one translation per cue.
 */
object ProTranslate {
    val DEFAULT_MODEL = ProProvider.CLAUDE.defaultModel

    private const val BATCH = 50
    private const val CONTEXT_LINES = 8
    private const val PARALLEL = 4

    class NotConfigured : IllegalStateException("no API key")
    class InvalidKey : IllegalStateException("invalid API key")
    class Refused(val category: String?) : IllegalStateException("refused")

    val isConfigured get() = AiSettings.proAiKey.isNotBlank()

    /** The last error of a batch that kept failing, reported when no batch got through at all. */
    private val lastError = AtomicReference<Exception?>(null)

    /** English name of [code] for the prompt ("Arabic", "Brazilian Portuguese"…). */
    private fun languageName(code: String): String = when (code) {
        "pt-BR" -> "Brazilian Portuguese"
        "zh-TW" -> "Traditional Chinese"
        "zh" -> "Simplified Chinese"
        else -> Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifBlank { code }
    }

    private val SYSTEM = """
        You are a professional film and TV subtitle translator. You translate subtitle cues so they read
        like natural, idiomatic dialogue written by a native subtitler, not word-for-word.
        Keep the meaning, tone, register, humour and emotion of each line. Use the context (the title,
        the preceding lines, the rest of the batch) to resolve ambiguity, pronouns, gender and slang.
        Keep names, places and brands; transliterate them only where the target language normally does.
        Each output line translates exactly the cue with the same index: never merge, split, reorder,
        drop or add cues. A cue that has a line break keeps a line break in the same place when it
        separates two speakers ("- ..." lines). Keep cues short enough to read on screen.
        Sound effects or captions such as [music] are translated too. Return only the JSON object.
    """.trimIndent()

    private fun schema(): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject().put("lines", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))))
        .put("required", JSONArray().put("lines"))
        .put("additionalProperties", false)

    /**
     * Translates every cue into [target] (BCP-47, e.g. "ar"). [title] (film/episode) gives context.
     * Returns the cues with [AiCue.translated] filled; a batch that keeps failing keeps the
     * original text for its lines (and is retried once in halves first).
     */
    suspend fun translate(
        cues: List<AiCue>,
        target: String,
        title: String?,
        sourceLang: String?,
        onProgress: (Int) -> Unit,
    ): List<AiCue> = withContext(Dispatchers.IO) {
        if (!isConfigured) throw NotConfigured()
        lastError.set(null)
        val translatedLines = AtomicInteger(0)
        val out = cues.map { it.text }.toMutableList()
        val done = AtomicInteger(0)
        val gate = Semaphore(PARALLEL)
        val batches = cues.indices.chunked(BATCH)
        onProgress(0)
        coroutineScope {
            batches.map { idx ->
                async {
                    gate.withPermit {
                        val result = translateRange(cues, idx.first(), idx.last() + 1, target, title, sourceLang)
                        result.forEachIndexed { k, s ->
                            if (!s.isNullOrBlank()) {
                                out[idx.first() + k] = s
                                translatedLines.incrementAndGet()
                            }
                        }
                        onProgress((done.addAndGet(idx.size) * 100 / cues.size.coerceAtLeast(1)).coerceAtMost(99))
                    }
                }
            }.awaitAll()
        }
        // Nothing got through (a quota, a model the key may not use…): say why instead of "done".
        if (translatedLines.get() == 0) lastError.get()?.let { throw it }
        onProgress(100)
        cues.mapIndexed { i, c -> c.copy(translated = out[i]) }
    }

    /** One batch [from, to); on a count mismatch or error it is retried once as two halves. */
    private suspend fun translateRange(
        cues: List<AiCue>, from: Int, to: Int,
        target: String, title: String?, sourceLang: String?, depth: Int = 0,
    ): List<String?> {
        coroutineContext.ensureActive()
        val lines = (from until to).map { cues[it].text }
        val context = (maxOf(0, from - CONTEXT_LINES) until from).map { cues[it].text }
        val got = try {
            request(lines, context, target, title, sourceLang)
        } catch (e: NotConfigured) { throw e } catch (e: InvalidKey) { throw e } catch (e: Refused) {
            if (depth > 0 || to - from < 2) return List(to - from) { null }
            null
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (e !is ProHttp.RateLimited) {
                lastError.set(e)
                null
            } else {
                delay(8_000)
                if (depth > 1) {
                    lastError.set(e)
                    return List(to - from) { null }
                }
                return translateRange(cues, from, to, target, title, sourceLang, depth + 1)
            }
        }
        if (got != null && got.size == lines.size) return got
        if (depth > 0 || to - from < 2) return got?.takeIf { it.size == lines.size } ?: List(to - from) { null }
        val mid = (from + to) / 2
        return translateRange(cues, from, mid, target, title, sourceLang, depth + 1) +
            translateRange(cues, mid, to, target, title, sourceLang, depth + 1)
    }

    private fun request(
        lines: List<String>, context: List<String>,
        target: String, title: String?, sourceLang: String?,
    ): List<String> {
        val from = sourceLang?.let { languageName(it) } ?: "the original language"
        val prompt = buildString {
            append("Translate the subtitle cues below from ").append(from).append(" into ")
                .append(languageName(target)).append(" (").append(target).append(").\n")
            if (!title.isNullOrBlank()) append("Title of the video: ").append(title).append('\n')
            if (context.isNotEmpty()) {
                append("\nPreceding cues, for context only (do not translate them):\n")
                append(JSONArray(context).toString()).append('\n')
            }
            append("\nCues to translate (").append(lines.size).append(" items, JSON array):\n")
            append(JSONArray(lines).toString())
            append("\n\nReply with {\"lines\": [...]} holding exactly ").append(lines.size)
                .append(" translated strings, in the same order.")
        }
        val text = complete(SYSTEM, prompt)
        val arr = JSONObject(text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
            .getJSONArray("lines")
        return (0 until arr.length()).map { arr.optString(it) }
    }

    /**
     * The reply of the chosen service to [prompt]. A model the service no longer knows is replaced
     * once by the closest one it lists, which is kept for next time.
     */
    private fun complete(system: String, prompt: String): String {
        val provider = AiSettings.proProvider.value
        val key = AiSettings.proKey(provider).takeIf { it.isNotBlank() } ?: throw NotConfigured()
        val model = AiSettings.proModel(provider)
        return try {
            completeWith(provider, key, model, system, prompt)
        } catch (e: ProHttp.ModelUnavailable) {
            val replacement = ProHttp.replacementModel(provider, key, model) ?: throw e
            AiSettings.setProModel(provider, replacement)
            completeWith(provider, key, replacement, system, prompt)
        }
    }

    private fun completeWith(provider: ProProvider, key: String, model: String, system: String, prompt: String): String =
        if (provider == ProProvider.CLAUDE) claude(model, system, prompt) else ProHttp.complete(provider, key, model, system, prompt)

    /** Claude over the Anthropic Messages API (iOS has no Anthropic SDK): structured JSON output. */
    private fun claude(model: String, system: String, prompt: String): String {
        val key = AiSettings.proKey(ProProvider.CLAUDE).takeIf { it.isNotBlank() } ?: throw NotConfigured()
        val headers = mapOf(
            "x-api-key" to key,
            "anthropic-version" to "2023-06-01",
            "Content-Type" to "application/json",
            "Accept" to "application/json",
        )
        fun body(structured: Boolean): String {
            val b = JSONObject()
                .put("model", model)
                .put("max_tokens", 16_000)
                .put("system", system)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            if (structured) {
                b.put("output_config", JSONObject().put("format", JSONObject().put("type", "json_schema").put("schema", schema())))
            }
            return b.toString()
        }
        var reply = blockingHttp("https://api.anthropic.com/v1/messages", "POST", headers, body(true).encodeToByteArray())
        // A model or account without structured output: ask for plain JSON instead.
        if (reply.code == 400 && reply.text.contains("output_config")) {
            reply = blockingHttp("https://api.anthropic.com/v1/messages", "POST", headers, body(false).encodeToByteArray())
        }
        var attempts = 0
        while ((reply.code == 529 || reply.code >= 500) && attempts < 3) {
            attempts++
            platform.posix.sleep((2 * attempts).toUInt())
            reply = blockingHttp("https://api.anthropic.com/v1/messages", "POST", headers, body(false).encodeToByteArray())
        }
        when {
            reply.code == 401 || reply.code == 403 -> throw InvalidKey()
            reply.code == 404 -> throw ProHttp.ModelUnavailable(model)
            reply.code == 429 -> throw ProHttp.RateLimited()
            reply.code !in 200..299 -> {
                val message = runCatching { JSONObject(reply.text).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty()
                if ("model" in message.lowercase() && ("not found" in message.lowercase() || "invalid" in message.lowercase())) {
                    throw ProHttp.ModelUnavailable(model)
                }
                throw IOException("HTTP ${reply.code}${if (message.isNotBlank()) ": $message" else ""}")
            }
        }
        val msg = JSONObject(reply.text)
        if (msg.optString("stop_reason") == "refusal") {
            throw Refused(msg.optJSONObject("stop_details")?.optString("category")?.ifBlank { null })
        }
        val content = msg.optJSONArray("content") ?: JSONArray()
        return (0 until content.length()).mapNotNull { content.optJSONObject(it) }
            .filter { it.optString("type") == "text" }
            .joinToString("") { it.optString("text") }
    }

    /** A cheap call to check the chosen service's key; the model that answered. */
    suspend fun test(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            complete("Reply with JSON only.", "Reply with {\"lines\": [\"OK\"]}")
            AiSettings.proAiModel
        }
    }
}
