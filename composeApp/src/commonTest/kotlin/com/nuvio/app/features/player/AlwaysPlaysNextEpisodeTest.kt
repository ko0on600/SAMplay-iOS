package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlwaysPlaysNextEpisodeTest {
    @Test
    fun aDriveFolderAlwaysGoesOn() {
        assertTrue(alwaysPlaysNextEpisode(true, false, "https://dav.example/a.mkv", nextEpisodeDownloaded = false))
    }

    @Test
    fun aDownloadGoesOnToTheNextDownloadedEpisode() {
        assertTrue(alwaysPlaysNextEpisode(false, false, "file:///data/user/0/x/files/downloads/E1.mkv", nextEpisodeDownloaded = true))
        assertTrue(alwaysPlaysNextEpisode(false, false, "content://downloads/E1", nextEpisodeDownloaded = true))
    }

    @Test
    fun aDownloadWaitsWhenTheNextEpisodeIsNotDownloaded() {
        assertFalse(alwaysPlaysNextEpisode(false, false, "file:///data/E1.mkv", nextEpisodeDownloaded = false))
    }

    @Test
    fun anOnlineStreamFollowsTheSetting() {
        assertFalse(alwaysPlaysNextEpisode(false, false, "https://cdn.example/E1.mkv", nextEpisodeDownloaded = true))
    }

    @Test
    fun aDeviceVideoHasItsOwnNextVideo() {
        assertFalse(alwaysPlaysNextEpisode(false, true, "content://media/external/video/media/1", nextEpisodeDownloaded = true))
    }
}
