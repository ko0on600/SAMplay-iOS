package com.nuvio.app.features.player

import androidx.media3.common.util.Consumer
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ReadableSubtitlesTest {
    private class Recording : SubtitleParser {
        var seen = ByteArray(0)

        override fun parse(
            data: ByteArray,
            offset: Int,
            length: Int,
            outputOptions: SubtitleParser.OutputOptions,
            output: Consumer<CuesWithTiming>,
        ) {
            seen = data.copyOfRange(offset, offset + length)
        }

        override fun getCueReplacementBehavior(): Int = 0
    }

    private val srt = "1\n00:00:01,000 --> 00:00:02,000\nعلينا المغادرة قبل حلول الظلام\n"

    private fun parse(bytes: ByteArray): ByteArray {
        val recording = Recording()
        ReadableSubtitleParser(recording).parse(bytes, 0, bytes.size, SubtitleParser.OutputOptions.allCues()) { }
        return recording.seen
    }

    @Test
    fun `an arabic file in windows 1256 reaches the parser as utf 8`() {
        val seen = parse(srt.toByteArray(Charset.forName("windows-1256")))
        assertEquals(srt, seen.decodeToString())
    }

    @Test
    fun `a utf 8 file is left alone`() {
        val bytes = srt.toByteArray()
        assertContentEquals(bytes, parse(bytes))
    }
}
