package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceVideoMatcherTest {
    @Test
    fun releaseNameOfAFilmIsAFilm() {
        val query = DeviceVideoMatcher.query("The.Shawshank.Redemption.1994.1080p.BluRay.x264.mkv", "Download")!!
        assertEquals("The Shawshank Redemption", query.title)
        assertEquals(1994, query.year)
        assertFalse(query.series)
        assertNull(query.episode)
    }

    @Test
    fun episodeNameCarriesItsSeasonAndNumber() {
        val query = DeviceVideoMatcher.query("Breaking.Bad.S02E05.720p.WEB-DL.mkv", "Breaking Bad")!!
        assertEquals("Breaking Bad", query.title)
        assertTrue(query.series)
        assertEquals(2, query.season)
        assertEquals(5, query.episode)
    }

    @Test
    fun bareEpisodeTakesTheShowFromItsFolder() {
        val query = DeviceVideoMatcher.query("E03.mkv", "Dark Season 2")!!
        assertEquals("Dark", query.title)
        assertTrue(query.series)
        assertEquals(2, query.season)
        assertEquals(3, query.episode)
    }

    @Test
    fun numberedFileInAShowFolderIsAnEpisodeOfSeasonOne() {
        val query = DeviceVideoMatcher.query("07.mp4", "Chernobyl")!!
        assertEquals("Chernobyl", query.title)
        assertEquals(1, query.season)
        assertEquals(7, query.episode)
    }

    @Test
    fun bareEpisodeInAGenericFolderIsNotLookedUp() {
        assertNull(DeviceVideoMatcher.query("E03.mkv", "Download"))
        assertNull(DeviceVideoMatcher.query("01.mp4", null))
    }

    @Test
    fun matchedEpisodeAsksForItsEpisodeId() {
        val match = DeviceVideoMatch("tt0903747", "series", "Breaking Bad", 2, 5)
        assertEquals("tt0903747:2:5", match.subtitleVideoId)
        assertEquals("tt0111161", DeviceVideoMatch("tt0111161", "movie", "x", null, null).subtitleVideoId)
    }
}
