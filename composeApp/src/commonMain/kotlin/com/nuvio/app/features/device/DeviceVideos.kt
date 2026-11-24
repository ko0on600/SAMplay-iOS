package com.nuvio.app.features.device

import kotlinx.serialization.Serializable

/**
 * SAMplay: a video file on this device, from Android's media store or opened from another app
 * (or with «Open file»). [uri] is what the player opens; [mediaId] is the media store row when
 * there is one (thumbnails come from it), -1 otherwise.
 */
data class DeviceVideo(
    val uri: String,
    val name: String,
    val folderId: String = "",
    val folderName: String = "",
    val durationMs: Long = 0L,
    val sizeBytes: Long = 0L,
    val addedEpochSec: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val mediaId: Long = -1L,
)

data class DeviceVideoFolder(
    val id: String,
    val name: String,
    val videos: List<DeviceVideo>,
) {
    val totalBytes: Long get() = videos.sumOf { it.sizeBytes }
    val totalDurationMs: Long get() = videos.sumOf { it.durationMs }
    val newestEpochSec: Long get() = videos.maxOfOrNull { it.addedEpochSec } ?: 0L
}

enum class DeviceVideoSort {
    NameAscending,
    NameDescending,
    Newest,
    Oldest,
    Largest,
    Smallest,
    Longest,
    Shortest,
}

/** Where the viewer stopped in a device video; kept on this device only (not synced). */
@Serializable
data class DeviceVideoPosition(
    val uri: String,
    val title: String = "",
    val folderName: String = "",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAtEpochMs: Long = 0L,
)

@Serializable
internal data class DeviceVideoStoredState(
    val sort: String? = null,
    val positions: List<DeviceVideoPosition> = emptyList(),
    /** Folders left out of the library: folder id → its name (for the «show again» list). */
    val hiddenFolders: Map<String, String> = emptyMap(),
    /** Videos left out of the library: file uri → its name. */
    val hiddenVideos: Map<String, String> = emptyMap(),
    /** «Hide short clips»: videos shorter than this many seconds are left out; 0 = off. */
    val minDurationSec: Int = 0,
    /** When a video ends, the next one in its folder plays. */
    val autoplayNext: Boolean = true,
)

/** Pure rules of the device library, kept apart so they are unit-tested. */
internal object DeviceVideoLibrary {
    /** Below this the start is not worth resuming. */
    const val MIN_RESUME_MS = 10_000L
    /** Past this share of the length the video counts as watched. */
    const val WATCHED_FRACTION = 0.95
    /** Positions kept; the oldest go first. */
    const val MAX_POSITIONS = 300

    private val videoExtensions = setOf(
        "mp4", "m4v", "mkv", "webm", "avi", "mov", "wmv", "flv", "ts", "m2ts", "mts", "3gp", "mpg", "mpeg", "ogv",
    )

    /** The file name without its video extension ("Film.2024.mkv" → "Film.2024"). */
    fun title(name: String): String {
        val trimmed = name.trim()
        val dot = trimmed.lastIndexOf('.')
        if (dot <= 0) return trimmed
        val extension = trimmed.substring(dot + 1).lowercase()
        return if (extension in videoExtensions) trimmed.substring(0, dot) else trimmed
    }

    /**
     * Natural order, as file managers sort: digit runs compare by value ("Ep 2" before "Ep 10",
     * Arabic-Indic digits too), the rest ignores case.
     */
    val naturalOrder: Comparator<String> = Comparator { a, b -> compareNatural(a, b) }

    fun sortVideos(videos: List<DeviceVideo>, sort: DeviceVideoSort): List<DeviceVideo> {
        val byName = Comparator<DeviceVideo> { a, b -> naturalOrder.compare(a.name, b.name) }
        val comparator: Comparator<DeviceVideo> = when (sort) {
            DeviceVideoSort.NameAscending -> byName
            DeviceVideoSort.NameDescending -> byName.reversed()
            DeviceVideoSort.Newest -> compareByDescending<DeviceVideo> { it.addedEpochSec }.then(byName)
            DeviceVideoSort.Oldest -> compareBy<DeviceVideo> { it.addedEpochSec }.then(byName)
            DeviceVideoSort.Largest -> compareByDescending<DeviceVideo> { it.sizeBytes }.then(byName)
            DeviceVideoSort.Smallest -> compareBy<DeviceVideo> { it.sizeBytes }.then(byName)
            DeviceVideoSort.Longest -> compareByDescending<DeviceVideo> { it.durationMs }.then(byName)
            DeviceVideoSort.Shortest -> compareBy<DeviceVideo> { it.durationMs }.then(byName)
        }
        return videos.sortedWith(comparator)
    }

