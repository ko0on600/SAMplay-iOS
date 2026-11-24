package com.nuvio.app.features.player

import java.io.File
import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SAMplay 2.0: AI subtitles and device files reach the player as file:// links. */
class LocalSubtitleFilesTest {
    private fun pathOf(url: String) = LocalSubtitleFiles.fileFor(url)?.path?.replace('\\', '/')

    @Test
    fun fileLinksAndPathsAreLocal() {
        assertEquals(
            "/data/user/0/com.sami.samplay/files/ai_subtitles/ee75627b/ml_ar_English (Sync Arabic test).srt",
            pathOf("file:///data/user/0/com.sami.samplay/files/ai_subtitles/ee75627b/ml_ar_English%20(Sync%20Arabic%20test).srt"),
        )
        assertEquals("/data/user/0/com.sami.samplay/cache/subtitles/1_x.srt", pathOf("file:/data/user/0/com.sami.samplay/cache/subtitles/1_x.srt"))
        assertEquals("/sdcard/Download/sub.srt", pathOf("/sdcard/Download/sub.srt"))
    }

    @Test
    fun onlineLinksAreNotLocal() {
        assertNull(LocalSubtitleFiles.fileFor("http://10.0.2.2:8092/late%2B4s-ar-utf8.srt"))
        assertNull(LocalSubtitleFiles.fileFor("https://example.org/sub.srt"))
        assertNull(LocalSubtitleFiles.readText("https://example.org/sub.srt"))
    }

    @Test
    fun readsAnArabicWindows1256File() {
        val file = File.createTempFile("samplay-sub", ".srt")
        try {
            val srt = "1\n00:00:01,000 --> 00:00:02,500\nهل أنت بخير؟\n\n2\n00:00:03,000 --> 00:00:04,000\nعلينا أن نتحرك الآن.\n"
            file.writeBytes(srt.toByteArray(Charset.forName("windows-1256")))
            val text = assertNotNull(LocalSubtitleFiles.readText(file.toURI().toString()))
            assertTrue("هل أنت بخير؟" in text, text)
            assertTrue("علينا أن نتحرك الآن." in text, text)
        } finally {
            file.delete()
        }
    }
}
