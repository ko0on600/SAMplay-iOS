package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubtitleTimeShiftTest {
    @Test
    fun srtLinesMoveByTheDelayAndKeepEverythingElse() {
        val srt = "1\r\n00:00:01,000 --> 00:00:03,500\r\n<i>Hello</i>\r\n\r\n2\r\n00:59:59,900 --> 01:00:01,000\r\nأهلًا\r\n"
        val shifted = SubtitleTimeShift.shift(srt, 1_500)
        assertEquals(
            "1\r\n00:00:02,500 --> 00:00:05,000\r\n<i>Hello</i>\r\n\r\n2\r\n01:00:01,400 --> 01:00:02,500\r\nأهلًا\r\n",
            shifted,
        )
    }

    @Test
    fun linesPulledBeforeTheStartBeginAtZero() {
        val srt = "1\n00:00:01,000 --> 00:00:04,000\nHi\n"
        assertEquals("1\n00:00:00,000 --> 00:00:02,000\nHi\n", SubtitleTimeShift.shift(srt, -2_000))
    }

    @Test
    fun webVttKeepsItsShortTimesAndCueSettings() {
        val vtt = "WEBVTT\n\n00:58.500 --> 01:02.000 align:start position:10%\nHi\n\n59:59.000 --> 01:00:00.500\nBye\n"
        assertEquals(
            "WEBVTT\n\n01:00.000 --> 01:03.500 align:start position:10%\nHi\n\n01:00:00.500 --> 01:00:02.000\nBye\n",
            SubtitleTimeShift.shift(vtt, 1_500),
        )
    }

    @Test
    fun assDialogueTimesMoveInHundredths() {
        val ass = "[Script Info]\nTitle: x\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: 0,0:00:01.00,0:00:03.50,Default,,0,0,0,,{\\an8}Hi, there\n" +
            "Comment: 0,0:59:59.95,1:00:00.00,Default,,0,0,0,,note\n"
        assertEquals(
            "[Script Info]\nTitle: x\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
                "Dialogue: 0,0:00:00.75,0:00:03.25,Default,,0,0,0,,{\\an8}Hi, there\n" +
                "Comment: 0,0:59:59.70,0:59:59.75,Default,,0,0,0,,note\n",
            SubtitleTimeShift.shift(ass, -250),
        )
    }

    @Test
    fun aFileWithoutTimesIsNotSavedAsSynced() {
        assertNull(SubtitleTimeShift.shift("{100}{200}Frame based\n", 1_000))
        assertNull(SubtitleTimeShift.shift("just text", 1_000))
    }

    @Test
    fun noDelayGivesTheSameText() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nHi\n"
        assertEquals(srt, SubtitleTimeShift.shift(srt, 0))
    }
}
