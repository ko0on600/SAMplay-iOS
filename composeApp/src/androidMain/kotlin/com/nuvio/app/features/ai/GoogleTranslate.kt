package com.nuvio.app.features.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/**
 * SAMplay: free online subtitle translation (Google Translate's web endpoint, `client=gtx`), many
 * cues per request joined by a blank line and split back on the blank lines of the reply. No key
 * and no model download; ML Kit on the device fills in what this can't reach.
 *
 * A batch whose reply does not split back into exactly its cues is retried as two halves, down to
 * single cues, so one odd line never shifts the others.
 */
object GoogleTranslate {
    private const val ENDPOINT = "https://translate.googleapis.com/translate_a/single"
    private const val MAX_CHARS = 4000
    private const val MAX_CUES = 80
    private const val PARALLEL = 3
    private const val SEPARATOR = "\n\n"
    private val SPLIT = Regex("""\n\s*\n""")

    /** [lines]: one translation per input line (null where it failed); [detected]: source language. */
    class Result(val lines: List<String?>, val detected: String?)

    class Blocked(code: Int) : IOException("HTTP $code")

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    /** Google's code for an ML Kit / BCP-47 language code. */
    fun googleCode(code: String): String = when (code.lowercase()) {
        "zh", "zh-hans", "zh-cn" -> "zh-CN"
        "zh-tw", "zh-hant" -> "zh-TW"
        "he" -> "iw"
        "jv" -> "jw"
        else -> code
    }

    /** The ML Kit / BCP-47 code for Google's detected language ("iw" → "he", "zh-CN" → "zh"). */
    fun fromGoogle(code: String?): String? = when (code?.lowercase()) {
        null, "", "auto", "und" -> null
        "iw" -> "he"
        "jw" -> "jv"
        "zh-cn", "zh-tw" -> "zh"
        else -> code.lowercase().substringBefore('-')
    }

    private val assTag = Regex("""\{\\[^}]*\}""")
    private val htmlTag = Regex("""</?[a-zA-Z][^>]*>""")
    private val lineBreaks = Regex("""\s*(\\N|\\n|\n)\s*""")

    /** A cue as one plain line for the request: no markup, no line breaks. */
    internal fun clean(text: String): String =
        text.replace(assTag, "").replace(htmlTag, "").replace(lineBreaks, " ").trim()

    private val dialogueDash = Regex("""\s+(?=[-–]\s*\S)""")

