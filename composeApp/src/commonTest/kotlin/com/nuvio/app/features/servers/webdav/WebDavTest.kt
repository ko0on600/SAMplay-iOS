package com.nuvio.app.features.servers.webdav

import com.nuvio.app.features.settings.webDavAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebDavTest {
    @Test
    fun parsesMultistatusWithPrefixedNamespaceAndEncodedHrefs() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:">
  <D:response>
    <D:href>/My%20Pack/</D:href>
    <D:propstat><D:prop><D:displayname>My Pack</D:displayname><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat>
  </D:response>
  <D:response>
    <D:href>/My%20Pack/The.Matrix.1999.1080p.BluRay.x264.mkv</D:href>
    <D:propstat><D:prop><D:displayname>The.Matrix.1999.1080p.BluRay.x264.mkv</D:displayname><D:resourcetype/><D:getcontentlength>1503238553</D:getcontentlength><D:getcontenttype>video/x-matroska</D:getcontenttype></D:prop></D:propstat>
  </D:response>
  <D:response>
    <D:href>https://dav.pikpak.ai/My%20Pack/Show%20%26%20Co/</D:href>
    <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat>
  </D:response>
</D:multistatus>"""
        val entries = WebDavClient.parseMultistatus(xml)
        assertEquals(3, entries.size)
        assertEquals("/My Pack/", entries[0].path)
        assertTrue(entries[0].isFolder)
        assertEquals("/My Pack/The.Matrix.1999.1080p.BluRay.x264.mkv", entries[1].path)
        assertFalse(entries[1].isFolder)
        assertEquals(1503238553L, entries[1].size)
        assertEquals("/My Pack/Show & Co/", entries[2].path)
        assertEquals("Show & Co", entries[2].name)
    }

    @Test
    fun parsesUnprefixedNamespace() {
        val xml = """<multistatus xmlns="DAV:"><response><href>/a.mp4</href><propstat><prop><resourcetype/></prop></propstat></response></multistatus>"""
        val entries = WebDavClient.parseMultistatus(xml)
        assertEquals(listOf("/a.mp4"), entries.map { it.path })
        assertFalse(entries.single().isFolder)
    }

    @Test
    fun parsesMovieAndEpisodeNames() {
        val movie = WebDavNames.parse("The.Matrix.1999.1080p.BluRay.x264.mkv")
        assertEquals("The Matrix", movie.title)
        assertEquals(1999, movie.year)
        assertNull(movie.episode)

        val episode = WebDavNames.parse("Breaking Bad S02E05 720p WEB-DL.mp4")
        assertEquals("Breaking Bad", episode.title)
        assertEquals(2, episode.season)
        assertEquals(5, episode.episode)

        val crossed = WebDavNames.parse("Dark 1x03.mkv")
        assertEquals(1, crossed.season)
        assertEquals(3, crossed.episode)

        assertEquals(2, WebDavNames.seasonOfFolder("Season 2"))
        assertEquals(3, WebDavNames.seasonOfFolder("S03"))
    }

    @Test
    fun matchesTitlesLoosely() {
        assertTrue(WebDavNames.titleMatches("The Matrix", "Matrix"))
        assertTrue(WebDavNames.titleMatches("Fast and Furious", "Fast & Furious"))
        assertFalse(WebDavNames.titleMatches("The Matrix Reloaded", "The Matrix"))
    }

    @Test
    fun sortsNamesLikeAFileManager() {
        val names = listOf("Show E10.mkv", "show e2.mkv", "Show E1.mkv", "Show E02b.mkv", "Movie.mkv")
        assertEquals(
            listOf("Movie.mkv", "Show E1.mkv", "show e2.mkv", "Show E02b.mkv", "Show E10.mkv"),
            names.sortedWith(Comparator { a, b -> WebDavNames.naturalCompare(a, b) }),
        )
    }

    @Test
    fun numbersEpisodesAndNamesShowFolders() {
        assertEquals(2, WebDavNames.episodeNumber("Breaking.Bad.S01E02.1080p.mkv"))
        assertEquals(12, WebDavNames.episodeNumber("[Group] Frieren - 12 [1080p].mkv"))
        assertEquals(3, WebDavNames.episodeNumber("03 - The Pilot.mp4"))
        assertEquals(7, WebDavNames.episodeNumber("الحلقة 7.mp4"))
        assertNull(WebDavNames.episodeNumber("The.Matrix.1999.1080p.mkv"))
        assertNull(WebDavNames.episodeNumber("1917.2019.mkv"))
        assertTrue(WebDavNames.isSeasonLabel("Season 2"))
        assertTrue(WebDavNames.isSeasonLabel("S03"))
        assertTrue(WebDavNames.isSeasonLabel("الموسم 2"))
        assertEquals(2, WebDavNames.seasonOfFolder("الموسم 2"))
        assertFalse(WebDavNames.isSeasonLabel("Breaking Bad Season 2"))
        assertEquals("Breaking Bad", WebDavNames.lookupTitle("Breaking.Bad.S01-S05.Complete.1080p.BluRay"))
        assertEquals("The Matrix", WebDavNames.lookupTitle("The.Matrix.1999.1080p.BluRay.x264.mkv"))
    }

    @Test
    fun comparesArabicTitlesAcrossSpellings() {
        assertEquals(WebDavNames.normalize("الاختيار"), WebDavNames.normalize("الإختيار"))
        assertEquals(WebDavNames.normalize("الحفرة"), WebDavNames.normalize("الحفره"))
        assertTrue("الحفرة".hasArabic())
        assertFalse("The Pit".hasArabic())
        assertEquals("Tom & Jerry's", decodeHtml("Tom &amp; Jerry&#39;s"))
    }

    @Test
    fun recognisesVideoAndSubtitleFiles() {
        assertTrue(WebDavNames.isVideo("a.MKV"))
        assertTrue(WebDavNames.isSubtitle("a.en.srt"))
        assertFalse(WebDavNames.isVideo("poster.jpg"))
    }

    @Test
    fun buildsAddressesFromTheSheetFields() {
        assertEquals("https://dav.pikpak.ai", webDavAddress("dav.pikpak.ai", useHttps = true, port = "", folder = ""))
        assertEquals("http://dav.mypikpak.com:80/My Pack", webDavAddress("dav.mypikpak.com", useHttps = false, port = "80", folder = "/My Pack/"))
        assertEquals("https://nas.local:5006/dav", webDavAddress("https://nas.local:5006/dav", useHttps = false, port = "1", folder = ""))
        assertEquals("https://dav.pikpak.ai", WebDavClient.normalizeAddress("dav.pikpak.ai/"))
        assertEquals("/", WebDavClient.rootPath("https://dav.pikpak.ai"))
        assertEquals("/dav/", WebDavClient.rootPath("https://nas.local:5006/dav"))
    }

    @Test
    fun fileNameKeepsExtensionWhenDisplayNameOmitsIt() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:">
  <D:response>
    <D:href>/%D8%B9%D8%B1%D8%A8%D9%8A/</D:href>
    <D:propstat><D:prop><D:displayname>عربي</D:displayname><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat>
  </D:response>
  <D:response>
    <D:href>/%D8%B9%D8%B1%D8%A8%D9%8A/%D9%81%D9%8A%D9%84%D9%85.mkv</D:href>
    <D:propstat><D:prop><D:displayname>فيلم</D:displayname><D:resourcetype/><D:getcontentlength>100</D:getcontentlength></D:prop></D:propstat>
  </D:response>
</D:multistatus>"""
        val entries = WebDavClient.parseMultistatus(xml)
        assertEquals(2, entries.size)
        assertEquals("عربي", entries[0].name)
        assertTrue(entries[0].isFolder)
        assertEquals("فيلم", entries[1].name)
        assertEquals("فيلم.mkv", entries[1].fileName)
        assertTrue(WebDavNames.isVideo(entries[1].fileName))
        assertFalse(WebDavNames.isVideo(entries[1].name))
        assertEquals("https://dav.pikpak.ai/%D8%B9%D8%B1%D8%A8%D9%8A/", WebDavClient.resolve("https://dav.pikpak.ai", "/عربي/"))
        assertEquals(
            "https://dav.pikpak.ai/%D8%B9%D8%B1%D8%A8%D9%8A/",
            WebDavClient.resolve("https://dav.pikpak.ai", "/%D8%B9%D8%B1%D8%A8%D9%8A/"),
        )
    }

    @Test
    fun resolveEncodesPlusAndOtherReservedCharactersInNames() {
        // PikPak reads a raw "+" in a path as a space and answers 404 (SAMplay 1.8.8).
        assertEquals(
            "https://dav.pikpak.ai/Show/Show.S01E01.DD%2B5.1.mkv",
            WebDavClient.resolve("https://dav.pikpak.ai", "/Show/Show.S01E01.DD+5.1.mkv"),
        )
        assertEquals(
            "https://dav.pikpak.ai/A%20%26%20B%20%5B1080p%5D%20%28x%27%29.mkv",
            WebDavClient.resolve("https://dav.pikpak.ai", "/A & B [1080p] (x').mkv"),
        )
    }


    @Test
    fun looksLikeVideoUsesContentTypeAndExtensionlessNames() {
        val withType = WebDavEntry(
            path = "/عربي/abc123",
            name = "فيلم",
            isFolder = false,
            size = 10L,
            contentType = "video/mp4",
            lastModified = null,
        )
        assertTrue(withType.looksLikeVideo())
        assertFalse(WebDavNames.isVideo(withType.fileName))
        assertFalse(WebDavNames.isVideo(withType.name))

        val extensionless = withType.copy(contentType = null)
        assertTrue(extensionless.looksLikeVideo())

        val poster = WebDavEntry("/a/poster.jpg", "poster.jpg", false, 1L, "image/jpeg", null)
        assertFalse(poster.looksLikeVideo())
    }

    @Test
    fun namesWithADotButNoVideoExtensionAreStillListed() {
        fun file(href: String, name: String = href.substringAfterLast('/'), type: String? = null) =
            WebDavEntry(href, name, false, 10L, type, null)
        // Titles with a dot and no extension, as PikPak shows renamed files.
        assertTrue(file("/عربي/abc123", "الرسالة.1976").looksLikeVideo())
        assertTrue(file("/عربي/فيلم.HD").looksLikeVideo())
        assertTrue(file("/عربي/abc123", "فيلم.HD", "application/octet-stream").looksLikeVideo())
        // Old formats and a stray space after the extension.
        assertTrue(file("/عربي/فيلم قديم.rm").looksLikeVideo())
        assertTrue(file("/عربي/مسرحية.asf").looksLikeVideo())
        assertTrue(file("/عربي/abc123", "فيلم.mkv ").looksLikeVideo())
        // Surely no video.
        assertFalse(file("/عربي/اغنية.mp3").looksLikeVideo())
        assertFalse(file("/عربي/abc123", "كتاب.pdf").looksLikeVideo())
        assertFalse(file("/عربي/abc123", "صوت", "audio/mpeg").looksLikeVideo())
        assertFalse(file("/عربي/فيلم.ar.srt").looksLikeVideo())
        assertFalse(file("/عربي/folder.jpg").looksLikeVideo())
        assertFalse(file("/عربي/فيلم.rar").looksLikeVideo())
    }

    @Test
    fun resolvesRelativeHrefsAgainstRequestFolder() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:">
  <D:response>
    <D:href>/%D8%B9%D8%B1%D8%A8%D9%8A/</D:href>
    <D:propstat><D:prop><D:displayname>عربي</D:displayname><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat>
  </D:response>
  <D:response>
    <D:href>clip.mkv</D:href>
    <D:propstat><D:prop><D:displayname>مشهد</D:displayname><D:resourcetype/><D:getcontenttype>video/x-matroska</D:getcontenttype></D:prop></D:propstat>
  </D:response>
  <D:response>
    <D:href>subdir/</D:href>
    <D:propstat><D:prop><D:displayname>فرعي</D:displayname><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat>
  </D:response>
</D:multistatus>"""
        val entries = WebDavClient.parseMultistatus(xml, requestPath = "/عربي/")
        assertEquals(3, entries.size)
        assertEquals("/عربي/clip.mkv", entries[1].path)
        assertEquals("clip.mkv", entries[1].fileName)
        assertTrue(entries[1].looksLikeVideo())
        assertEquals("/عربي/subdir/", entries[2].path)
        assertTrue(entries[2].isFolder)
    }

    @Test
    fun sectionOverrideKeepsItsKindThroughSharing() {
        val section = WebDavInfoOverride(type = WebDavInfoOverride.TYPE_FOLDER)
        assertTrue(section.isFolder)
        // A section with no name or picture still says "not a film", so it is kept.
        assertFalse(section.isEmpty)
        assertEquals(WebDavInfoOverride.TYPE_FOLDER, section.shared().type)
        assertTrue(WebDavInfoOverride().isEmpty)
        assertFalse(WebDavInfoOverride(type = "movie", imdbId = "tt0111161").isFolder)
    }

}
