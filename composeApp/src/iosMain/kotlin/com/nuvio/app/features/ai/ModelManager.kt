package com.nuvio.app.features.ai

import com.nuvio.app.samplay.Context
import com.nuvio.app.samplay.File
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLSession
import platform.Foundation.downloadTaskWithRequest
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait

/** Downloads the multilingual whisper.cpp ggml model on first use (never bundled in the app). */
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

    @OptIn(ExperimentalForeignApi::class)
    suspend fun ensure(c: Context, m: Model, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val target = file(c, m)
        if (isReady(c, m)) return@withContext target
        onProgress(0)
        var attempt = 0
        while (true) {
            val error = download(BASE_URL + m.file, target)
            if (error == null && isReady(c, m)) break
            target.delete()
            if (++attempt >= 3) throw IllegalStateException(error ?: "incomplete download")
        }
        onProgress(100)
        target
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun download(url: String, target: File): String? {
        val semaphore = dispatch_semaphore_create(0)
        var failure: String? = null
        val request = NSURLRequest.requestWithURL(NSURL.URLWithString(url)!!)
        val task = NSURLSession.sharedSession.downloadTaskWithRequest(request) { location, response, error ->
            val code = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
            when {
                error != null -> failure = error.localizedDescription
                code != 200 || location == null -> failure = "HTTP $code"
                else -> {
                    target.parentFile?.mkdirs()
                    if (target.exists()) target.delete()
                    if (!NSFileManager.defaultManager.moveItemAtURL(location, NSURL.fileURLWithPath(target.path), null)) {
                        failure = "could not save model"
                    }
                }
            }
            dispatch_semaphore_signal(semaphore)
        }
        task.resume()
        dispatch_semaphore_wait(semaphore, DISPATCH_TIME_FOREVER)
        return failure
    }
}
