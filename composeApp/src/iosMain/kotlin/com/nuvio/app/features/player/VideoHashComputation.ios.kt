package com.nuvio.app.features.player

import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.samplay.toByteArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileHandle
import platform.Foundation.closeFile
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.readDataOfLength
import platform.Foundation.seekToFileOffset

/**
 * SAMplay iOS: the OpenSubtitles hash (`size + sum(first 64 KB LE longs) + sum(last 64 KB)`),
 * from range requests for streams and read in place for a file on this device.
 */
internal actual suspend fun computeVideoHash(
    url: String,
    headers: Map<String, String>,
): Pair<String, Long>? = withContext(Dispatchers.IO) {
    runCatching {
        localPathOf(url)?.let { return@runCatching OpenSubtitlesHash.ofFile(it) }
        OpenSubtitlesHash.ofStream(url, headers)
    }.getOrNull()
}

internal object OpenSubtitlesHash {
    const val CHUNK = 65536

    fun sum(bytes: ByteArray): Long {
        var sum = 0L
        var i = 0
        while (i + 8 <= bytes.size) {
            var v = 0L
            for (b in 0 until 8) v = v or ((bytes[i + b].toLong() and 0xFF) shl (b * 8))
            sum += v
            i += 8
        }
        return sum
    }

    fun hex(size: Long, head: ByteArray, tail: ByteArray): String =
        (size + sum(head) + sum(tail)).toULong().toString(16).padStart(16, '0')

    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    fun ofFile(path: String): Pair<String, Long>? {
        val size = com.nuvio.app.samplay.File(path).length()
        if (size < CHUNK * 2L) return null
        val handle = NSFileHandle.fileHandleForReadingAtPath(path) ?: return null
        try {
            val head = handle.readDataOfLength(CHUNK.toULong()).toByteArray()
            handle.seekToFileOffset((size - CHUNK).toULong())
            val tail = handle.readDataOfLength(CHUNK.toULong()).toByteArray()
            if (head.size != CHUNK || tail.size != CHUNK) return null
            return hex(size, head, tail) to size
        } finally {
            handle.closeFile()
        }
    }

    suspend fun ofStream(url: String, headers: Map<String, String>): Pair<String, Long>? {
        val head = range(url, headers, "bytes=0-${CHUNK - 1}") ?: return null
        val size = head.second ?: return null
        if (size < CHUNK * 2L) return null
        val tail = range(url, headers, "bytes=${size - CHUNK}-${size - 1}") ?: return null
        if (head.first.size != CHUNK || tail.first.size != CHUNK) return null
        return hex(size, head.first, tail.first) to size
    }

    /** The bytes of a 206 reply and the total size from its Content-Range. */
    private suspend fun range(url: String, headers: Map<String, String>, range: String): Pair<ByteArray, Long?>? {
        val reply = httpRequestRaw(
            method = "GET",
            url = url,
            headers = headers + ("Range" to range),
            body = "",
            maxResponseBodyBytes = CHUNK + 1024,
        )
        if (reply.status != 206) return null
        val total = reply.headers.entries.firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
            ?.value?.substringAfterLast('/')?.trim()?.toLongOrNull()
        return reply.bodyBytes to total
    }
}
