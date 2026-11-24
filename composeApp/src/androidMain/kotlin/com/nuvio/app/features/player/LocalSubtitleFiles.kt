package com.nuvio.app.features.player

import com.nuvio.app.features.autosync.SubtitleCharsetDetector
import java.io.File
import java.net.URI
import java.net.URLDecoder

/**
 * SAMplay: subtitles made by the AI tools and those loaded from the device's files are kept in the
 * app's own storage and reach the player as file:// links (or bare paths). The subtitle loaders
 * only spoke HTTP, so such subtitles never showed; they are read from disk here instead.
 */
internal object LocalSubtitleFiles {
    private const val MAX_BYTES = 8L * 1024 * 1024

    /** The file [url] names, or null when it is not a local file link. */
    fun fileFor(url: String): File? {
        val trimmed = url.trim()
        val path = when {
            trimmed.startsWith("/") -> trimmed
            trimmed.startsWith("file:", ignoreCase = true) ->
                runCatching { URI(trimmed).path }.getOrNull()
                    ?: runCatching {
                        URLDecoder.decode("/" + trimmed.substringAfter(':').trimStart('/'), "UTF-8")
                    }.getOrNull()
            else -> null
        }
        return path?.takeIf { it.isNotBlank() }?.let(::File)
    }

    /** The bytes of [file]; fails when it is missing, empty or larger than a subtitle can be. */
    fun readBytes(file: File): ByteArray {
        require(file.isFile) { "subtitle file not found" }
        val size = file.length()
        require(size in 1..MAX_BYTES) { "subtitle file is empty or too large ($size bytes)" }
        return file.readBytes()
    }

    /** The text of the local subtitle [url] names (any common encoding), or null when not local. */
    fun readText(url: String): String? {
        val file = fileFor(url) ?: return null
        return SubtitleCharsetDetector.decode(readBytes(file))
    }
}
