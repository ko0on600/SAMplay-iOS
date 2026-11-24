package com.nuvio.app.features.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** Downloads the multilingual whisper.cpp ggml model on first use (never bundled in the APK). */
object ModelManager {
    data class Model(val id: String, val file: String, val bytes: Long)

    val TINY = Model("tiny", "ggml-tiny-q5_1.bin", 32_152_673)
    val BASE = Model("base", "ggml-base-q5_1.bin", 59_707_625)

    fun byId(id: String) = if (id == "tiny") TINY else BASE

    private const val BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

    fun dir(c: Context) = File(c.filesDir, "models").apply { mkdirs() }
    fun file(c: Context, m: Model) = File(dir(c), m.file)
    fun isReady(c: Context, m: Model) = file(c, m).let { it.exists() && it.length() > m.bytes * 0.95 }

    fun deleteAll(c: Context) { dir(c).listFiles()?.forEach { it.delete() } }

    suspend fun ensure(c: Context, m: Model, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val target = file(c, m)
        if (isReady(c, m)) return@withContext target
        val part = File(target.path + ".part")
        // A .part that is already complete (killed between the last byte and the rename) would get
        // "HTTP 416" for its "Range: bytes=<size>-" request on every attempt: just finish it.
        if (part.length() > m.bytes) part.delete()
        var attempt = 0
        while (part.length() != m.bytes) {
            try {
                download(BASE_URL + m.file, part, m.bytes, onProgress)
                break
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                // A broken partial file (wrong length / server ignored the range) starts over.
                if (e is RangeNotSatisfiable) part.delete()
                if (++attempt >= 3) throw e
            }
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) throw IllegalStateException("could not save model")
        target
    }

    private class RangeNotSatisfiable : IllegalStateException("HTTP 416")

    private suspend fun download(url: String, part: File, expected: Long, onProgress: (Int) -> Unit) {
        var u = URL(url)
        var conn: HttpURLConnection
        var redirects = 0
        val have = if (part.exists()) part.length() else 0L
        while (true) {
            conn = u.openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "Nuvio/1.0")
            if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
            val code = conn.responseCode
            if (code in 300..399 && redirects < 6) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                u = URL(u, loc); redirects++
                continue
            }
            break
        }
        val code = conn.responseCode
        if (code == 416) { conn.disconnect(); throw RangeNotSatisfiable() }
        if (code != 200 && code != 206) throw IllegalStateException("HTTP $code")
        val append = code == 206
        val total = (if (append) have + conn.contentLengthLong else conn.contentLengthLong)
            .takeIf { it > have } ?: expected
        var done = if (append) have else 0L
        conn.inputStream.use { input ->
            java.io.FileOutputStream(part, append).use { out ->
                val b = ByteArray(64 * 1024)
                var lastPct = -1
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(b)
                    if (n < 0) break
                    out.write(b, 0, n)
                    done += n
                    val pct = (done * 100 / total).toInt()
                    if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                }
            }
        }
        conn.disconnect()
        if (part.length() < expected * 0.95) throw IllegalStateException("incomplete download")
    }
}