    /**
     * A cue of two speakers ("- Yeah.\n- She knows.") goes out as one line; each speaker gets a line
     * of its own again when the reply keeps the dashes ("- نعم. - إنها تعرف.").
     */
    internal fun restoreDialogue(original: String, translated: String): String {
        val lines = original.replace(assTag, "").replace(htmlTag, "").split(lineBreaks)
            .map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2 || lines.any { !it.startsWith("-") && !it.startsWith("–") }) return translated
        val parts = translated.trim().split(dialogueDash).map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.size == lines.size) parts.joinToString("\n") else translated
    }

    /** Request batches over [lines]: at most [maxChars] characters and [maxCues] lines each. */
    internal fun batches(lines: List<String>, maxChars: Int = MAX_CHARS, maxCues: Int = MAX_CUES): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = 0
        var chars = 0
        for (i in lines.indices) {
            val len = lines[i].length + SEPARATOR.length
            if (i > start && (chars + len > maxChars || i - start >= maxCues)) {
                out += start until i
                start = i
                chars = 0
            }
            chars += len
        }
        if (start < lines.size) out += start until lines.size
        return out
    }

    /** The translated text and detected source of a reply: `[[["translated","original",…],…],null,"en",…]`. */
    internal fun parse(body: String): Pair<String, String?> {
        val root = JSONArray(body)
        val segments = root.optJSONArray(0) ?: throw IOException("no translation in the reply")
        val text = StringBuilder()
        for (i in 0 until segments.length()) {
            val seg = segments.optJSONArray(i) ?: continue
            if (!seg.isNull(0)) text.append(seg.optString(0))
        }
        val detected = root.opt(2) as? String
        return text.toString() to detected
    }

    private fun request(text: String, source: String?, target: String): Pair<String, String?> {
        val form = FormBody.Builder()
            .add("client", "gtx")
            .add("dt", "t")
            .add("ie", "UTF-8")
            .add("oe", "UTF-8")
            .add("sl", source?.let { googleCode(it) } ?: "auto")
            .add("tl", googleCode(target))
            .add("q", text)
            .build()
        val req = Request.Builder().url(ENDPOINT).post(form)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw Blocked(resp.code)
            return runCatching { parse(body) }.getOrElse { throw IOException("unexpected reply", it) }
        }
    }

    private suspend fun requestRetrying(text: String, source: String?, target: String): Pair<String, String?> {
        var last: Exception? = null
        repeat(3) { attempt ->
            coroutineContext.ensureActive()
            try {
                return request(text, source, target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                val wait = if (e is Blocked && e.message == "HTTP 429") 4_000L * (attempt + 1) else 1_200L * (attempt + 1)
                if (attempt < 2) delay(wait)
            }
        }
        throw last ?: IOException("translation failed")
    }

    /**
     * Translates [lines] into [target]. [source] null = detect. Lines blank after [clean] are not
     * sent (their result is null). Throws when no request at all got through.
     */
    suspend fun translate(
        lines: List<String>, source: String?, target: String, onProgress: (Int) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        val cleaned = lines.map(::clean)
        val todo = cleaned.indices.filter { cleaned[it].isNotEmpty() }
        val out = arrayOfNulls<String>(lines.size)
        if (todo.isEmpty()) return@withContext Result(out.toList(), null)
        val detected = AtomicReference<String?>(null)
        val lastError = AtomicReference<Exception?>(null)
        val ok = AtomicInteger(0)
        val done = AtomicInteger(0)
        val gate = Semaphore(PARALLEL)
        val parts = batches(todo.map { cleaned[it] })
        onProgress(0)
        coroutineScope {
            parts.mapIndexed { n, range ->
                async {
                    if (n in 1 until PARALLEL) delay(n * 400L)
                    gate.withPermit {
                        val idx = range.map { todo[it] }
                        val got = translateRange(idx.map { cleaned[it] }, source, target, detected, lastError)
                        got.forEachIndexed { k, s ->
                            if (!s.isNullOrBlank()) {
                                out[idx[k]] = restoreDialogue(lines[idx[k]], s)
                                ok.incrementAndGet()
                            }
                        }
                        onProgress((done.addAndGet(idx.size) * 100 / todo.size).coerceAtMost(99))
                    }
                }
            }.awaitAll()
        }
        if (ok.get() == 0) throw lastError.get() ?: IOException("nothing was translated")
        onProgress(100)
        Result(out.toList(), detected.get())
    }

    private suspend fun translateRange(
        lines: List<String>, source: String?, target: String,
        detected: AtomicReference<String?>, lastError: AtomicReference<Exception?>,
    ): List<String?> {
        coroutineContext.ensureActive()
        val reply = try {
            requestRetrying(lines.joinToString(SEPARATOR), source, target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError.set(e)
            return List(lines.size) { null }
        }
        reply.second?.let { detected.compareAndSet(null, it) }
        if (lines.size == 1) return listOf(reply.first.replace(SPLIT, " ").trim())
        val split = reply.first.split(SPLIT).map { it.trim() }.let { s ->
            if (s.size == lines.size + 1 && s.last().isEmpty()) s.dropLast(1) else s
        }
        if (split.size == lines.size) return split
        val mid = lines.size / 2
        return translateRange(lines.subList(0, mid), source, target, detected, lastError) +
            translateRange(lines.subList(mid, lines.size), source, target, detected, lastError)
    }
}
