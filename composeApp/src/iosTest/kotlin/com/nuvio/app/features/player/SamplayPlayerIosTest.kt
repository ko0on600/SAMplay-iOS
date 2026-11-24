package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SAMplay iOS: subtitle file decoding and the OpenSubtitles hash. */
class SamplayPlayerIosTest {
    @Test
    fun utf8SubtitleIsKept() {
        val bytes = "1\n00:00:01,000 --> 00:00:02,000\nمرحبا\n".encodeToByteArray()
        val text = subtitleText(bytes)
        assertTrue(text.text.contains("مرحبا"))
        assertNull(text.converted)
    }

    @Test
    fun windows1256ArabicIsConverted() {
        // "مرحبا بكم" in Windows-1256.
        val arabic = byteArrayOf(0xE3.toByte(), 0xD1.toByte(), 0xCD.toByte(), 0xC8.toByte(), 0xC7.toByte(), 0x20, 0xC8.toByte(), 0xDF.toByte(), 0xE3.toByte())
        val head = "1\n00:00:01,000 --> 00:00:02,000\n".encodeToByteArray()
        val body = ArrayList<Byte>()
        repeat(8) { body.addAll(head.toList()); body.addAll(arabic.toList()); body.addAll("\n\n".encodeToByteArray().toList()) }
        val text = subtitleText(body.toByteArray())
        assertTrue(text.text.contains("مرحبا بكم"), text.text)
        assertEquals(text.text, text.converted?.decodeToString())
    }

    @Test
    fun localPaths() {
        assertEquals("/var/a b.srt", localPathOf("file:///var/a%20b.srt"))
        assertEquals("/tmp/x.srt", localPathOf("/tmp/x.srt"))
        assertNull(localPathOf("https://example.com/x.srt"))
    }

    @Test
    fun openSubtitlesHashMatchesTheReference() {
        // All-zero chunks: the hash is the size itself.
        val zero = ByteArray(OpenSubtitlesHash.CHUNK)
        assertEquals("0000000000020000", OpenSubtitlesHash.hex(131_072, zero, zero))
        // One little-endian long of 1 in each chunk adds 2.
        val one = ByteArray(OpenSubtitlesHash.CHUNK).also { it[0] = 1 }
        assertEquals("0000000000020002", OpenSubtitlesHash.hex(131_072, one, one))
        // The sum wraps like a 64-bit unsigned value.
        val max = ByteArray(OpenSubtitlesHash.CHUNK).also { for (i in 0 until 8) it[i] = 0xFF.toByte() }
        assertEquals("000000000001fffe", OpenSubtitlesHash.hex(131_072, max, max))
    }
}
