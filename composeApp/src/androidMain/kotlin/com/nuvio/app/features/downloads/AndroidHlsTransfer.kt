package com.nuvio.app.features.downloads

import co.touchlab.kermit.Logger
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** A stream that can never be saved as one file (live, protected, sound kept apart): no retry helps. */
internal class UnsupportedDownloadException(message: String) : IllegalStateException(message)

private val hlsLog = Logger.withTag("SamplayDownloads")

/** Pieces fetched at once. The FaselHD proxy answers each piece in ~1 s, so one at a time crawled. */
internal const val HLS_PARALLEL_PIECES = 6

/**
 * SAMplay 2.0.4: saves an HLS stream (a playlist of many small pieces) as one file: the pieces are
 * fetched [parallel] at a time, written in order into [partial], decrypted when AES-128.
 *
 * A checkpoint next to [partial] (`.hls`: pieces written and their bytes, after an fsync) lets a stop
 * or a network change continue where it was instead of starting over. [firstPlaylist] is the text
 * the caller already read from [playlistUrl] (a proxy link only says it is a playlist when asked).
 */
internal suspend fun transferHlsDownload(
    item: DownloadItem,
    playlistUrl: String,
    firstPlaylist: String?,
    partial: File,
    client: OkHttpClient,
    onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    parallel: Int = HLS_PARALLEL_PIECES,
): File = withContext(Dispatchers.IO) {
    // Enqueued calls obey the dispatcher's per-host cap (5 by default); give the pieces their own.
    val http = client.newBuilder()
        .dispatcher(Dispatcher().apply { maxRequests = parallel * 2 + 4; maxRequestsPerHost = parallel + 2 })
        .build()
    val headers = item.sourceHeaders.filterKeys { name ->
        !name.equals("Range", true) && !name.equals("If-Range", true) && !name.equals("Accept-Encoding", true)
    }

    suspend fun text(url: String): String = String(http.fetch(url, headers, null), Charsets.UTF_8)

    val top = firstPlaylist ?: text(playlistUrl)
    if (!HlsPlaylist.looksLikePlaylist(top)) throw UnsupportedDownloadException("The link did not give a playlist")
    var mediaUrl = playlistUrl
    var mediaText = top
    if (HlsPlaylist.isMaster(top)) {
        val master = HlsPlaylist.parseMaster(top, playlistUrl)
        val variant = HlsPlaylist.pickVariant(master) ?: throw UnsupportedDownloadException("The playlist has no video")
        if (HlsPlaylist.hasSeparateAudio(master, variant)) {
            throw UnsupportedDownloadException("This stream keeps its sound apart from the picture; it cannot be saved as one file")
        }
        mediaUrl = variant.uri
        mediaText = text(mediaUrl)
    }
    val media = HlsPlaylist.parseMedia(mediaText, mediaUrl)
    HlsPlaylist.unsupportedReason(media)?.let { throw UnsupportedDownloadException(it) }
    val total = media.segments.size

    val checkpoint = File(partial.path + ".hls")
    var done = 0
    var bytes = 0L
    readHlsCheckpoint(checkpoint)?.takeIf { it.pieces == total && it.done in 1..total && partial.length() >= it.bytes }
        ?.let { saved ->
            RandomAccessFile(partial, "rw").use { it.setLength(saved.bytes) }
            done = saved.done
            bytes = saved.bytes
        }
    if (done == 0) {
        partial.delete()
        checkpoint.delete()
    }
    hlsLog.i { "pieces: $total (${media.durationSec.toInt()} s), continuing from ${done}, $parallel at once" }

    val keys = mutableMapOf<String, ByteArray>()
    val keyLock = Mutex()
    suspend fun piece(index: Int): ByteArray {
        val segment = media.segments[index]
        val data = http.fetchWithRetry(segment.uri, headers, segment.byteRange)
        val key = segment.key ?: return data
        val keyUri = key.uri ?: throw UnsupportedDownloadException("This stream is protected and cannot be downloaded")
        val keyBytes = keyLock.withLock { keys.getOrPut(keyUri) { http.fetchWithRetry(keyUri, headers, null) } }
        return decryptAes128(data, keyBytes, hlsIv(key.ivHex, segment.sequence))
    }

    fun estimate(): Long? = if (done >= 3) bytes / done * total else null

    FileOutputStream(partial, true).use { output ->
        if (done == 0 && media.mapUri != null) {
            val init = http.fetchWithRetry(media.mapUri, headers, media.mapByteRange)
            output.write(init)
            bytes += init.size
        }
        onProgress(bytes, estimate())
        coroutineScope {
            var next = done
            val window = ArrayDeque<Deferred<ByteArray>>()
            fun launchNext() {
                val index = next++
                window.addLast(async { piece(index) })
            }
            repeat(minOf(parallel, total - done)) { launchNext() }
            while (window.isNotEmpty()) {
                val data = window.removeFirst().await()
                if (next < total) launchNext()
                ensureActive()
                output.write(data)
                bytes += data.size
                done++
                if (done % 10 == 0 || done == total) {
                    output.flush()
                    output.fd.sync()
                    writeHlsCheckpoint(checkpoint, HlsCheckpoint(total, done, bytes))
                }
                onProgress(bytes, if (done == total) bytes else estimate())
            }
        }
        output.flush()
        output.fd.sync()
    }
    checkpoint.delete()
    hlsLog.i { "saved $total pieces, $bytes bytes" }
    partial
}

