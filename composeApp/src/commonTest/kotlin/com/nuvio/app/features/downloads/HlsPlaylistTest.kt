package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HlsPlaylistTest {
    private val faselProxy = "http://145.241.158.129:3112/stream?url=https%3A%2F%2Fr467--x.c.scdns.io%2Fstream" +
        "%2Fv1%2Fhls%2Fabc%2F160_hd1080b_playlist.m3u8&ref=https%3A%2F%2Fwww.fasel-hd.co"

    @Test
    fun proxyLinkWithAPlaylistInsideIsHls() {
        assertTrue(HlsPlaylist.looksLikeHlsUrl(faselProxy))
        assertTrue(HlsPlaylist.looksLikeHlsUrl("https://cdn.example/v/index.M3U8?token=1"))
        assertFalse(HlsPlaylist.looksLikeHlsUrl("https://cdn.example/v/Show.S01E01.mkv"))
        assertFalse(HlsPlaylist.looksLikeHlsUrl("https://cdn.example/v/file.mp4#x.m3u8"))
        assertTrue(HlsPlaylist.isHlsContentType("application/vnd.apple.mpegurl"))
        assertTrue(HlsPlaylist.isHlsContentType("audio/x-mpegURL; charset=utf-8"))
        assertFalse(HlsPlaylist.isHlsContentType("video/mp2t"))
    }

    @Test
    fun faselStylePlaylistGivesItsPiecesInOrder() {
        val text = """
            #EXTM3U
            #EXT-X-TARGETDURATION:2
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-MEDIA-SEQUENCE:1
            #EXTINF:2.002,
            http://145.241.158.129:3112/stream?url=a%2Fsegment_1.ts&ref=x
            #EXTINF:1.952,
            http://145.241.158.129:3112/stream?url=a%2Fsegment_3.ts&ref=x
            #EXTINF:0.050,
            http://145.241.158.129:3112/stream?url=a%2Fsegment_4.ts&ref=x
            #EXT-X-ENDLIST
        """.trimIndent()
        assertTrue(HlsPlaylist.looksLikePlaylist("﻿\n$text"))
        assertFalse(HlsPlaylist.isMaster(text))
        val media = HlsPlaylist.parseMedia(text, faselProxy)
        assertEquals(3, media.segments.size)
        assertEquals("http://145.241.158.129:3112/stream?url=a%2Fsegment_3.ts&ref=x", media.segments[1].uri)
        assertEquals(listOf(1L, 2L, 3L), media.segments.map { it.sequence })
        assertEquals(4.004, media.durationSec, 0.0001)
        assertTrue(media.ended)
        assertFalse(media.isFragmentedMp4)
        assertNull(HlsPlaylist.unsupportedReason(media))
    }

    @Test
    fun masterPicksTheSharpestPictureAndSeesSeparateSound() {
        val text = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="English",URI="audio/en.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2"
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,AUDIO="aac"
            ../hd/index.m3u8?t=1
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720
            /mid/index.m3u8
        """.trimIndent()
        assertTrue(HlsPlaylist.isMaster(text))
        val master = HlsPlaylist.parseMaster(text, "https://cdn.example/show/ep1/master.m3u8?sig=9")
        assertEquals(3, master.variants.size)
        assertEquals("https://cdn.example/show/ep1/low/index.m3u8", master.variants[0].uri)
        assertEquals("https://cdn.example/mid/index.m3u8", master.variants[2].uri)
        val best = assertNotNull(HlsPlaylist.pickVariant(master))
        assertEquals("https://cdn.example/show/hd/index.m3u8?t=1", best.uri)
        assertEquals(1080, best.height)
        assertTrue(HlsPlaylist.hasSeparateAudio(master, best))
        assertFalse(HlsPlaylist.hasSeparateAudio(master, master.variants[0]))
    }

    @Test
    fun keysByteRangesAndInitSection() {
        val text = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:7
            #EXT-X-MAP:URI="init.mp4",BYTERANGE="720@0"
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x000102030405060708090a0b0c0d0e0f
            #EXTINF:4,
            #EXT-X-BYTERANGE:1000@720
            all.m4s
            #EXTINF:4,
            #EXT-X-BYTERANGE:500
            all.m4s
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:4,
            clear.m4s
            #EXT-X-ENDLIST
        """.trimIndent()
        val media = HlsPlaylist.parseMedia(text, "https://cdn.example/a/b/media.m3u8")
        assertEquals("https://cdn.example/a/b/init.mp4", media.mapUri)
        assertEquals(0L..719L, media.mapByteRange)
        assertTrue(media.isFragmentedMp4)
        assertEquals(720L..1719L, media.segments[0].byteRange)
        assertEquals(1720L..2219L, media.segments[1].byteRange)
        assertEquals("AES-128", media.segments[0].key?.method)
        assertEquals("https://cdn.example/a/b/key.bin", media.segments[0].key?.uri)
        assertEquals("0x000102030405060708090a0b0c0d0e0f", media.segments[1].key?.ivHex)
        assertNull(media.segments[2].key)
        assertEquals(listOf(7L, 8L, 9L), media.segments.map { it.sequence })
        assertNull(HlsPlaylist.unsupportedReason(media))
    }

    @Test
    fun liveAndProtectedStreamsSayWhyNot() {
        val live = HlsPlaylist.parseMedia("#EXTM3U\n#EXTINF:6,\na.ts\n#EXTINF:6,\nb.ts\n", "https://x.example/live.m3u8")
        assertFalse(live.ended)
        assertEquals("This is a live stream; it has no end to download", HlsPlaylist.unsupportedReason(live))
        val sample = HlsPlaylist.parseMedia(
            "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://1\"\n#EXTINF:6,\na.ts\n#EXT-X-ENDLIST\n",
            "https://x.example/v.m3u8",
        )
        assertEquals("This stream is protected and cannot be downloaded", HlsPlaylist.unsupportedReason(sample))
    }

    @Test
    fun attributesKeepQuotedCommas() {
        val attrs = HlsPlaylist.attributes("BANDWIDTH=1,CODECS=\"avc1.4d401e,mp4a.40.2\",RESOLUTION=1x2,NAME=\"a=b\"")
        assertEquals("avc1.4d401e,mp4a.40.2", attrs["CODECS"])
        assertEquals("1x2", attrs["RESOLUTION"])
        assertEquals("a=b", attrs["NAME"])
    }

    @Test
    fun relativeAddressesResolveLikeABrowser() {
        val base = "http://145.241.158.129:3112/stream?url=x"
        assertEquals("http://145.241.158.129:3112/seg1.ts", HlsPlaylist.resolve(base, "seg1.ts"))
        assertEquals("https://cdn.example/a/c.ts", HlsPlaylist.resolve("https://cdn.example/a/b/p.m3u8", "../c.ts"))
        assertEquals("https://other.example/x.ts", HlsPlaylist.resolve("https://cdn.example/a/p.m3u8", "//other.example/x.ts"))
        assertEquals("https://cdn.example/a/p.m3u8?page=2", HlsPlaylist.resolve("https://cdn.example/a/p.m3u8?page=1", "?page=2"))
    }
}
