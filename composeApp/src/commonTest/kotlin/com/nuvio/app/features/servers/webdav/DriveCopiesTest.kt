package com.nuvio.app.features.servers.webdav

import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.player.skip.PlayerNextEpisodeRules
import com.nuvio.app.features.servers.ServerItemRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DriveCopiesTest {
    @Test
    fun sameVideoKeyGroupsAnEpisodeOrAFilmAcrossQualities() {
        assertEquals("test show s1e1", DriveCopies.sameVideoKey("Test Show S01E01 1080p.mkv"))
        assertEquals("test show s1e1", DriveCopies.sameVideoKey("Test.Show.S01E01.720p.WEB.mkv"))
        assertEquals("test show s1e1", DriveCopies.sameVideoKey("Test Show S01E01.mkv"))
        assertEquals("other show s1e1", DriveCopies.sameVideoKey("Other Show S01E01 720p.mkv"))
        assertEquals("movie name 2019", DriveCopies.sameVideoKey("Movie.Name.2019.1080p.BluRay.mkv"))
        assertEquals("movie name 2019", DriveCopies.sameVideoKey("Movie Name (2019) [720p].mp4"))
        assertNull(DriveCopies.sameVideoKey("Holiday clip.mp4"))
    }

    @Test
    fun theNextVideoIsNotAnotherCopyOfThisOne() {
        val folder = listOf(
            "Test Show S01E01.mkv",
            "Test Show S01E02 360p.mkv",
            "Test Show S01E02 720p.mkv",
            "Test Show S01E02.mkv",
            "Test Show S01E03.mkv",
        )
        assertEquals(4, DriveCopies.nextIndex(folder, 1))
        assertEquals(4, DriveCopies.nextIndex(folder, 3))
        assertEquals(1, DriveCopies.nextIndex(folder, 0))
        assertNull(DriveCopies.nextIndex(folder, 4))
        // Parts that name no quality both play.
        assertEquals(1, DriveCopies.nextIndex(listOf("Show S01E02 Part 1.mkv", "Show S01E02 Part 2.mkv"), 0))
        // Nothing after the copies.
        assertNull(DriveCopies.nextIndex(listOf("Film 2019 1080p.mkv", "Film 2019 720p.mkv"), 0))
        assertFalse(DriveCopies.areCopies("Show S01E02 HDR10.mkv", "Show S01E03 HDR10.mkv"))
    }

    @Test
    fun thePlayersNextEpisodeSkipsCopiesOfADriveFile() {
        fun file(name: String, episode: Int) = MetaVideo(
            id = ServerItemRef("pikpak", "/Test Show/$name").encode(),
            title = name,
            season = 1,
            episode = episode,
        )
        // Copies make the drive number the folder's files in order.
        val videos = listOf(
            file("Test Show S01E01.mkv", 1),
            file("Test Show S01E02 360p.mkv", 2),
            file("Test Show S01E02 720p.mkv", 3),
            file("Test Show S01E03.mkv", 4),
        )
        assertEquals(4, PlayerNextEpisodeRules.resolveNextEpisode(videos, 1, 2)?.episode)
        assertEquals(2, PlayerNextEpisodeRules.resolveNextEpisode(videos, 1, 1)?.episode)
        assertNull(PlayerNextEpisodeRules.resolveNextEpisode(videos, 1, 4))
        // Add-on episodes are not drive files: nothing is skipped.
        val addon = listOf(MetaVideo("tt1:1:1", "720p", season = 1, episode = 1), MetaVideo("tt1:1:2", "720p", season = 1, episode = 2))
        assertEquals(2, PlayerNextEpisodeRules.resolveNextEpisode(addon, 1, 1)?.episode)
    }
}
