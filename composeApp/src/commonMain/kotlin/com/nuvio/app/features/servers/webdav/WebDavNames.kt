package com.nuvio.app.features.servers.webdav

/** What a file or folder name says about the video inside it. */
internal data class ParsedMediaName(
    val title: String,
    val year: Int?,
    val season: Int?,
    val episode: Int?,
)

/** Release-name parsing for files on a WebDAV drive (PikPak, NAS, cloud drives). */
internal object WebDavNames {
    private val VIDEO_EXTENSIONS = setOf(
        "mkv", "mp4", "m4v", "avi", "mov", "wmv", "flv", "webm", "ts", "m2ts", "mts", "mpg", "mpeg",
        "vob", "ogv", "3gp", "rmvb", "divx", "iso", "strm",
        // Nuvio RS: older and broadcast formats (old Arabic films are often RealMedia or ASF).
        "rm", "asf", "f4v", "ogm", "m2t", "m2v", "tp", "trp", "mxf", "mk3d", "qt",
    )
    private val SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "sub")

    private val SXXEXX = Regex("""(?i)\bs(\d{1,2})[\s._-]*e(\d{1,3})\b""")
    private val NXNN = Regex("""(?i)\b(\d{1,2})x(\d{2,3})\b""")
    // \b only sees ASCII word characters, so the Arabic words are matched outside it.
    private val EPISODE_ONLY = Regex("""(?i)(?:(?:\b|_)(?:e|ep|episode)|الحلقة)[\s._-]*(\d{1,3})\b""")
    private val SEASON_FOLDER = Regex("""(?i)(?:\b(?:s|season|saison|staffel)|الموسم)[\s._-]*(\d{1,2})\b""")
    /** "Show - 01", "Show_-_12v2 [1080p]": the numbering anime releases use. */
    private val DASH_EPISODE = Regex("""(?:^|[\s._])-[\s._]*(\d{1,3})(?:v\d)?(?=$|[\s._\[(])""")
    /** A name that is only a number ("01", "12 - Pilot"). */
    private val LEADING_NUMBER = Regex("""^(\d{1,3})(?:$|[\s._-])""")
    private val SEASON_MARKERS =
        Regex("""(?i)(?:\b(?:s\d{1,2}|season[\s._-]*\d{1,2}|saison[\s._-]*\d{1,2}|staffel[\s._-]*\d{1,2}|complete)\b|الموسم[\s._-]*\d{1,2}\b)""")
    private val YEAR = Regex("""(?:^|[\s._(\[-])((?:19|20)\d{2})(?:$|[\s._)\]-])""")
    private val NOISE = Regex(
        """(?i)\b(2160p|1080p|720p|576p|480p|4k|uhd|hdr10?\+?|bluray|blu-ray|bdrip|brrip|web-?dl|webrip|hdtv|dvdrip|remux|x26[45]|h\.?26[45]|hevc|aac\d?(?:\.\d)?|ac3|eac3|ddp\d?(?:\.\d)?|dts(?:-hd)?|truehd|atmos|10bit|8bit|repack|amzn|dsnp|hmax|atvp)\b.*$""",
    )

    fun extension(name: String): String = name.trim().substringAfterLast('.', "").lowercase()

    fun isVideo(name: String): Boolean = extension(name) in VIDEO_EXTENSIONS

    fun isSubtitle(name: String): Boolean = extension(name) in SUBTITLE_EXTENSIONS

    /** Images, NFO, text and other non-playable sidecars that share a folder with videos. */
    fun isSidecar(name: String): Boolean = extension(name) in SIDECAR_EXTENSIONS

    private val SIDECAR_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "nfo", "txt", "url", "torrent", "nzb",
        "exe", "apk", "zip", "rar", "7z", "json", "xml", "html", "htm", "cue", "m3u", "m3u8",
    )

    /**
     * Nuvio RS: files that are surely no video: audio, documents and the like. A drive file with a
     * dot but none of the video extensions ("الرسالة.1976", "فيلم.HD", an old ".rm" film) is still
     * listed unless it is one of these, a subtitle or a sidecar.
     */
    fun isKnownNonVideo(name: String): Boolean = extension(name) in NON_VIDEO_EXTENSIONS

    private val NON_VIDEO_EXTENSIONS = setOf(
        "mp3", "flac", "m4a", "aac", "wav", "ogg", "oga", "opus", "wma", "ape", "aiff", "mka", "mid",
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "epub", "mobi", "azw3", "csv", "md",
        "log", "ini", "db", "sqlite", "sfv", "md5", "sha1", "par2", "msi", "dmg", "pkg", "deb", "ttf",
        "otf", "svg", "ico", "psd", "heic", "tif", "tiff", "lrc", "idx", "sup", "js", "css",
    )

    fun baseName(name: String): String = name.substringBeforeLast('.', name)

    /** "The.Matrix.1999.1080p.BluRay.x264.mkv" → title "The Matrix", year 1999. */
    fun parse(name: String): ParsedMediaName {
        val base = if (isVideo(name) || isSubtitle(name)) baseName(name) else name
        var season: Int? = null
        var episode: Int? = null
        var cut = base.length
        SXXEXX.find(base)?.let {
            season = it.groupValues[1].toInt()
            episode = it.groupValues[2].toInt()
            cut = minOf(cut, it.range.first)
        } ?: NXNN.find(base)?.let {
            season = it.groupValues[1].toInt()
            episode = it.groupValues[2].toInt()
            cut = minOf(cut, it.range.first)
        } ?: EPISODE_ONLY.find(base)?.let {
            episode = it.groupValues[1].toInt()
            cut = minOf(cut, it.range.first)
        } ?: DASH_EPISODE.find(base)?.let {
            episode = it.groupValues[1].toInt()
            cut = minOf(cut, it.range.first)
        }
        val yearMatch = YEAR.findAll(base).lastOrNull { it.range.first > 0 }
        val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()
        if (yearMatch != null) cut = minOf(cut, yearMatch.range.first)
        val title = base.take(cut)
            .replace('.', ' ')
            .replace('_', ' ')
            .replace(NOISE, "")
            .replace(Regex("""[\[(]\s*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '[', '(')
        return ParsedMediaName(title = title.ifBlank { base }, year = year, season = season, episode = episode)
    }

    /** The episode number a file name carries, including a bare leading number ("01.mkv"). */
    fun episodeNumber(name: String): Int? =
        parse(name).episode ?: LEADING_NUMBER.find(baseName(name))?.groupValues?.get(1)?.toIntOrNull()

    /** True for "Season 2", "S02", "الموسم 2": a folder that only names a season. */
    fun isSeasonLabel(name: String): Boolean =
        seasonOfFolder(name) != null && normalize(name.replace(SEASON_MARKERS, "")).length <= 2

    /** The title to look a file or folder up by: release noise and season markers removed. */
    fun lookupTitle(name: String): String =
        parse(name).title.replace(SEASON_MARKERS, " ").replace(Regex("""\s+"""), " ").trim(' ', '-', '.')

    /** Season number a folder name states ("Season 2", "S02", "الموسم 2"), if any. */
    fun seasonOfFolder(name: String): Int? =
        SEASON_FOLDER.find(name)?.groupValues?.get(1)?.toIntOrNull()
            ?: SXXEXX.find(name)?.groupValues?.get(1)?.toIntOrNull()

    /** Lower-case, accent-light, punctuation-free form used for title comparison. */
    fun normalize(value: String): String = value
        .lowercase()
        // Arabic spelling variants: hamza forms of alef, teh marbuta, alef maksura, tatweel.
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
        .replace('ة', 'ه').replace('ى', 'ي').replace("ـ", "")
        .replace('&', ' ')
        .replace(Regex("""\band\b"""), " ")
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    /** True when a parsed [candidate] title names the same work as [wanted]. */
    fun titleMatches(candidate: String, wanted: String): Boolean {
        val a = normalize(candidate)
        val b = normalize(wanted)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val articles = Regex("""^(the|a|an) """)
        return a.replace(articles, "") == b.replace(articles, "")
    }

    /** Case-insensitive order that compares digit runs as numbers ("Ep 2" < "Ep 10"). */
    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val startA = i
                val startB = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val numA = a.substring(startA, i).trimStart('0')
                val numB = b.substring(startB, j).trimStart('0')
                if (numA.length != numB.length) return numA.length - numB.length
                val byValue = numA.compareTo(numB)
                if (byValue != 0) return byValue
            } else {
                val byChar = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (byChar != 0) return byChar
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    /** "1.4 GB" style size label. */
    fun sizeLabel(bytes: Long?): String? {
        if (bytes == null || bytes <= 0) return null
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        val rounded = if (unit >= 3) (value * 10).toLong() / 10.0 else value.toLong().toDouble()
        val text = if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
        return "$text ${units[unit]}"
    }
}
