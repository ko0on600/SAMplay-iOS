package com.nuvio.app.features.servers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkRetryTest {
    @Test
    fun waitsGrowAndTheBudgetEndsAfterTenTries() {
        val retry = LinkRetry()
        val waits = (0 until 10).map { retry.next("video", nowMs = it * 1_000L) }
        assertEquals(LinkRetry.WAITS_MS.toList(), waits)
        assertEquals(10, retry.attempt)
        assertNull(retry.next("video", nowMs = 11_000L))
    }

    @Test
    fun anotherVideoOrALongCalmStartsANewBudget() {
        val retry = LinkRetry(waitsMs = longArrayOf(1, 2), calmMs = 100)
        assertEquals(1L, retry.next("a", 0))
        assertEquals(2L, retry.next("a", 10))
        assertNull(retry.next("a", 20))
        assertEquals(1L, retry.next("b", 30))
        assertEquals(2L, retry.next("b", 40))
        // The link played for longer than calmMs, then failed again.
        assertEquals(1L, retry.next("b", 200))
    }

    @Test
    fun theHttpStatusIsReadFromThePlayerError() {
        assertEquals(404, LinkRetry.httpStatusOf("HTTP 404 Not Found [ERROR_CODE_IO_BAD_HTTP_STATUS]"))
        assertEquals(503, LinkRetry.httpStatusOf("HTTP 503"))
        assertNull(LinkRetry.httpStatusOf("Decoder failed [ERROR_CODE_DECODING_FAILED]"))
        assertNull(LinkRetry.httpStatusOf(null))
    }

    @Test
    fun decoderErrorsAreToldApartFromLinkErrors() {
        assertTrue(
            LinkRetry.isDecoderError(
                "MediaCodecVideoRenderer error, index=1, format=Format(1, Dolby Vision P8 MEL, video/x-matroska, " +
                    "video/dolby-vision, dvhe.08.03, -1, und, [1920, 1038, -1.0, null], [-1, -1]), " +
                    "format_supported=NO_EXCEEDS_CAPABILITIES [ERROR_CODE_DECODING_FAILED]",
            ),
        )
        assertTrue(LinkRetry.isDecoderError("Decoder init failed: c2.android.hevc.decoder [ERROR_CODE_DECODER_INIT_FAILED]"))
        assertTrue(LinkRetry.isDecoderError("ERROR [ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES]"))
        assertFalse(LinkRetry.isDecoderError("HTTP 404 Not Found [ERROR_CODE_IO_BAD_HTTP_STATUS]"))
        assertFalse(LinkRetry.isDecoderError("Source error [ERROR_CODE_IO_UNSPECIFIED]"))
        assertFalse(LinkRetry.isDecoderError(null))
    }
}