internal data class HlsCheckpoint(val pieces: Int, val done: Int, val bytes: Long)

internal fun readHlsCheckpoint(file: File): HlsCheckpoint? = runCatching {
    val parts = file.readText().trim().split(' ')
    if (parts.size != 4 || parts[0] != "v1") return null
    HlsCheckpoint(parts[1].toInt(), parts[2].toInt(), parts[3].toLong())
}.getOrNull()

private fun writeHlsCheckpoint(file: File, checkpoint: HlsCheckpoint) {
    val temp = File(file.path + ".tmp")
    temp.writeText("v1 ${checkpoint.pieces} ${checkpoint.done} ${checkpoint.bytes}")
    if (!temp.renameTo(file)) {
        file.delete()
        temp.renameTo(file)
    }
}

/** One piece, with up to 4 more tries for a dropped connection or a busy server (408/429/5xx). */
private suspend fun OkHttpClient.fetchWithRetry(url: String, headers: Map<String, String>, range: LongRange?): ByteArray {
    var attempt = 0
    while (true) {
        try {
            return fetch(url, headers, range)
        } catch (error: IOException) {
            val retriable = error !is DownloadHttpException ||
                error.statusCode == 408 || error.statusCode == 429 || error.statusCode in 500..599
            if (!retriable || ++attempt > 4) throw error
            delay(1_000L shl (attempt - 1))
        }
    }
}

private suspend fun OkHttpClient.fetch(url: String, headers: Map<String, String>, range: LongRange?): ByteArray {
    val request = Request.Builder().url(url).apply {
        headers.forEach { (name, value) -> header(name, value) }
        header("Accept-Encoding", "identity")
        range?.let { header("Range", "bytes=${it.first}-${it.last}") }
    }.build()
    return suspendCancellableCoroutine { continuation ->
        val call = newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!it.isSuccessful) throw DownloadHttpException(it.code)
                        val body = it.body ?: throw IOException("Empty answer")
                        continuation.resume(body.bytes())
                    } catch (error: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            }
        })
    }
}

/** IV from the key line, else the piece's media sequence number (HLS spec, section 5.2). */
internal fun hlsIv(ivHex: String?, sequence: Long): ByteArray {
    val hex = ivHex?.trim()?.removePrefix("0x")?.removePrefix("0X")
    if (hex != null && hex.length == 32 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
        return ByteArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
    return ByteBuffer.allocate(16).putLong(0L).putLong(sequence).array()
}

internal fun decryptAes128(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
    if (key.size != 16) throw UnsupportedDownloadException("The stream's key is not a 16-byte AES key")
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    return cipher.doFinal(data)
}
