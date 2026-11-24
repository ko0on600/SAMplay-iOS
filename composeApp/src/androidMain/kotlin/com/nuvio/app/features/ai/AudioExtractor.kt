package com.nuvio.app.features.ai

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import java.nio.ByteOrder

/**
 * Decodes the first audio track of a video with MediaExtractor + MediaCodec and streams it out as
 * 16 kHz mono float PCM in chunks (split at the quietest point near the chunk end).
 */
class AudioExtractor(
    private val context: Context,
    private val uri: Uri,
    /** Nuvio RS: the stream's own request headers (debrid and add-on links often need them). */
    private val headers: Map<String, String> = emptyMap(),
) {

    class NoAudioException : Exception("no decodable audio track")

    var durationMs: Long = 0
        private set

    /**
     * @param onChunk called with (samples, startMs) for each chunk; return false to stop.
     */
    fun extract(
        chunkSeconds: Int = 90,
        isCancelled: () -> Boolean,
        onProgress: (Float) -> Unit,
        onChunk: (FloatArray, Long) -> Boolean,
    ) {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, headers.takeIf { it.isNotEmpty() })
        var track = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; format = f; break }
        }
        if (track < 0 || format == null) { extractor.release(); throw NoAudioException() }
        extractor.selectTrack(track)
        durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else 0
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val codec = try {
            MediaCodec.createDecoderByType(mime).also { it.configure(format, null, null, 0); it.start() }
        } catch (e: Exception) {
            extractor.release(); throw NoAudioException()
        }

        var inRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var floatPcm = false
        val resampler = Resampler(inRate, TARGET_RATE)
        val chunkLen = chunkSeconds * TARGET_RATE
        var buf = FloatArray(chunkLen + TARGET_RATE * 8)
        var bufLen = 0
        var chunkStartSample = 0L
        var stop = false

        fun emit(force: Boolean) {
            if (bufLen == 0) return
            if (!force && bufLen < chunkLen) return
            val cut = if (force) bufLen else quietestCut(buf, bufLen, chunkLen)
            val out = buf.copyOf(cut)
            val startMs = chunkStartSample * 1000 / TARGET_RATE
            if (!onChunk(out, startMs)) stop = true
            System.arraycopy(buf, cut, buf, 0, bufLen - cut)
            bufLen -= cut
            chunkStartSample += cut
        }

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone && !stop) {
                if (isCancelled()) break
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib = codec.getInputBuffer(ii)!!
                        val n = extractor.readSampleData(ib, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val t = extractor.sampleTime
                            codec.queueInputBuffer(ii, 0, n, t, 0)
                            extractor.advance()
                            if (durationMs > 0) onProgress((t / 1000f / durationMs).coerceIn(0f, 1f))
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        inRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = Build.VERSION.SDK_INT >= 24 && of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        resampler.setInputRate(inRate)
                    }
                    oi >= 0 -> {
                        val ob = codec.getOutputBuffer(oi)
                        if (ob != null && info.size > 0) {
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            val ordered = ob.order(ByteOrder.nativeOrder())
                            val frames: Int
                            val mono: FloatArray
                            if (floatPcm) {
                                val fb = ordered.asFloatBuffer()
                                frames = fb.remaining() / channels
                                mono = FloatArray(frames)
                                for (f in 0 until frames) {
                                    var s = 0f
                                    for (c in 0 until channels) s += fb.get()
                                    mono[f] = s / channels
                                }
                            } else {
                                val sb = ordered.asShortBuffer()
                                frames = sb.remaining() / channels
                                mono = FloatArray(frames)
                                for (f in 0 until frames) {
                                    var s = 0f
                                    for (c in 0 until channels) s += sb.get()
                                    mono[f] = s / channels / 32768f
                                }
                            }
                            resampler.process(mono, frames) { v ->
                                if (bufLen >= buf.size) buf = buf.copyOf(buf.size * 2)
                                buf[bufLen++] = v
                            }
                            emit(false)
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            if (!stop && !isCancelled()) emit(true)
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            extractor.release()
        }
    }

    /** Find the quietest 20 ms window in the last 6 s before [target] so words aren't cut. */
    private fun quietestCut(buf: FloatArray, len: Int, target: Int): Int {
        val win = TARGET_RATE / 50
        val from = (target - TARGET_RATE * 6).coerceAtLeast(win)
        val to = target.coerceAtMost(len - win)
        if (to <= from) return target.coerceAtMost(len)
        var best = to
        var bestE = Float.MAX_VALUE
        var i = from
        while (i < to) {
            var e = 0f
            for (k in i until i + win) e += buf[k] * buf[k]
            if (e < bestE) { bestE = e; best = i + win / 2 }
            i += win / 2
        }
        return best
    }

    /** Streaming box-filter resampler (anti-aliased decimation; sample-and-hold when upsampling). */
    class Resampler(inRate: Int, private val outRate: Int) {
        private var ratio = inRate.toDouble() / outRate
        private var acc = 0.0
        private var accN = 0
        private var pos = 0.0
        private var next = ratio

        fun setInputRate(r: Int) {
            ratio = r.toDouble() / outRate
            pos = 0.0; next = ratio; acc = 0.0; accN = 0
        }

        inline fun process(input: FloatArray, n: Int, out: (Float) -> Unit) {
            for (i in 0 until n) {
                feed(input[i])
                while (ready()) out(take())
            }
        }

        fun feed(s: Float) {
            acc += s; accN++; pos += 1.0
        }

        private var last = 0f
        fun ready(): Boolean = pos >= next

        fun take(): Float {
            if (accN > 0) { last = (acc / accN).toFloat(); acc = 0.0; accN = 0 }
            next += ratio
            return last
        }
    }

    companion object {
        const val TARGET_RATE = 16_000
    }
}
