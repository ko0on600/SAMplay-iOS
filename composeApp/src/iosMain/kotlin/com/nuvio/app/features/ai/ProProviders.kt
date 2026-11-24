package com.nuvio.app.features.ai

import com.nuvio.app.samplay.Charsets

import com.nuvio.app.samplay.JSONArray
import com.nuvio.app.samplay.JSONObject
import com.nuvio.app.samplay.IOException
import com.nuvio.app.samplay.Locale
import com.nuvio.app.samplay.blockingHttp

/**
 * The services "Pro AI" translation can use, each on the user's own API key (none is built in).
 * [defaultModel] is used until the user types another; when a service no longer knows a model,
 * [ProHttp.replacementModel] picks the closest one it lists and keeps it.
 */
enum class ProProvider(
    val id: String,
    val label: String,
    val defaultModel: String,
    /** How the service's keys start, shown in the key field. */
    val keyHint: String,
    internal val modelPrefix: String,
    /** Model tiers, preferred first, when a replacement is picked. */
    internal val tiers: List<String>,
) {
    CLAUDE("claude", "Claude", "claude-opus-5-5", "sk-ant-…", "claude-", listOf("opus", "sonnet", "haiku")),
    CHATGPT("openai", "ChatGPT", "gpt-5", "sk-…", "gpt-", listOf("", "mini", "pro")),
    GEMINI("gemini", "Gemini", "gemini-2.5-flash", "AIza…", "gemini-", listOf("flash", "pro")),
    GROK("grok", "Grok", "grok-4", "xai-…", "grok-", listOf("", "fast", "mini"));

    companion object {
        fun byId(id: String?): ProProvider = entries.firstOrNull { it.id == id } ?: CLAUDE
    }
}

/** ChatGPT, Gemini and Grok over their HTTP APIs (Claude goes through the Anthropic SDK). */
internal object ProHttp {
    class RateLimited : IOException("rate limited")
    class ModelUnavailable(model: String) : IOException("model not available: $model")

    private const val OPENAI = "https://api.openai.com/v1"
    private const val XAI = "https://api.x.ai/v1"
    private const val GEMINI = "https://generativelanguage.googleapis.com/v1beta"
    private const val ANTHROPIC = "https://api.anthropic.com/v1"

