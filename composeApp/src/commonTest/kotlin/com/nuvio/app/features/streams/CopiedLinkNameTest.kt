package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CopiedLinkNameTest {
    @Test
    fun episodeAndMovieNames() {
        assertEquals(
            "Impractical Jokers - S13E07 - Guest of Dishonor",
            CopiedLinkName.episodeName(" Impractical Jokers ", 13, 7, "Guest of Dishonor"),
        )
        assertEquals("Z Nation - S02E13", CopiedLinkName.episodeName("Z Nation", 2, 13, " "))
        assertEquals("The Odyssey", CopiedLinkName.episodeName("The Odyssey", null, null, "ignored"))
    }

    @Test
    fun proxyPlaylistGetsTheEpisodeNameAsTs() {
        val link = "http://145.241.158.129:3112/stream?url=https%3A%2F%2Fcdn%2F160_hd1080b_playlist.m3u8&ref=x"
        assertEquals(
            "$link#Impractical%20Jokers%20-%20S13E07%20-%20Guest%20of%20Dishonor.ts",
            CopiedLinkName.withName(link, "Impractical Jokers - S13E07 - Guest of Dishonor"),
        )
    }

    @Test
    fun linksThatNameTheirFileStayAsTheyAre() {
        val debrid = "https://download.real-debrid.com/d/ABC/Show.S01E01.1080p.mkv"
        assertEquals(debrid, CopiedLinkName.withName(debrid, "Show - S01E01"))
        val pikpak = "https://cdn.mypikpak.com/download/?fid=1#My%20Name%20Is%20Earl%20-%20S01E01.mp4"
        assertEquals(pikpak, CopiedLinkName.withName(pikpak, "Other"))
    }

    @Test
    fun aNamelessDirectLinkGetsMp4AndUnsafeCharactersGo() {
        assertEquals(
            "https://h.example/play/123#Show%20-%20S01E02%20-%20Who%20What.mp4",
            CopiedLinkName.withName("https://h.example/play/123", "Show - S01E02 - Who/What?"),
        )
        assertEquals("https://h.example/play/123", CopiedLinkName.withName("https://h.example/play/123", "  "))
    }

    @Test
    fun whatCountsAsAFileName() {
        assertTrue(CopiedLinkName.pathNamesFile("https://x/a/video.mp4?sig=1"))
        assertFalse(CopiedLinkName.pathNamesFile("https://x/stream?url=y.mkv"))
        assertFalse(CopiedLinkName.pathNamesFile("https://x/download/"))
        assertFalse(CopiedLinkName.pathNamesFile("https://x/v/12.5"))
    }
}
