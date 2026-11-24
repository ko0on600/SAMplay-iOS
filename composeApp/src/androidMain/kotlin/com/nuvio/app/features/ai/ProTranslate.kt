package com.nuvio.app.features.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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

    @Volatile private var client: AnthropicClient? = null
    @Volatile private var clientKey: String? = null

    private fun client(): AnthropicClient {
        val key = AiSettings.proKey(ProProvider.CLAUDE).takeIf { it.isNotBlank() } ?: throw NotConfigured()
        client?.takeIf { clientKey == key }?.let { return it }
        return AnthropicOkHttpClient.builder()
            .apiKey(key)
            .timeout(Duration.ofSeconds(180))
            .maxRetries(3)
            .build()
            .also { client = it; clientKey = key }
    }

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

    private val schema: BetaJsonOutputFormat.Schema = BetaJsonOutputFormat.Schema.builder()
        .putAdditionalProperty("type", JsonValue.from("object"))
        .putAdditionalProperty(
            "properties",
            JsonValue.from(mapOf("lines" to mapOf("type" to "array", "items" to mapOf("type" to "string")))),
        )
        .putAdditionalProperty("required", JsonValue.from(listOf("lines")))
        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
        .build()

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
            if (e !is RateLimitException && e !is ProHttp.RateLimited) {
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

    private fun claude(model: String, system: String, prompt: String): String {
        val api = client()
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16_000L)
            .system(system)
            .outputConfig(
                BetaOutputConfig.builder()
                    .effort(BetaOutputConfig.Effort.MEDIUM)
                    .format(BetaJsonOutputFormat.builder().schema(schema).build())
                    .build(),
            )
            // Server-side refusal fallback ("default" = routed by refusal category).
            .addBeta("server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .addUserMessage(prompt)
            .build()
        val msg = try {
            api.beta().messages().create(params)
        } catch (e: UnauthorizedException) {
            throw InvalidKey()
        } catch (e: AnthropicServiceException) {
            if (e.statusCode() == 401 || e.statusCode() == 403) throw InvalidKey()
            if (e.statusCode() == 404) throw ProHttp.ModelUnavailable(model)
            throw e
        }
        if (msg.stopReason().orElse(null) == BetaStopReason.REFUSAL) {
            throw Refused(msg.stopDetails().map { it.category().toString() }.orElse(null))
        }
        return msg.content().mapNotNull { b -> b.text().map { it.text() }.orElse(null) }.joinToString("")
    }

    /** A cheap call to check the chosen service's key; the model that answered. */
    suspend fun test(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            complete("Reply with JSON only.", "Reply with {\"lines\": [\"OK\"]}")
            AiSettings.proAiModel
        }
    }
}