    /** Videos grouped by folder; folders and the videos inside follow [sort]. */
    fun folders(videos: List<DeviceVideo>, sort: DeviceVideoSort): List<DeviceVideoFolder> {
        val folders = videos
            .groupBy { it.folderId }
            .map { (id, folderVideos) ->
                DeviceVideoFolder(
                    id = id,
                    name = folderVideos.first().folderName.ifBlank { id },
                    videos = sortVideos(folderVideos, sort),
                )
            }
        val byName = Comparator<DeviceVideoFolder> { a, b -> naturalOrder.compare(a.name, b.name) }
        val comparator: Comparator<DeviceVideoFolder> = when (sort) {
            DeviceVideoSort.NameAscending -> byName
            DeviceVideoSort.NameDescending -> byName.reversed()
            DeviceVideoSort.Newest -> compareByDescending<DeviceVideoFolder> { it.newestEpochSec }.then(byName)
            DeviceVideoSort.Oldest -> compareBy<DeviceVideoFolder> { it.newestEpochSec }.then(byName)
            DeviceVideoSort.Largest -> compareByDescending<DeviceVideoFolder> { it.totalBytes }.then(byName)
            DeviceVideoSort.Smallest -> compareBy<DeviceVideoFolder> { it.totalBytes }.then(byName)
            DeviceVideoSort.Longest -> compareByDescending<DeviceVideoFolder> { it.totalDurationMs }.then(byName)
            DeviceVideoSort.Shortest -> compareBy<DeviceVideoFolder> { it.totalDurationMs }.then(byName)
        }
        return folders.sortedWith(comparator)
    }

