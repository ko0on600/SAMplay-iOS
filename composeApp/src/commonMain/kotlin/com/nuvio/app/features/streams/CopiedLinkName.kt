package com.nuvio.app.features.streams

import com.nuvio.app.features.downloads.HlsPlaylist

/**
 * SAMplay 2.0.4: «Copy stream link» names the file after '#' when the link itself names none, so a
 * download manager (SMD 1.1.8+ reads it) saves "Impractical Jokers - S13E07 - Guest of Dishonor.ts"
 * instead of "stream" (FaselHD's proxy links end in `/stream?url=…`) or "playlist". The part after
 * '#' is never sent to the server, so the link works as before everywhere else.
 */
internal object CopiedLinkName {
    fun episodeName(title: String, seasonNumber: Int?, episodeNumber: Int?, episodeTitle: String?): String {
        val show = title.trim()
        if (seasonNumber == null || episodeNumber == null) return show
        return buildString {
            append(show)
            append(" - S").append(seasonNumber.toString().padStart(2, '0'))
            append('E').append(episodeNumber.toString().padStart(2, '0'))
            episodeTitle?.trim()?.takeIf { it.isNotEmpty() }?.let { append(" - ").append(it) }
        }
    }

    /**
     * [url] with `#name.ext` added when it has no '#' yet and its address names no file of its own
     * (always for a playlist: "playlist.m3u8" or "index.m3u8" says nothing about the episode).
     */
    fun withName(url: String, name: String): String {
        if ('#' in url) return url
        val cleanName = name.map { if (it < ' ' || it in "\\/:*?\"<>|") ' ' else it }.joinToString("")
            .replace(Regex("\\s+"), " ").trim()
        if (cleanName.isEmpty()) return url
        val hls = HlsPlaylist.looksLikeHlsUrl(url)
        if (!hls && pathNamesFile(url)) return url
        val extension = if (hls) "ts" else "mp4"
        return url + "#" + encode("$cleanName.$extension")
    }

    /** "…/Show.S01E01.mkv?x=1" names a file; "…/stream?url=…" and "…/download/" do not. */
    internal fun pathNamesFile(url: String): Boolean {
        val last = url.substringBefore('#').substringBefore('?').trimEnd('/').substringAfterLast('/')
        val extension = last.substringAfterLast('.', "")
        return '.' in last && extension.length in 2..5 && extension.all { it.isLetterOrDigit() } && extension.any { it.isLetter() }
    }

    private fun encode(text: String): String = buildString {
        for (byte in text.encodeToByteArray()) {
            val c = byte.toInt() and 0xFF
            if (c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code ||
                c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code
            ) {
                append(c.toChar())
            } else {
                append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
            }
        }
    }

    private const val HEX = "0123456789ABCDEF"
}