    /** The schema of every reply: {"lines": ["…", …]}. */
    private fun linesSchema(): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject().put("lines", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))))
        .put("required", JSONArray().put("lines"))
        .put("additionalProperties", false)

    /** The reply's text (a JSON object) from [provider]; Claude is not handled here. */
    fun complete(provider: ProProvider, key: String, model: String, system: String, prompt: String): String =
        when (provider) {
            ProProvider.CHATGPT -> openAiCompatible(OPENAI, key, model, system, prompt)
            ProProvider.GROK -> openAiCompatible(XAI, key, model, system, prompt)
            ProProvider.GEMINI -> gemini(key, model, system, prompt)
            ProProvider.CLAUDE -> error("Claude goes through the Anthropic SDK")
        }

    /** OpenAI's chat completions, which xAI (Grok) speaks too. */
    private fun openAiCompatible(base: String, key: String, model: String, system: String, prompt: String): String {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", prompt))
        val formats = listOf(
            JSONObject().put("type", "json_schema").put(
                "json_schema",
                JSONObject().put("name", "subtitle_lines").put("strict", true).put("schema", linesSchema()),
            ),
            JSONObject().put("type", "json_object"),
            null,
        )
        var last: Pair<Int, String>? = null
        for (format in formats) {
            val body = JSONObject().put("model", model).put("messages", messages)
            if (format != null) body.put("response_format", format)
            val (code, text) = call("$base/chat/completions", "POST", mapOf("Authorization" to "Bearer $key"), body.toString())
            if (code in 200..299) {
                val choice = JSONObject(text).getJSONArray("choices").getJSONObject(0)
                val message = choice.getJSONObject("message")
                val refusal = message.optString("refusal").takeIf { message.has("refusal") && !message.isNull("refusal") && it.isNotBlank() }
                if (refusal != null || choice.optString("finish_reason") == "content_filter") {
                    throw ProTranslate.Refused(refusal ?: "content_filter")
                }
                return message.optString("content")
            }
            last = code to text
            // An older model or another server may not take this reply format: try a plainer one.
            if (code != 400 || !text.contains("response_format", ignoreCase = true)) break
        }
        val (code, text) = last ?: (0 to "")
        fail(code, text, model)
    }

    private fun gemini(key: String, model: String, system: String, prompt: String): String {
        val name = model.removePrefix("models/")
        val schema = JSONObject()
            .put("type", "OBJECT")
            .put("properties", JSONObject().put("lines", JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "STRING"))))
            .put("required", JSONArray().put("lines"))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("responseSchema", schema))
        val (code, text) = call("$GEMINI/models/$name:generateContent", "POST", mapOf("x-goog-api-key" to key), body.toString())
        if (code !in 200..299) fail(code, text, model)
        val reply = JSONObject(text)
        reply.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotBlank() }?.let {
            throw ProTranslate.Refused(it)
        }
        val candidate = reply.optJSONArray("candidates")?.optJSONObject(0) ?: throw ProTranslate.Refused("no candidate")
        val finish = candidate.optString("finishReason")
        if (finish in setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION")) throw ProTranslate.Refused(finish)
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        return (0 until parts.length())
            .mapNotNull { parts.optJSONObject(it) }
            .filterNot { it.optBoolean("thought") }
            .joinToString("") { it.optString("text") }
    }

    /** The models [provider] offers on [key], by id. */
    fun listModels(provider: ProProvider, key: String): List<String> {
        val (url, headers) = when (provider) {
            ProProvider.CHATGPT -> "$OPENAI/models" to mapOf("Authorization" to "Bearer $key")
            ProProvider.GROK -> "$XAI/models" to mapOf("Authorization" to "Bearer $key")
            ProProvider.CLAUDE -> "$ANTHROPIC/models?limit=1000" to mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01")
            ProProvider.GEMINI -> "$GEMINI/models?pageSize=1000" to mapOf("x-goog-api-key" to key)
        }
        val (code, text) = call(url, "GET", headers, null)
        if (code !in 200..299) fail(code, text, "")
        val json = JSONObject(text)
        return if (provider == ProProvider.GEMINI) {
            val models = json.optJSONArray("models") ?: JSONArray()
            (0 until models.length()).mapNotNull { models.optJSONObject(it) }
                .filter { m ->
                    val methods = m.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                    (0 until methods.length()).any { methods.optString(it) == "generateContent" }
                }
                .map { it.optString("name").removePrefix("models/") }
        } else {
            val data = json.optJSONArray("data") ?: JSONArray()
            (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotBlank() } }
        }
    }

    private val replacements = HashMap<String, String?>()

    /** A model to use instead of [failed], once per service, key and model; null when none fits. */
    fun replacementModel(provider: ProProvider, key: String, failed: String): String? {
        val cacheKey = "${provider.id}|${key.hashCode()}|$failed"
        run {
            if (replacements.containsKey(cacheKey)) return replacements[cacheKey]
            val picked = runCatching { pickModel(provider, listModels(provider, key)) }.getOrNull()
                ?.takeIf { it != failed }
            replacements[cacheKey] = picked
            return picked
        }
    }

    private val notForText = listOf(
        "audio", "realtime", "image", "tts", "transcri", "search", "embed", "vision", "instruct", "codex",
        "live", "computer", "robotics", "research", "code", "nano", "lite", "guard", "moderation",
        "learnlm", "gemma", "aqa", "oss", "chat-latest",
    )
    private val unstable = listOf("preview", "exp", "beta", "alpha", "latest")

    /** The strongest stable text model of [provider] among [ids]: newest version, preferred tier. */
    internal fun pickModel(provider: ProProvider, ids: List<String>): String? {
        val usable = ids.map { it.removePrefix("models/") }.distinct()
            .filter { id -> id.startsWith(provider.modelPrefix) && notForText.none { it in id.lowercase() } }
        return usable.sortedWith(
            compareBy<String>(
                { id -> unstable.count { it in id.lowercase() } },
                { id -> -version(id, provider) },
                { id -> tierRank(id, provider) },
                { id -> id.length },
            ),
        ).firstOrNull()
    }

    /** "gpt-5.1" → 5.1, "claude-opus-4-1-20250805" → 4.1, "gemini-2.5-flash" → 2.5 (dates are skipped). */
    private fun version(id: String, provider: ProProvider): Double {
        val numbers = id.removePrefix(provider.modelPrefix).split('-', '.', '_')
            .filter { it.isNotEmpty() && it.length <= 2 && it.all(Char::isDigit) }
        val major = numbers.getOrNull(0)?.toDoubleOrNull() ?: return 0.0
        val minor = numbers.getOrNull(1)?.toDoubleOrNull() ?: 0.0
        return major + minor / 10.0
    }

    private fun tierRank(id: String, provider: ProProvider): Int {
        val lower = id.lowercase()
        provider.tiers.forEachIndexed { index, tier ->
            if (tier.isNotEmpty() && tier in lower) return index
        }
        // No tier word: the plain model, first where the provider prefers it ("").
        val plain = provider.tiers.indexOf("")
        return if (plain >= 0) plain else provider.tiers.size
    }

    private fun fail(code: Int, body: String, model: String): Nothing {
        val message = errorMessage(body)
        val lower = (message + " " + body.take(400)).lowercase()
        when {
            code == 401 || code == 403 -> throw ProTranslate.InvalidKey()
            code == 400 && ("api key" in lower || "api_key" in lower || "x-api-key" in lower) -> throw ProTranslate.InvalidKey()
            code == 429 && "insufficient_quota" in lower -> throw IOException(message.ifBlank { "insufficient quota" })
            code == 429 -> throw RateLimited()
            code == 404 -> throw ModelUnavailable(model)
            "model" in lower && ("not found" in lower || "does not exist" in lower || "not_found" in lower ||
                "invalid model" in lower || "not supported" in lower) -> throw ModelUnavailable(model)
            else -> throw IOException("HTTP $code${if (message.isNotBlank()) ": $message" else ""}")
        }
    }

    private fun errorMessage(body: String): String = runCatching {
        val json = JSONObject(body)
        val error = json.opt("error")
        when (error) {
            is JSONObject -> error.optString("message")
            is String -> error
            else -> json.optString("message")
        }
    }.getOrDefault("").take(200)

    private fun call(url: String, method: String, headers: Map<String, String>, body: String?): Pair<Int, String> {
        val all = LinkedHashMap<String, String>()
        all["Accept"] = "application/json"
        all.putAll(headers)
        if (body != null) all["Content-Type"] = "application/json; charset=utf-8"
        val reply = blockingHttp(url, method, all, body?.encodeToByteArray(), timeoutSeconds = 180.0)
        return reply.code to reply.text
    }
}
