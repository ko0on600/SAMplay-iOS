package com.nuvio.app.features.downloads

/**
 * SAMplay 2.0.4: reading HLS playlists (.m3u8) so «Download file» can save a stream that is cut into
 * many small pieces (FaselHD and similar add-ons) as one file. Before, the downloader saved the
 * playlist text itself (a few hundred KB of addresses) or, behind a proxy, never got that far.
 *
 * Only what a download needs: the best picture of a master playlist, the pieces of a media playlist
 * with their AES-128 keys, byte ranges and init section. Rules only, no network: the Android side
 * ([transferHlsDownload]) fetches.
 */
internal object HlsPlaylist {
    data class Variant(
        val uri: String,
        val bandwidth: Long,
        val height: Int?,
        val audioGroup: String?,
    )

    data class Master(
        val variants: List<Variant>,
        /** GROUP-ID of the audio renditions that have their own playlist (sound kept apart from the picture). */
        val separateAudioGroups: Set<String>,
    )

    data class Key(val method: String, val uri: String?, val ivHex: String?)

    data class Segment(
        val uri: String,
        val durationSec: Double,
        /** Media sequence number (the AES-128 IV when the key gives none). */
        val sequence: Long,
        val key: Key?,
        /** Bytes of [uri] to take (EXT-X-BYTERANGE), null for the whole answer. */
        val byteRange: LongRange?,
    )

    data class Media(
        val segments: List<Segment>,
        val mapUri: String?,
        val mapByteRange: LongRange?,
        /** False for a live playlist (no end): it never finishes, so it cannot be saved. */
        val ended: Boolean,
    ) {
        val isFragmentedMp4: Boolean get() = mapUri != null
        val durationSec: Double get() = segments.sumOf { it.durationSec }
    }

    /** A link that names an .m3u8 playlist, also when it sits inside a proxy's query (`?url=…playlist.m3u8`). */
    fun looksLikeHlsUrl(url: String): Boolean {
        val lower = url.lowercase()
        val path = lower.substringBefore('#').substringBefore('?')
        return path.endsWith(".m3u8") || path.endsWith(".m3u") || ".m3u8" in lower.substringBefore('#')
    }

    fun isHlsContentType(contentType: String?): Boolean =
        contentType?.lowercase()?.contains("mpegurl") == true

    fun looksLikePlaylist(text: String): Boolean =
        text.trimStart('﻿', ' ', '\t', '\r', '\n').startsWith("#EXTM3U")

    fun isMaster(text: String): Boolean = text.lineSequence().any { it.trim().startsWith("#EXT-X-STREAM-INF") }

