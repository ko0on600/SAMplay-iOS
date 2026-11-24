package com.nuvio.app.features.player

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SAMplay 2.0.4: Z Nation S2E14 on the TV lost its subtitle for the rest of the episode when an
 * Arabic subtitle was picked half an hour in: SubDL answered its by-then expired link with a
 * one-line stand-in. The phone asks for the list the same way.
 */
class SubtitleLinkRefreshTest {
    private val subDlStandIn = "WEBVTT\n\n00:00:00.000 --> 00:00:12.000\n" +
        "This subtitle action expired. Reopen the subtitle menu to refresh it.\n"

    private val realSubtitle = buildString {
        for (i in 1..40) {
            append(i).append('\n')
            append("00:%02d:%02d,000 --> 00:%02d:%02d,500\n".format(i / 60, i % 60, i / 60, i % 60))
            append("Line number ").append(i).append("\n\n")
        }
    }

    private var clock = 0L

    @AfterTest fun reset() = runBlocking {
        SubtitleLinks.refresher = { SubtitleRepository.freshLinkFor(it) }
        SubtitleLinks.nowMs = { System.nanoTime() / 1_000_000 }
        SubtitleLinks.clear()
    }

    @Test fun subDlsExpiredStandInIsRecognisedAndRead() {
        assertTrue(SubtitleStandIn.isStandIn(subDlStandIn))
        assertEquals("This subtitle action expired. Reopen the subtitle menu to refresh it.", SubtitleStandIn.text(subDlStandIn))
    }

    @Test fun realAndForcedSubtitlesAreNotStandIns() {
        assertFalse(SubtitleStandIn.isStandIn(realSubtitle))
        val forced = "1\n00:41:10,000 --> 00:41:12,000\nНет!\n\n2\n00:52:00,000 --> 00:52:02,000\nДа.\n"
        assertFalse(SubtitleStandIn.isStandIn(forced))
        assertFalse(SubtitleStandIn.isStandIn(""))
        assertTrue(SubtitleStandIn.isStandIn("WEBVTT\n\n00:00.000 --> 00:10.000\nTranslating, try again soon\n"))
    }

    @Test fun anExpiredLinkIsAskedForAgainAndTheFreshOneIsKept() = runBlocking {
        val asked = mutableListOf<String>()
        SubtitleLinks.nowMs = { clock }
        SubtitleLinks.refresher = { listed -> asked += listed; "https://api3.subdl.com/f/NEW/14.srt" }
        val fetched = mutableListOf<String>()
        val fetch: suspend (String) -> String = { url -> fetched += url; if ("NEW" in url) realSubtitle else subDlStandIn }

        assertEquals(realSubtitle, SubtitleLinks.download("https://api3.subdl.com/f/OLD/14.srt", fetch))
        assertEquals(listOf("https://api3.subdl.com/f/OLD/14.srt", "https://api3.subdl.com/f/NEW/14.srt"), fetched)
        assertEquals(1, asked.size)

        fetched.clear()
        assertEquals(realSubtitle, SubtitleLinks.download("https://api3.subdl.com/f/OLD/14.srt", fetch))
        assertEquals(listOf("https://api3.subdl.com/f/NEW/14.srt"), fetched)
        assertEquals(1, asked.size)
    }

    @Test fun aStandInThatStaysIsNotAskedForOverAndOver() = runBlocking {
        var asked = 0
        SubtitleLinks.nowMs = { clock }
        SubtitleLinks.refresher = { listed -> asked++; listed }
        val fetch: suspend (String) -> String = { subDlStandIn }

        assertEquals(subDlStandIn, SubtitleLinks.download("https://x/translate/1.srt", fetch))
        assertEquals(subDlStandIn, SubtitleLinks.download("https://x/translate/1.srt", fetch))
        assertEquals(1, asked)
        clock += SubtitleLinks.REASK_GAP_MS
        assertEquals(subDlStandIn, SubtitleLinks.download("https://x/translate/1.srt", fetch))
        assertEquals(2, asked)
    }

    @Test fun aSubtitleTheListDoesNotHoldIsKeptAsItCame() = runBlocking {
        assertEquals(subDlStandIn, SubtitleLinks.download("https://x/not-listed.srt") { subDlStandIn })
    }

    @Test fun theSameSubtitleIsFoundInTheFreshList() {
        fun sub(id: String, url: String, addon: String = "SubDL Official Subtitles", language: String = "ar") =
            AddonSubtitle(id = id, url = url, language = language, display = "Arabic", addonName = addon)
        val old = sub("subdl:faa5819d", "https://api3.subdl.com/f/vMp8/14.srt")
        val fresh = listOf(
            sub("subdl:3adef278", "https://api3.subdl.com/f/AAAA/14.srt"),
            sub("subdl:faa5819d", "https://api3.subdl.com/f/BBBB/14.srt"),
        )
        assertEquals("https://api3.subdl.com/f/BBBB/14.srt", freshMatch(old, fresh)?.url)

        // An id that is only a place in the list must not pick another file.
        assertNull(freshMatch(sub("subdl_3", "https://h/f/x/A.srt"), listOf(sub("subdl_3", "https://h/f/y/B.srt"), sub("subdl_4", "https://h/f/z/C.srt"))))
        assertEquals(
            "https://h/f/y/Z.Nation.S02E14.srt",
            freshMatch(sub("ar-111", "https://h/f/x/Z.Nation.S02E14.srt"), listOf(sub("ar-222", "https://h/f/y/Z.Nation.S02E14.srt")))?.url,
        )
        assertNull(freshMatch(sub("ar-1", "https://h/f/x/14.srt"), listOf(sub("ar-2", "https://h/f/y/14.srt"), sub("ar-3", "https://h/f/z/14.srt"))))
        assertNull(freshMatch(old, listOf(sub("subdl:faa5819d", "https://other/1.srt", addon = "SubMaker"))))
    }
}
