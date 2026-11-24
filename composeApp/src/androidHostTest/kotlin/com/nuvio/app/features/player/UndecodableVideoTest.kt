package com.nuvio.app.features.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UndecodableVideoTest {
    private fun group(mime: String, support: Int, codecs: String? = null): Tracks.Group {
        val format = Format.Builder().setSampleMimeType(mime).setCodecs(codecs).build()
        return Tracks.Group(TrackGroup(format), false, intArrayOf(support), booleanArrayOf(false))
    }

    private val ac3 get() = group(MimeTypes.AUDIO_AC3, C.FORMAT_HANDLED)

    @Test
    fun videoWithNoDecoderIsReported() {
        // MPEG-2 on a phone: the sound plays, the picture has no decoder.
        val tracks = Tracks(listOf(group(MimeTypes.VIDEO_MPEG2, C.FORMAT_UNSUPPORTED_SUBTYPE), ac3))
        assertEquals("video/mpeg2", tracks.undecodableVideoFormat())
        val hevc = Tracks(listOf(group(MimeTypes.VIDEO_H265, C.FORMAT_UNSUPPORTED_TYPE, "hvc1.2.4.L153"), ac3))
        assertEquals("video/hevc hvc1.2.4.L153", hevc.undecodableVideoFormat())
    }

    @Test
    fun playableVideoIsNotReported() {
        assertNull(Tracks(listOf(group(MimeTypes.VIDEO_H264, C.FORMAT_HANDLED), ac3)).undecodableVideoFormat())
        // Past the decoder's stated limits (a 4K file on a 1080p decoder): ExoPlayer still tries it.
        assertNull(
            Tracks(listOf(group(MimeTypes.VIDEO_H265, C.FORMAT_EXCEEDS_CAPABILITIES), ac3)).undecodableVideoFormat(),
        )
        // One picture that plays is enough.
        val twoAngles = Tracks(
            listOf(
                group(MimeTypes.VIDEO_MPEG2, C.FORMAT_UNSUPPORTED_SUBTYPE),
                group(MimeTypes.VIDEO_H264, C.FORMAT_HANDLED),
                ac3,
            ),
        )
        assertNull(twoAngles.undecodableVideoFormat())
    }

    @Test
    fun soundOnlyOrNothingYetIsNotReported() {
        assertNull(Tracks(listOf(ac3)).undecodableVideoFormat())
        assertNull(Tracks.EMPTY.undecodableVideoFormat())
    }
}
