package com.nuvio.app.features.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceVideoLibraryTest {
    private fun video(
        name: String,
        folder: String = "Movies",
        added: Long = 0L,
        size: Long = 0L,
        duration: Long = 0L,
    ) = DeviceVideo(
        uri = "content://media/external/video/media/${name.hashCode()}",
        name = name,
        folderId = folder.lowercase(),
        folderName = folder,
        addedEpochSec = added,
        sizeBytes = size,
        durationMs = duration,
    )

    @Test
    fun episodesSortByNumberNotByText() {
        val names = listOf("Show E10.mkv", "Show E2.mkv", "Show E1.mkv", "show e3.mkv")
        val sorted = DeviceVideoLibrary.sortVideos(names.map { video(it) }, DeviceVideoSort.NameAscending)
        assertEquals(listOf("Show E1.mkv", "Show E2.mkv", "show e3.mkv", "Show E10.mkv"), sorted.map(DeviceVideo::name))
        val reversed = DeviceVideoLibrary.sortVideos(names.map { video(it) }, DeviceVideoSort.NameDescending)
        assertEquals("Show E10.mkv", reversed.first().name)
    }

    @Test
    fun arabicIndicDigitsCountAsNumbers() {
        val sorted = listOf("حلقة ١٠", "حلقة ٢", "حلقة ١").sortedWith(DeviceVideoLibrary.naturalOrder)
        assertEquals(listOf("حلقة ١", "حلقة ٢", "حلقة ١٠"), sorted)
    }

    @Test
    fun leadingZerosDoNotChangeTheOrder() {
        val sorted = listOf("S01E10", "S01E02", "S01E1").sortedWith(DeviceVideoLibrary.naturalOrder)
        assertEquals(listOf("S01E1", "S01E02", "S01E10"), sorted)
    }

    @Test
    fun otherSortsUseTheirValueThenTheName() {
        val videos = listOf(
            video("b.mp4", added = 5, size = 10, duration = 3),
            video("a.mp4", added = 5, size = 30, duration = 1),
            video("c.mp4", added = 9, size = 20, duration = 2),
        )
        assertEquals(listOf("c.mp4", "a.mp4", "b.mp4"), DeviceVideoLibrary.sortVideos(videos, DeviceVideoSort.Newest).map { it.name })
        assertEquals(listOf("a.mp4", "b.mp4", "c.mp4"), DeviceVideoLibrary.sortVideos(videos, DeviceVideoSort.Oldest).map { it.name })
        assertEquals(listOf("a.mp4", "c.mp4", "b.mp4"), DeviceVideoLibrary.sortVideos(videos, DeviceVideoSort.Largest).map { it.name })
        assertEquals(listOf("b.mp4", "c.mp4", "a.mp4"), DeviceVideoLibrary.sortVideos(videos, DeviceVideoSort.Longest).map { it.name })
        assertEquals(listOf("a.mp4", "c.mp4", "b.mp4"), DeviceVideoLibrary.sortVideos(videos, DeviceVideoSort.Shortest).map { it.name })
    }

    @Test
    fun foldersGroupTheirVideosAndSortThemToo() {
        val videos = listOf(
            video("E2.mp4", folder = "Series", added = 1, size = 5),
            video("Clip.mp4", folder = "Camera", added = 7, size = 1),
            video("E1.mp4", folder = "Series", added = 3, size = 5),
        )
        val byName = DeviceVideoLibrary.folders(videos, DeviceVideoSort.NameAscending)
        assertEquals(listOf("Camera", "Series"), byName.map { it.name })
        assertEquals(listOf("E1.mp4", "E2.mp4"), byName[1].videos.map { it.name })
        assertEquals(10L, byName[1].totalBytes)

        val byNewest = DeviceVideoLibrary.folders(videos, DeviceVideoSort.Newest)
        assertEquals(listOf("Camera", "Series"), byNewest.map { it.name })
        assertEquals(listOf("E1.mp4", "E2.mp4"), byNewest[1].videos.map { it.name })

        assertEquals(listOf("Series", "Camera"), DeviceVideoLibrary.folders(videos, DeviceVideoSort.Largest).map { it.name })
    }

    @Test
    fun searchNeedsEveryWordInTheNameOrFolder() {
        val videos = listOf(video("The Film 2024.mkv", folder = "Download"), video("Other.mp4", folder = "Camera"))
        assertEquals(listOf("The Film 2024.mkv"), DeviceVideoLibrary.search(videos, " film  download ").map { it.name })
        assertEquals(2, DeviceVideoLibrary.search(videos, "  ").size)
        assertTrue(DeviceVideoLibrary.search(videos, "film camera").isEmpty())
    }

    @Test
    fun titlesDropOnlyVideoExtensions() {
        assertEquals("Film.2024.1080p", DeviceVideoLibrary.title("Film.2024.1080p.mkv"))
        assertEquals("Notes.v2", DeviceVideoLibrary.title("Notes.v2"))
        assertEquals(".mkv", DeviceVideoLibrary.title(".mkv"))
    }

    @Test
    fun lengthsSizesAndQuality() {
        assertEquals("", DeviceVideoLibrary.formatDuration(0))
        assertEquals("0:09", DeviceVideoLibrary.formatDuration(9_400))
        assertEquals("4:05", DeviceVideoLibrary.formatDuration(245_000))
        assertEquals("1:02:03", DeviceVideoLibrary.formatDuration(3_723_000))
        assertEquals("", DeviceVideoLibrary.formatSize(0))
        assertEquals("1 KB", DeviceVideoLibrary.formatSize(100))
        assertEquals("850 MB", DeviceVideoLibrary.formatSize(850L * 1024 * 1024))
        assertEquals("1.5 GB", DeviceVideoLibrary.formatSize(1536L * 1024 * 1024))
        assertEquals("1080p", DeviceVideoLibrary.qualityLabel(1920, 800))
        assertEquals("1080p", DeviceVideoLibrary.qualityLabel(1080, 1920))
        assertEquals("4K", DeviceVideoLibrary.qualityLabel(3840, 1600))
        assertEquals("720p", DeviceVideoLibrary.qualityLabel(1280, 720))
        assertNull(DeviceVideoLibrary.qualityLabel(320, 240))
        assertNull(DeviceVideoLibrary.qualityLabel(0, 0))
    }

    @Test
    fun resumeSkipsTheStartAndFinishedVideos() {
        fun at(position: Long, duration: Long) = DeviceVideoPosition(uri = "u", positionMs = position, durationMs = duration)
        assertEquals(0L, DeviceVideoLibrary.resumePositionMs(null))
        assertEquals(0L, DeviceVideoLibrary.resumePositionMs(at(5_000, 600_000)))
        assertEquals(120_000L, DeviceVideoLibrary.resumePositionMs(at(120_000, 600_000)))
        assertEquals(0L, DeviceVideoLibrary.resumePositionMs(at(590_000, 600_000)))
        assertTrue(DeviceVideoLibrary.isWatched(at(590_000, 600_000)))
        assertFalse(DeviceVideoLibrary.isWatched(at(590_000, 0)))
        // Length unknown: still resumable.
        assertEquals(120_000L, DeviceVideoLibrary.resumePositionMs(at(120_000, 0)))
        assertNull(DeviceVideoLibrary.progressFraction(at(120_000, 0)))
        assertEquals(0.2f, DeviceVideoLibrary.progressFraction(at(120_000, 600_000)))
    }

    @Test
    fun positionsKeepOneEntryPerFileNewestFirst() {
        val a1 = DeviceVideoPosition(uri = "a", positionMs = 60_000, durationMs = 600_000, updatedAtEpochMs = 1)
        val b = DeviceVideoPosition(uri = "b", positionMs = 60_000, durationMs = 600_000, updatedAtEpochMs = 2)
        val a2 = a1.copy(positionMs = 90_000, updatedAtEpochMs = 3)
        val merged = DeviceVideoLibrary.mergePosition(DeviceVideoLibrary.mergePosition(listOf(a1), b), a2)
        assertEquals(listOf("a", "b"), merged.map { it.uri })
        assertEquals(90_000L, merged.first().positionMs)

        val many = (1..DeviceVideoLibrary.MAX_POSITIONS + 5).fold(emptyList<DeviceVideoPosition>()) { list, i ->
            DeviceVideoLibrary.mergePosition(list, DeviceVideoPosition(uri = "u$i"))
        }
        assertEquals(DeviceVideoLibrary.MAX_POSITIONS, many.size)
        assertEquals("u${DeviceVideoLibrary.MAX_POSITIONS + 5}", many.first().uri)
    }

    @Test
    fun continueRowHasOnlyUnfinishedVideos() {
        val started = DeviceVideoPosition(uri = "s", positionMs = 60_000, durationMs = 600_000, updatedAtEpochMs = 1)
        val newer = DeviceVideoPosition(uri = "n", positionMs = 30_000, durationMs = 600_000, updatedAtEpochMs = 5)
        val finished = DeviceVideoPosition(uri = "f", positionMs = 599_000, durationMs = 600_000, updatedAtEpochMs = 9)
        val barely = DeviceVideoPosition(uri = "b", positionMs = 2_000, durationMs = 600_000, updatedAtEpochMs = 9)
        assertEquals(listOf("n", "s"), DeviceVideoLibrary.inProgress(listOf(started, newer, finished, barely)).map { it.uri })
    }

    @Test
    fun unknownSortFallsBackToName() {
        assertEquals(DeviceVideoSort.Newest, DeviceVideoLibrary.parseSort("Newest"))
        assertEquals(DeviceVideoSort.NameAscending, DeviceVideoLibrary.parseSort("bogus"))
        assertEquals(DeviceVideoSort.NameAscending, DeviceVideoLibrary.parseSort(null))
    }

    @Test
    fun hiddenFoldersVideosAndShortClipsLeaveTheList() {
        val film = video("Film.mkv", duration = 5_400_000)
        val clip = video("Clip.mp4", duration = 12_000)
        val unknown = video("Unknown.mkv", duration = 0)
        val camera = video("VID_1.mp4", folder = "Camera", duration = 600_000)
        val all = listOf(film, clip, unknown, camera)

        assertEquals(all, DeviceVideoLibrary.visible(all, emptySet(), emptySet(), 0L))
        assertEquals(
            listOf(film, clip, unknown),
            DeviceVideoLibrary.visible(all, hiddenFolders = setOf("camera"), hiddenVideos = emptySet(), minDurationMs = 0L),
        )
        assertEquals(
            listOf(clip, unknown, camera),
            DeviceVideoLibrary.visible(all, emptySet(), hiddenVideos = setOf(film.uri), minDurationMs = 0L),
        )
        // A length the media store does not know never counts as short.
        assertEquals(
            listOf(film, unknown, camera),
            DeviceVideoLibrary.visible(all, emptySet(), emptySet(), minDurationMs = 30_000L),
        )
        assertEquals(
            setOf(clip.uri, camera.uri, "content://gone"),
            DeviceVideoLibrary.hiddenUris(all, setOf("camera"), setOf("content://gone"), 30_000L),
        )
    }

    @Test
    fun nextVideoFollowsTheFolderOrderAndStopsAtItsEnd() {
        val e1 = video("Show E1.mkv", folder = "Series")
        val e2 = video("Show E2.mkv", folder = "Series")
        val e10 = video("Show E10.mkv", folder = "Series")
        val other = video("Other.mkv", folder = "Movies")
        val all = listOf(e10, other, e2, e1)
        val everything: (DeviceVideo) -> Boolean = { true }

        assertEquals(e2, DeviceVideoLibrary.nextInFolder(all, e1.uri, DeviceVideoSort.NameAscending, everything))
        assertEquals(e10, DeviceVideoLibrary.nextInFolder(all, e2.uri, DeviceVideoSort.NameAscending, everything))
        assertNull(DeviceVideoLibrary.nextInFolder(all, e10.uri, DeviceVideoSort.NameAscending, everything))
        // The library's own order: Z–A goes the other way.
        assertEquals(e2, DeviceVideoLibrary.nextInFolder(all, e10.uri, DeviceVideoSort.NameDescending, everything))
        // Only one file in its folder, or a file that is not in the library.
        assertNull(DeviceVideoLibrary.nextInFolder(all, other.uri, DeviceVideoSort.NameAscending, everything))
        assertNull(DeviceVideoLibrary.nextInFolder(all, "content://elsewhere/1", DeviceVideoSort.NameAscending, everything))
    }

    @Test
    fun nextVideoSkipsHiddenOnesEvenFromAHiddenVideo() {
        val e1 = video("Show E1.mkv", folder = "Series")
        val e2 = video("Show E2.mkv", folder = "Series")
        val e3 = video("Show E3.mkv", folder = "Series")
        val all = listOf(e1, e2, e3)
        val e2Hidden: (DeviceVideo) -> Boolean = { it.uri != e2.uri }

        assertEquals(e3, DeviceVideoLibrary.nextInFolder(all, e1.uri, DeviceVideoSort.NameAscending, e2Hidden))
        // Played from «Continue» although it is hidden: the next one still follows it.
        assertEquals(e3, DeviceVideoLibrary.nextInFolder(all, e2.uri, DeviceVideoSort.NameAscending, e2Hidden))
    }

    @Test
    fun choosingFoldersKeepsFoldersThatAreNotOnTheDeviceNow() {
        val folders = DeviceVideoLibrary.folders(
            listOf(video("a.mkv", folder = "Movies"), video("b.mkv", folder = "Camera"), video("c.mkv", folder = "WhatsApp")),
            DeviceVideoSort.NameAscending,
        )
        val before = mapOf("camera" to "Camera", "sdcard-old" to "Old card")
        val after = DeviceVideoLibrary.hiddenFoldersAfterChoice(before, folders, shownIds = setOf("movies", "camera"))
        assertEquals(mapOf("sdcard-old" to "Old card", "whatsapp" to "WhatsApp"), after)
    }

    @Test
    fun savedStateFromEarlierVersionsKeepsTheNewDefaults() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString(
            DeviceVideoStoredState.serializer(),
            """{"sort":"Newest","positions":[{"uri":"u","positionMs":61000,"durationMs":600000}]}""",
        )
        assertEquals("Newest", old.sort)
        assertEquals(1, old.positions.size)
        assertTrue(old.autoplayNext)
        assertEquals(0, old.minDurationSec)
        assertTrue(old.hiddenFolders.isEmpty())
        assertTrue(old.hiddenVideos.isEmpty())
        assertTrue(0 in DeviceVideoLibrary.SHORT_CLIP_CHOICES_SEC)
    }
}
