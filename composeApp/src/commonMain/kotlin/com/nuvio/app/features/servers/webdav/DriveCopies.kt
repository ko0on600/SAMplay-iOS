package com.nuvio.app.features.servers.webdav

/**
 * Nuvio RS (from SAMplay 1.8.9): which files of a drive folder are copies of one video at other
 * qualities ("Show S01E02 720p.mkv" next to "Show S01E02 360p.mkv"). The next episode skips them:
 * "S01E02 360p" was followed by "S01E02 720p".
 */
object DriveCopies {
    private val EPISODE = Regex("""(?i)(?<![a-z0-9])s(\d{1,2})[ ._-]*e(\d{1,3})(?![0-9])""")
    private val VIDEO_EXT = Regex("""\.[A-Za-z0-9]{2,4}$""")
    private val QUALITY_WORD = Regex(
        """(?<![a-z0-9])(4320p|8k|2160p|4k|uhd|1440p|2k|qhd|1080[pi]|fhd|full ?hd|720p|hd|576p|480p|sd|360p|240p|144p)(?![a-z0-9])""",
    )
    private val SEPARATORS = Regex("[._]+")
    private val BRACKETS = Regex("""[\[\]()\-]+""")
    private val SPACES = Regex("""\s+""")

    /**
     * What makes two files the same video: the show and episode ("test show s1e2") when the name
     * has one, else the name up to its first quality word ("movie name 2019" of
     * "Movie.Name.2019.1080p.mkv"). Null for a film whose name says no quality.
     */
    fun sameVideoKey(name: String): String? {
        val base = name.replace(VIDEO_EXT, "").lowercase().replace(SEPARATORS, " ")
        EPISODE.find(base)?.let { match ->
            val season = match.groupValues[1].toInt()
            val episode = match.groupValues[2].toInt()
            return (tidy(base.substring(0, match.range.first)) + " s${season}e$episode").trim()
        }
        val cut = QUALITY_WORD.find(base)?.range?.first ?: return null
        return tidy(base.substring(0, cut)).ifEmpty { null }
    }

    /** The name states a quality ("1080p", "4K", "HD"…). */
    fun statesQuality(name: String): Boolean =
        QUALITY_WORD.containsMatchIn(name.lowercase().replace('_', ' ').replace('.', ' '))

    /**
     * Copies of one video: the same [sameVideoKey], and at least one of the two names states a
     * quality, so "Show S01E02 Part 1" and "Part 2" both still play.
     */
    fun areCopies(a: String, b: String): Boolean {
        if (a == b) return false
        val key = sameVideoKey(a) ?: return false
        return key == sameVideoKey(b) && (statesQuality(a) || statesQuality(b))
    }

    /** The index after [current] in [names] that is not a copy of it; null at the folder's end. */
    fun nextIndex(names: List<String>, current: Int): Int? {
        if (current !in names.indices) return null
        return (current + 1 until names.size).firstOrNull { !areCopies(names[it], names[current]) }
    }

    private fun tidy(text: String): String = text.replace(BRACKETS, " ").replace(SPACES, " ").trim()
}