    /** Videos whose name or folder holds every word of [query]; all of them when it is blank. */
    fun search(videos: List<DeviceVideo>, query: String): List<DeviceVideo> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotEmpty)
        if (words.isEmpty()) return videos
        return videos.filter { video ->
            val haystack = "${video.name} ${video.folderName}".lowercase()
            words.all(haystack::contains)
        }
    }

    /** "1:02:03" or "4:05"; empty when the length is unknown. */
    fun formatDuration(ms: Long): String {
        if (ms <= 0L) return ""
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "$hours:${minutes.twoDigits()}:${seconds.twoDigits()}"
        } else {
            "$minutes:${seconds.twoDigits()}"
        }
    }

    /** "1.4 GB", "850 MB", "12 KB"; empty when the size is unknown. */
    fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return ""
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            bytes >= gb -> "${oneDecimal(bytes / gb)} GB"
            bytes >= mb -> "${(bytes / mb).toLong()} MB"
            else -> "${maxOf(1L, (bytes / kb).toLong())} KB"
        }
    }

    /** "4K", "1080p" … from the frame size (portrait videos count by their long side too). */
    fun qualityLabel(width: Int, height: Int): String? {
        if (width <= 0 || height <= 0) return null
        val long = maxOf(width, height)
        val short = minOf(width, height)
        return when {
            long >= 3200 || short >= 2000 -> "4K"
            long >= 2400 || short >= 1400 -> "1440p"
            long >= 1800 || short >= 1000 -> "1080p"
            long >= 1200 || short >= 700 -> "720p"
            long >= 800 || short >= 460 -> "480p"
            else -> null
        }
    }

    fun isWatched(position: DeviceVideoPosition?): Boolean {
        position ?: return false
        if (position.durationMs <= 0L) return false
        return position.positionMs >= position.durationMs * WATCHED_FRACTION
    }

    /** Where to start: the saved place, unless it is near the start or the video was finished. */
    fun resumePositionMs(position: DeviceVideoPosition?): Long {
        position ?: return 0L
        if (position.positionMs < MIN_RESUME_MS) return 0L
        if (isWatched(position)) return 0L
        return position.positionMs
    }

    /** 0..1 for the progress bar; null when there is nothing to show. */
    fun progressFraction(position: DeviceVideoPosition?): Float? {
        position ?: return null
        if (position.durationMs <= 0L || position.positionMs < MIN_RESUME_MS) return null
        return (position.positionMs.toDouble() / position.durationMs).coerceIn(0.0, 1.0).toFloat()
    }

    /** [update] replaces the saved place of its file; newest first, at most [MAX_POSITIONS]. */
    fun mergePosition(current: List<DeviceVideoPosition>, update: DeviceVideoPosition): List<DeviceVideoPosition> =
        (listOf(update) + current.filter { it.uri != update.uri }).take(MAX_POSITIONS)

    /** Started but not finished, newest first: the «Continue» row. */
    fun inProgress(positions: Collection<DeviceVideoPosition>): List<DeviceVideoPosition> =
        positions
            .filter { resumePositionMs(it) > 0L }
            .sortedByDescending(DeviceVideoPosition::updatedAtEpochMs)

    fun parseSort(raw: String?): DeviceVideoSort =
        DeviceVideoSort.entries.firstOrNull { it.name == raw } ?: DeviceVideoSort.NameAscending

    /** «Hide short clips» choices in seconds, 0 = off (as in SAMplay v32). */
    val SHORT_CLIP_CHOICES_SEC: List<Int> = listOf(0, 30, 60, 180, 300)

    /**
     * Shown in the library: not in a hidden folder, not hidden itself, and not shorter than
     * [minDurationMs]. A length the media store does not know never counts as short. Hiding
     * never touches the file.
     */
    fun isVisible(video: DeviceVideo, hiddenFolders: Set<String>, hiddenVideos: Set<String>, minDurationMs: Long): Boolean =
        video.folderId !in hiddenFolders &&
            video.uri !in hiddenVideos &&
            (minDurationMs <= 0L || video.durationMs <= 0L || video.durationMs >= minDurationMs)

    fun visible(videos: List<DeviceVideo>, hiddenFolders: Set<String>, hiddenVideos: Set<String>, minDurationMs: Long): List<DeviceVideo> {
        if (hiddenFolders.isEmpty() && hiddenVideos.isEmpty() && minDurationMs <= 0L) return videos
        return videos.filter { isVisible(it, hiddenFolders, hiddenVideos, minDurationMs) }
    }

    /** Files the library leaves out (they stay out of «Continue watching» too). */
    fun hiddenUris(videos: List<DeviceVideo>, hiddenFolders: Set<String>, hiddenVideos: Set<String>, minDurationMs: Long): Set<String> =
        videos.filterNot { isVisible(it, hiddenFolders, hiddenVideos, minDurationMs) }.mapTo(HashSet()) { it.uri } + hiddenVideos

    /**
     * The video after [currentUri] in its folder, in the library's [sort], for «play the next
     * video». Videos [isShown] leaves out are skipped, but the current one may itself be hidden
     * (played from «Continue»). Null at the end of the folder or for a file not in the library.
     */
    fun nextInFolder(
        videos: List<DeviceVideo>,
        currentUri: String,
        sort: DeviceVideoSort,
        isShown: (DeviceVideo) -> Boolean,
    ): DeviceVideo? {
        val current = videos.firstOrNull { it.uri == currentUri } ?: return null
        val folder = videos.filter { it.folderId == current.folderId && (it.uri == currentUri || isShown(it)) }
        val ordered = sortVideos(folder, sort)
        val index = ordered.indexOfFirst { it.uri == currentUri }
        return ordered.getOrNull(index + 1)
    }

    /**
     * «Choose folders to show»: the folders on the device now are hidden unless in [shownIds];
     * hidden folders that are not on the device now (a removed card) stay hidden.
     */
    fun hiddenFoldersAfterChoice(
        current: Map<String, String>,
        present: List<DeviceVideoFolder>,
        shownIds: Set<String>,
    ): Map<String, String> {
        val presentIds = present.mapTo(HashSet()) { it.id }
        return current.filterKeys { it !in presentIds } +
            present.filter { it.id !in shownIds }.associate { it.id to it.name }
    }

    private fun compareNatural(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val endA = runEnd(a, i, digits = true)
                val endB = runEnd(b, j, digits = true)
                val c = compareDigitRuns(a, i, endA, b, j, endB)
                if (c != 0) return c
                i = endA
                j = endB
            } else if (!ca.isDigit() && !cb.isDigit()) {
                val endA = runEnd(a, i, digits = false)
                val endB = runEnd(b, j, digits = false)
                val c = a.substring(i, endA).lowercase().compareTo(b.substring(j, endB).lowercase())
                if (c != 0) return c
                i = endA
                j = endB
            } else {
                // Digits before letters, as file managers do.
                return if (ca.isDigit()) -1 else 1
            }
        }
        val rest = (a.length - i).compareTo(b.length - j)
        return if (rest != 0) rest else a.compareTo(b)
    }

    private fun runEnd(s: String, start: Int, digits: Boolean): Int {
        var k = start
        while (k < s.length && s[k].isDigit() == digits) k++
        return k
    }

    private fun compareDigitRuns(a: String, startA: Int, endA: Int, b: String, startB: Int, endB: Int): Int {
        var i = startA
        var j = startB
        while (i < endA - 1 && a[i].digitValue() == 0) i++
        while (j < endB - 1 && b[j].digitValue() == 0) j++
        val lengthA = endA - i
        val lengthB = endB - j
        if (lengthA != lengthB) return lengthA.compareTo(lengthB)
        while (i < endA) {
            val c = a[i].digitValue().compareTo(b[j].digitValue())
            if (c != 0) return c
            i++
            j++
        }
        return 0
    }

    private fun Char.digitValue(): Int = runCatching { digitToInt() }.getOrDefault(0)

    private fun Long.twoDigits(): String = toString().padStart(2, '0')

    private fun oneDecimal(value: Double): String {
        val tenths = kotlin.math.round(value * 10).toLong()
        return "${tenths / 10}.${tenths % 10}"
    }
}