    fun parseMaster(text: String, baseUrl: String): Master {
        val variants = mutableListOf<Variant>()
        val separateAudio = mutableSetOf<String>()
        var pending: Map<String, String>? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXT-X-STREAM-INF:") -> pending = attributes(line.substringAfter(':'))
                line.startsWith("#EXT-X-MEDIA:") -> {
                    val media = attributes(line.substringAfter(':'))
                    if (media["TYPE"].equals("AUDIO", ignoreCase = true) && !media["URI"].isNullOrBlank()) {
                        media["GROUP-ID"]?.let(separateAudio::add)
                    }
                }
                line.startsWith("#") -> Unit
                pending != null -> {
                    val attrs = pending
                    variants += Variant(
                        uri = resolve(baseUrl, line),
                        bandwidth = attrs["AVERAGE-BANDWIDTH"]?.toLongOrNull() ?: attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L,
                        height = attrs["RESOLUTION"]?.substringAfter('x', "")?.toIntOrNull(),
                        audioGroup = attrs["AUDIO"],
                    )
                    pending = null
                }
            }
        }
        return Master(variants, separateAudio)
    }

    /** The sharpest picture: tallest, then the highest bit rate. */
    fun pickVariant(master: Master): Variant? =
        master.variants.maxWithOrNull(compareBy<Variant>({ it.height ?: 0 }, { it.bandwidth }))

    /** True when [variant]'s sound comes from its own playlist, which one file of pieces would lack. */
    fun hasSeparateAudio(master: Master, variant: Variant): Boolean =
        variant.audioGroup != null && variant.audioGroup in master.separateAudioGroups

    fun parseMedia(text: String, baseUrl: String): Media {
        val segments = mutableListOf<Segment>()
        var sequence = 0L
        var duration = 0.0
        var key: Key? = null
        var range: LongRange? = null
        var mapUri: String? = null
        var mapRange: LongRange? = null
        var ended = false
        var vod = false
        val rangeEnds = mutableMapOf<String, Long>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                    sequence = line.substringAfter(':').trim().toLongOrNull() ?: sequence
                line.startsWith("#EXTINF:") ->
                    duration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                line.startsWith("#EXT-X-KEY:") -> {
                    val attrs = attributes(line.substringAfter(':'))
                    val method = attrs["METHOD"]?.uppercase() ?: "NONE"
                    key = if (method == "NONE") null else Key(method, attrs["URI"]?.let { resolve(baseUrl, it) }, attrs["IV"])
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    val attrs = attributes(line.substringAfter(':'))
                    mapUri = attrs["URI"]?.let { resolve(baseUrl, it) }
                    mapRange = attrs["BYTERANGE"]?.let { parseByteRange(it, 0L) }
                }
                line.startsWith("#EXT-X-BYTERANGE:") -> range = line.substringAfter(':').trim().let { value ->
                    // An offset left out continues where the previous range of the same piece ended.
                    parseByteRange(value, null)
                        ?: value.substringBefore('@').trim().toLongOrNull()?.let { length -> LongRange(-1L, length) }
                }
                line == "#EXT-X-ENDLIST" -> ended = true
                line.startsWith("#EXT-X-PLAYLIST-TYPE:") -> vod = line.substringAfter(':').trim().equals("VOD", true)
                line.startsWith("#") -> Unit
                else -> {
                    val uri = resolve(baseUrl, line)
                    val pieceRange = range?.let { r ->
                        if (r.first >= 0L) r else {
                            val start = rangeEnds[uri] ?: 0L
                            start until start + r.last
                        }
                    }
                    pieceRange?.let { rangeEnds[uri] = it.last + 1 }
                    segments += Segment(uri, duration, sequence, key, pieceRange)
                    sequence++
                    duration = 0.0
                    range = null
                }
            }
        }
        return Media(segments, mapUri, mapRange, ended = ended || vod)
    }

    /** Why this playlist cannot be saved, or null when it can (clear or AES-128 pieces). */
    fun unsupportedReason(media: Media): String? = when {
        media.segments.isEmpty() -> "The stream has no pieces to download"
        !media.ended -> "This is a live stream; it has no end to download"
        media.segments.any { it.key != null && (it.key.method != "AES-128" || it.key.uri.isNullOrBlank()) } ->
            "This stream is protected and cannot be downloaded"
        else -> null
    }

    /** "1000@200" → 200..1199; "1000" → [offsetIfMissing] start, or null when the offset is unknown. */
    private fun parseByteRange(value: String, offsetIfMissing: Long?): LongRange? {
        val length = value.substringBefore('@').trim().toLongOrNull() ?: return null
        val offset = if ('@' in value) value.substringAfter('@').trim().toLongOrNull() else offsetIfMissing
        if (offset == null || length <= 0L) return null
        return offset until offset + length
    }

    /** KEY=VALUE,KEY="quoted, with commas" */
    internal fun attributes(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var i = 0
        while (i < text.length) {
            val eq = text.indexOf('=', i)
            if (eq < 0) break
            val name = text.substring(i, eq).trim().trimStart(',').trim()
            var j = eq + 1
            val value: String
            if (j < text.length && text[j] == '"') {
                val close = text.indexOf('"', j + 1).let { if (it < 0) text.length else it }
                value = text.substring(j + 1, close)
                j = close + 1
            } else {
                val comma = text.indexOf(',', j).let { if (it < 0) text.length else it }
                value = text.substring(j, comma).trim()
                j = comma
            }
            if (name.isNotEmpty()) result[name.uppercase()] = value
            i = j + 1
        }
        return result
    }

    /** [ref] against the playlist's own address, as a browser would. */
    fun resolve(baseUrl: String, ref: String): String {
        val reference = ref.trim()
        val lower = reference.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return reference
        val base = baseUrl.substringBefore('#')
        val scheme = base.substringBefore("://", "https")
        if (reference.startsWith("//")) return "$scheme:$reference"
        val afterScheme = base.substringAfter("://")
        val authorityEnd = afterScheme.indexOfAny(charArrayOf('/', '?')).let { if (it < 0) afterScheme.length else it }
        val origin = "$scheme://" + afterScheme.substring(0, authorityEnd)
        val basePath = afterScheme.substring(authorityEnd).substringBefore('?').ifEmpty { "/" }
        if (reference.startsWith("?")) return origin + basePath + reference
        val (refPath, refQuery) = reference.indexOf('?').let { q ->
            if (q < 0) reference to "" else reference.substring(0, q) to reference.substring(q)
        }
        val joined = if (refPath.startsWith("/")) refPath else basePath.substringBeforeLast('/') + "/" + refPath
        val parts = ArrayDeque<String>()
        for (part in joined.split('/').drop(1)) {
            when (part) {
                "." -> Unit
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(part)
            }
        }
        return origin + "/" + parts.joinToString("/") + refQuery
    }
}
