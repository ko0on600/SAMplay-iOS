package com.nuvio.app.features.autosync

import com.nuvio.app.features.addons.AddonHttpClientProvider
import com.nuvio.app.features.player.LocalSubtitleFiles
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Dedicated client for container index range requests, kept apart from Nuvio's shared pool. */
private val containerIndexHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
}

internal actual suspend fun <T> autoSyncHttpGet(
    url: String,
    headers: Map<String, String>,
    kind: AutoSyncHttpClientKind,
    timeoutMs: Long?,
    block: suspend (AutoSyncHttpResponse) -> T,
): T {
    // SAMplay: AI subtitles and those loaded from the device are local files, not HTTP links.
    if (kind == AutoSyncHttpClientKind.SUBTITLE) {
        LocalSubtitleFiles.fileFor(url)?.let { file -> return readLocalSubtitle(file, url, block) }
    }
    // SAMplay: a video on this device (content:// or file://). Its subtitle index is read from
    // the file itself, answered like an HTTP range request so the container parsers stay the same.
    if (kind == AutoSyncHttpClientKind.CONTAINER_INDEX && isLocalMediaUrl(url)) {
        return readLocalRange(url, headers, block)
    }
    val requestBuilder = Request.Builder().url(url).get()
    headers.forEach { (name, value) -> requestBuilder.header(name, value) }

    val client = when (kind) {
        AutoSyncHttpClientKind.CONTAINER_INDEX -> containerIndexHttpClient
        AutoSyncHttpClientKind.SUBTITLE -> AddonHttpClientProvider.get()
    }
    val call = client.newCall(requestBuilder.build())
    if (timeoutMs != null) call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)

    // OkHttp's async API lets coroutine cancellation interrupt DNS/connect/headers immediately
    // instead of waiting for a blocking execute() to return.
    val response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.failure(cancellationIfCanceled(call, e)))
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.success(response))
                    } else {
                        response.close()
                    }
                }
            },
        )
    }

    // Cancelling the caller while the body is read cancels the call, which aborts the read.
    val cancelHandle = currentCoroutineContext().job.invokeOnCompletion { cause ->
        if (cause != null) call.cancel()
    }
    return try {
        response.use { current ->
            val stream: InputStream? = current.body?.byteStream()
            val wrapped = AutoSyncHttpResponse(
                status = current.code,
                statusText = current.message,
                finalUrl = current.request.url.toString(),
                headers = current.headers
                    .toMultimap()
                    .mapValues { (_, values) -> values.joinToString(",") }
                    .mapKeys { (name, _) -> name.lowercase() },
                body = object : AutoSyncHttpBody {
                    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                        stream?.read(buffer, offset, length) ?: -1
                },
            )
            withContext(Dispatchers.IO) { block(wrapped) }
        }
    } catch (error: IOException) {
        throw cancellationIfCanceled(call, error)
    } finally {
        cancelHandle.dispose()
    }
}

/** A local subtitle file answered like a download: 200 with its bytes, or 404 when it is gone. */
private suspend fun <T> readLocalSubtitle(
    file: File,
    url: String,
    block: suspend (AutoSyncHttpResponse) -> T,
): T = withContext(Dispatchers.IO) {
    val bytes = if (file.isFile) LocalSubtitleFiles.readBytes(file) else null
    val stream = (bytes ?: ByteArray(0)).inputStream()
    block(
        AutoSyncHttpResponse(
            status = if (bytes != null) 200 else 404,
            statusText = if (bytes != null) "OK" else "Not Found",
            finalUrl = url,
            headers = emptyMap(),
            body = object : AutoSyncHttpBody {
                override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    stream.read(buffer, offset, length)
            },
        ),
    )
}

private fun isLocalMediaUrl(url: String): Boolean =
    url.startsWith("content://", ignoreCase = true) || url.startsWith("file://", ignoreCase = true)

/**
 * One byte range of a local video: 206 with Content-Range when it can be read, 416 past its end,
 * 404 when it cannot be opened.
 */
private suspend fun <T> readLocalRange(
    url: String,
    headers: Map<String, String>,
    block: suspend (AutoSyncHttpResponse) -> T,
): T = withContext(Dispatchers.IO) {
    val range = headers.entries.firstOrNull { it.key.equals("Range", ignoreCase = true) }?.value
    val match = range?.let { Regex("""bytes=(\d+)-(\d*)""").find(it.trim()) }
    val requestedStart = match?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    val requestedEnd = match?.groupValues?.get(2)?.toLongOrNull()
    val uri = android.net.Uri.parse(url)
    val descriptor = runCatching {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            val file = runCatching { File(java.net.URI(url)) }.getOrNull() ?: uri.path?.let(::File)
            file?.let { android.os.ParcelFileDescriptor.open(it, android.os.ParcelFileDescriptor.MODE_READ_ONLY) }
        } else {
            com.nuvio.app.features.player.PlayerSettingsStorage.applicationContext()
                ?.contentResolver
                ?.openFileDescriptor(uri, "r")
        }
    }.getOrNull()
    if (descriptor == null) {
        return@withContext block(localResponse(url, 404, "Not Found", emptyMap(), ByteArray(0).inputStream()))
    }
    android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
        val channel = input.channel
        val total = runCatching { channel.size() }.getOrDefault(descriptor.statSize)
        if (total <= 0L || requestedStart >= total) {
            return@withContext block(
                localResponse(url, 416, "Range Not Satisfiable", mapOf("content-range" to "bytes */$total"), ByteArray(0).inputStream()),
            )
        }
        val end = (requestedEnd ?: (total - 1L)).coerceAtMost(total - 1L)
        channel.position(requestedStart)
        val length = end - requestedStart + 1L
        val body = java.nio.channels.Channels.newInputStream(channel)
        val bounded = object : InputStream() {
            private var remaining = length
            override fun read(): Int {
                if (remaining <= 0L) return -1
                return body.read().also { if (it >= 0) remaining-- }
            }
            override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
                if (remaining <= 0L) return -1
                val read = body.read(buffer, offset, minOf(count.toLong(), remaining).toInt())
                if (read > 0) remaining -= read
                return read
            }
        }
        block(
            localResponse(
                url,
                206,
                "Partial Content",
                mapOf(
                    "content-range" to "bytes $requestedStart-$end/$total",
                    "content-length" to length.toString(),
                ),
                bounded,
            ),
        )
    }
}

private fun localResponse(
    url: String,
    status: Int,
    statusText: String,
    headers: Map<String, String>,
    stream: InputStream,
): AutoSyncHttpResponse = AutoSyncHttpResponse(
    status = status,
    statusText = statusText,
    finalUrl = url,
    headers = headers,
    body = object : AutoSyncHttpBody {
        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            stream.read(buffer, offset, length)
    },
)

private fun cancellationIfCanceled(call: Call, error: Throwable): Throwable =
    if (call.isCanceled() && error !is CancellationException) {
        CancellationException("Cancelled AutoSync HTTP request").also { it.initCause(error) }
    } else {
        error
    }

internal actual fun autoSyncIsCharsetSupported(charsetName: String): Boolean =
    try {
        Charset.isSupported(charsetName)
    } catch (_: Exception) {
        false
    }

internal actual fun autoSyncDecodeBytes(
    bytes: ByteArray,
    offset: Int,
    length: Int,
    charsetName: String,
    strict: Boolean,
): String? {
    val charset = try {
        Charset.forName(charsetName)
    } catch (_: Exception) {
        return null
    }
    if (!strict) return String(bytes, offset, length, charset)
    return try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, length))
            .toString()
    } catch (_: Exception) {
        null
    }
}
