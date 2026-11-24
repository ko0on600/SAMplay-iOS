@file:androidx.annotation.OptIn(UnstableApi::class)

package com.nuvio.app.features.player

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * SAMplay: surround audio (5.1 / 7.1) mixed down to stereo inside the app (from SAMplay 1.9.1).
 *
 * In a film's 5.1 mix the dialogue is in the centre channel. Handed over as 5.1, the device mixes it
 * down itself, and many phones, TV boxes on a stereo TV and some Bluetooth paths just keep the front
 * left/right channels: music and effects stay, the voices are gone ("only music"). Mixed here, the
 * centre goes into both speakers at the level of the fronts, so the dialogue is never lost. Nuvio's
 * own FFmpeg downmix covers Bluetooth only, and only when FFmpeg decodes the audio.
 *
 * It is on whenever the sound goes to a stereo output; an HDMI / USB receiver that takes more than
 * two channels keeps the original layout.
 */
internal object StereoDownmix {
    private const val TAG = "SamplayDownmix"

    /**
     * True when media plays on an HDMI / USB / digital output that reports more than two PCM
     * channels (a receiver or a TV that really takes 5.1). Built-in speakers, headphones and
     * Bluetooth are stereo; an output that reports nothing is treated as stereo too.
     */
    fun outputTakesSurround(context: Context): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val outputs = runCatching { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }
            .getOrDefault(emptyList())
        // Headphones and Bluetooth win over the HDMI port: Android routes media to them.
        if (outputs.any { it.type in STEREO_OUTPUTS }) return false
        return outputs.any { device -> device.type in SURROUND_OUTPUTS && device.channelCounts.any { it > 2 } }
    }

    /** Linear centre gain for the audio panel's level; 0 dB keeps the centre as loud as the fronts. */
    fun centerGain(centerMixLevelDb: Int): Float =
        10f.pow(centerMixLevelDb.coerceIn(CENTER_MIX_LEVEL_MIN_DB, CENTER_MIX_LEVEL_MAX_DB) / 20f)

    /**
     * Mix of [channels] (Android / WAVE order) into stereo: L coefficients then R coefficients, or
     * null for a layout that is left alone. Fronts and centre at the same level (the dialogue keeps
     * its place against music and effects), surrounds a little lower, a touch of LFE.
     */
    fun matrix(channels: Int, centerGain: Float = 1f): FloatArray? {
        val f = 0.5f
        val c = 0.5f * centerGain
        val s = 0.35f
        val lfe = 0.25f
        return when (channels) {
            // FL FR FC
            3 -> floatArrayOf(0.6f, 0f, c, /* R */ 0f, 0.6f, c)
            // FL FR BL BR (quad)
            4 -> floatArrayOf(0.6f, 0f, 0.4f, 0f, /* R */ 0f, 0.6f, 0f, 0.4f)
            // FL FR FC BL BR
            5 -> floatArrayOf(f, 0f, c, s, 0f, /* R */ 0f, f, c, 0f, s)
            // FL FR FC LFE BL BR (5.1)
            6 -> floatArrayOf(f, 0f, c, lfe, s, 0f, /* R */ 0f, f, c, lfe, 0f, s)
            // FL FR FC LFE BC SL SR (6.1)
            7 -> floatArrayOf(f, 0f, c, lfe, 0.25f, s, 0f, /* R */ 0f, f, c, lfe, 0.25f, 0f, s)
            // FL FR FC LFE BL BR SL SR (7.1)
            8 -> floatArrayOf(f, 0f, c, lfe, 0.3f, 0f, 0.3f, 0f, /* R */ 0f, f, c, lfe, 0f, 0.3f, 0f, 0.3f)
            else -> null
        }
    }

    internal fun log(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    private val SURROUND_OUTPUTS = setOf(
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        29, // TYPE_HDMI_EARC
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
    )
    private val STEREO_OUTPUTS = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        26, // TYPE_BLE_HEADSET
        27, // TYPE_BLE_SPEAKER
    )
}

/**
 * The Media3 audio processor doing [StereoDownmix]'s mix. Stereo, mono and encoded audio pass
 * through. The centre level follows [centerMixLevelDbProvider] (the audio panel) buffer by buffer.
 */
internal class StereoDownmixAudioProcessor(
    private val enabled: () -> Boolean,
    private val centerMixLevelDbProvider: () -> Int,
) : BaseAudioProcessor() {

    /** Whether surround is being mixed to stereo now, so the audio panel can show the centre level. */
    @Volatile
    var isMixing: Boolean = false
        private set

    private var mix: FloatArray? = null
    private var mixCenterDb = Int.MIN_VALUE
    private var frame = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        isMixing = false
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) return AudioProcessor.AudioFormat.NOT_SET
        if (inputAudioFormat.channelCount <= 2) return AudioProcessor.AudioFormat.NOT_SET
        if (StereoDownmix.matrix(inputAudioFormat.channelCount) == null) return AudioProcessor.AudioFormat.NOT_SET
        val on = runCatching(enabled).getOrDefault(true)
        StereoDownmix.log("${inputAudioFormat.channelCount} ch → ${if (on) "stereo (in-app mix)" else "original channels"}")
        if (!on) return AudioProcessor.AudioFormat.NOT_SET
        isMixing = true
        return AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, 2, encoding)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val channels = inputAudioFormat.channelCount
        val centerDb = runCatching(centerMixLevelDbProvider).getOrDefault(0)
        if (mix == null || centerDb != mixCenterDb) {
            mix = StereoDownmix.matrix(channels, StereoDownmix.centerGain(centerDb))
            mixCenterDb = centerDb
        }
        val m = mix ?: return
        if (frame.size != channels) frame = FloatArray(channels)
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val frames = input.remaining() / inputAudioFormat.bytesPerFrame
        val out = replaceOutputBuffer(frames * outputAudioFormat.bytesPerFrame).order(ByteOrder.nativeOrder())
        repeat(frames) {
            if (float) {
                for (ch in 0 until channels) frame[ch] = input.float
            } else {
                for (ch in 0 until channels) frame[ch] = input.short / 32768f
            }
            var left = 0f
            var right = 0f
            for (ch in 0 until channels) {
                left += m[ch] * frame[ch]
                right += m[channels + ch] * frame[ch]
            }
            left = left.coerceIn(-1f, 1f)
            right = right.coerceIn(-1f, 1f)
            if (float) {
                out.putFloat(left)
                out.putFloat(right)
            } else {
                out.putShort((left * 32767f).toInt().toShort())
                out.putShort((right * 32767f).toInt().toShort())
            }
        }
        input.position(input.limit())
        out.flip()
    }

    override fun onReset() {
        mix = null
        mixCenterDb = Int.MIN_VALUE
        isMixing = false
    }
}
