@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SAMplay: surround mixed to stereo keeps the centre channel, where a film's dialogue is. */
class StereoDownmixTest {

    private var centerDb = 0

    private fun processor(enabled: Boolean, channels: Int, encoding: Int = C.ENCODING_PCM_16BIT): StereoDownmixAudioProcessor {
        val processor = StereoDownmixAudioProcessor(enabled = { enabled }, centerMixLevelDbProvider = { centerDb })
        processor.configure(AudioProcessor.AudioFormat(48_000, channels, encoding))
        processor.flush()
        return processor
    }

    private fun pcm16(frames: Int, channels: Int, sample: (channel: Int) -> Short): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(frames * channels * 2).order(ByteOrder.nativeOrder())
        repeat(frames) { for (channel in 0 until channels) buffer.putShort(sample(channel)) }
        buffer.flip()
        return buffer
    }

    private fun stereoOut(processor: StereoDownmixAudioProcessor): List<Pair<Int, Int>> {
        val out = processor.output.order(ByteOrder.nativeOrder())
        val pairs = ArrayList<Pair<Int, Int>>()
        while (out.remaining() >= 4) pairs += out.short.toInt() to out.short.toInt()
        return pairs
    }

    @Test
    fun `dialogue in the centre reaches both speakers`() {
        val processor = processor(enabled = true, channels = 6)
        assertTrue(processor.isActive)
        assertTrue(processor.isMixing)
        // 5.1: FL FR FC LFE BL BR, only the centre (dialogue) carries sound.
        processor.queueInput(pcm16(4, 6) { channel -> if (channel == 2) 16_000 else 0 })
        val out = stereoOut(processor)
        assertEquals(4, out.size)
        for ((left, right) in out) {
            assertEquals(8_000.0, left.toDouble(), 2.0)
            assertEquals(8_000.0, right.toDouble(), 2.0)
        }
    }

    @Test
    fun `front channels stay on their side`() {
        val processor = processor(enabled = true, channels = 6)
        processor.queueInput(pcm16(2, 6) { channel -> if (channel == 0) 20_000 else 0 })
        for ((left, right) in stereoOut(processor)) {
            assertEquals(10_000.0, left.toDouble(), 2.0)
            assertEquals(0, right)
        }
    }

    @Test
    fun `the audio panel's dialogue level raises the centre`() {
        centerDb = 6
        val processor = processor(enabled = true, channels = 6)
        processor.queueInput(pcm16(1, 6) { channel -> if (channel == 2) 8_000 else 0 })
        val (left, right) = stereoOut(processor).single()
        // +6 dB is about twice as loud.
        assertEquals(7_981.0, left.toDouble(), 30.0)
        assertEquals(7_981.0, right.toDouble(), 30.0)

        centerDb = 0
        processor.queueInput(pcm16(1, 6) { channel -> if (channel == 2) 8_000 else 0 })
        val (left0, _) = stereoOut(processor).single()
        assertEquals(4_000.0, left0.toDouble(), 2.0)
    }

    @Test
    fun `a loud mix is clipped, not wrapped`() {
        val processor = processor(enabled = true, channels = 6)
        processor.queueInput(pcm16(1, 6) { Short.MAX_VALUE })
        val (left, right) = stereoOut(processor).single()
        assertEquals(Short.MAX_VALUE.toInt(), left)
        assertEquals(Short.MAX_VALUE.toInt(), right)
    }

    @Test
    fun `float audio is mixed too`() {
        val processor = processor(enabled = true, channels = 8, encoding = C.ENCODING_PCM_FLOAT)
        val buffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder())
        for (channel in 0 until 8) buffer.putFloat(if (channel == 2) 0.5f else 0f)
        buffer.flip()
        processor.queueInput(buffer)
        val out = processor.output.order(ByteOrder.nativeOrder())
        assertEquals(8, out.remaining())
        assertEquals(0.25f, out.float, 0.001f)
        assertEquals(0.25f, out.float, 0.001f)
    }

    @Test
    fun `stereo, mono, encoded audio and surround outputs are left alone`() {
        assertFalse(processor(enabled = true, channels = 2).isActive)
        assertFalse(processor(enabled = true, channels = 1).isActive)
        assertFalse(processor(enabled = true, channels = 6, encoding = C.ENCODING_AC3).isActive)
        val receiver = processor(enabled = false, channels = 6)
        assertFalse(receiver.isActive)
        assertFalse(receiver.isMixing)
    }

    @Test
    fun `every layout up to 7_1 has a mix and others are left alone`() {
        for (channels in 3..8) {
            val mix = StereoDownmix.matrix(channels)
            assertEquals(channels * 2, mix?.size)
            // Every channel reaches at least one speaker.
            for (channel in 0 until channels) assertTrue(mix!![channel] + mix[channels + channel] > 0f)
        }
        assertNull(StereoDownmix.matrix(2))
        assertNull(StereoDownmix.matrix(10))
    }
}
