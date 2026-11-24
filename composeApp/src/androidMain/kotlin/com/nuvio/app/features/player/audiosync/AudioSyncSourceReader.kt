@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player.audiosync

import android.media.MediaFormat
import android.net.Uri
import android.os.Process
import androidx.media3.common.Format
import androidx.media3.datasource.DataSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.exoplayer.MediaExtractorCompat
import com.nuvio.app.core.diagnostics.Diag

/**
 * SAMplay TV 1.0.2: the audio the sync listens to, for an engine that is not ExoPlayer (libVLC).
 *
 * With ExoPlayer the sync copies the audio while the player demuxes it and hears the player's own
 * output. libVLC gives neither, so the audio sync never ran on it (and a TV mostly plays through
 * libVLC). This reader opens the same stream a second time, with the same headers, and reads the
 * audio from the playhead to [AHEAD_US] ahead of it, following the playhead after every seek. The
 * samples go through the very same decoder, speech detection and aligner as on ExoPlayer, so both
 * engines compute the same offset for the same video and subtitle.
 *
 * The extra connection reads at the stream's bitrate, and only while the sync wants audio: nothing
 * before a subtitle is being synced, and one minute in three once the sync is confirmed.
 */
internal class AudioSyncSourceReader(
    private val uri: Uri,
    private val dataSourceFactory: DataSource.Factory,
    private val controller: AudioSubtitleSyncController,
    /** The playhead in ms, published by the main thread. */
    private val positionMs: () -> Long,
) {
    @Volatile
    private var running = false
    /**
     * Bumped by every [start] and [stop]: a reading thread works only while the generation it was
     * started with is current, so a quick stop + start never leaves two threads reading (or lets
     * the old one end the new one).
     */
    @Volatile
    private var generation = 0
    private var thread: Thread? = null

    /** Why reading stopped for good on this stream, for the diagnostics log; null while it works. */
    @Volatile
    var failure: String? = null
        private set

    @Volatile
    private var pickedFormat: Format? = null

    val isRunning: Boolean get() = running

    @Synchronized
    fun start() {
        if (running || failure != null) return
        running = true
        val gen = ++generation
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runCatching { loop(gen) }.onFailure { error ->
                if (alive(gen)) {
                    failure = error.message ?: error.javaClass.simpleName
                    Diag.log("autosync", "audio reader stopped: $failure")
                }
            }
            synchronized(this) { if (generation == gen) running = false }
        }, "SamAudioSyncReader").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        generation++
        thread?.interrupt()
        thread = null
    }

    private fun alive(gen: Int): Boolean = running && generation == gen && !Thread.currentThread().isInterrupted

    private val sink = object : AudioSampleSink {
        override fun wantsSamples(format: Format): Boolean {
            // The first audio track of the file stands for the one libVLC plays (it does not say
            // which it picked in a way that maps onto the container's tracks). Dubs keep the
            // original's timing, so the speech sits at the same places either way.
            val picked = pickedFormat ?: format.also {
                pickedFormat = it
                controller.onAudioTrackSelected(it)
                Diag.log("autosync", "audio reader: track ${it.sampleMimeType} ${it.channelCount}ch ${it.sampleRate}Hz lang=${it.language}")
            }
            return picked === format || (picked.id == format.id && picked.sampleMimeType == format.sampleMimeType)
        }

        override fun onSample(format: Format, timeUs: Long, data: ByteArray, offset: Int, size: Int) {
            controller.onSourceSample(format, timeUs, data, offset, size)
        }

        override fun onDiscontinuity() = controller.onDiscontinuity()
    }

    private fun loop(gen: Int) {
        var attempts = 0
        while (alive(gen)) {
            val problem = readOnce(gen)
            if (!alive(gen)) return
            if (problem == null) return
            attempts++
            if (attempts >= MAX_ATTEMPTS || problem.permanent) {
                failure = problem.reason
                Diag.log("autosync", "audio reader gave up: ${problem.reason}")
                return
            }
            Diag.log("autosync", "audio reader: ${problem.reason}; retry $attempts")
            sleep(RETRY_MS)
        }
    }

    private class Problem(val reason: String, val permanent: Boolean)

    /** Reads until stopped; returns what went wrong, or null when stopped normally. */
    private fun readOnce(gen: Int): Problem? {
        val extractor = MediaExtractorCompat(AudioSyncExtractorsFactory(DefaultExtractorsFactory(), sink), dataSourceFactory)
        try {
            extractor.setDataSource(uri, 0L)
            var audioTracks = 0
            for (index in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    extractor.selectTrack(index)
                    audioTracks++
                }
            }
            if (audioTracks == 0) return Problem("no audio track in the file", permanent = true)
            Diag.log("autosync", "audio reader: open, $audioTracks audio track(s)")
            var readerUs = -1L
            var announced = false
            while (alive(gen)) {
                val playheadUs = positionMs().coerceAtLeast(0L) * 1_000L
                if (!controller.isListening()) {
                    // Nothing is being synced: read nothing, and start at the playhead afterwards.
                    readerUs = -1L
                    sleep(IDLE_MS)
                    continue
                }
                val lost = readerUs < 0 || readerUs < playheadUs - BEHIND_US || readerUs > playheadUs + AHEAD_US + JUMP_US
                // Once the sync is confirmed only one minute in three is analysed: skip the rest.
                val wantUs = controller.nextWantedAudioUs(if (lost) playheadUs else readerUs)
                if (wantUs >= playheadUs + AHEAD_US) {
                    sleep(PACE_MS)
                    continue
                }
                if (lost || wantUs != readerUs) {
                    extractor.seekTo(wantUs, MediaExtractorCompat.SEEK_TO_PREVIOUS_SYNC)
                    val firstUs = extractor.sampleTime
                    if (firstUs < 0) {
                        // At or past the end: wait for a seek back.
                        readerUs = -1L
                        sleep(IDLE_MS)
                        continue
                    }
                    if (kotlin.math.abs(firstUs - wantUs) > MAX_SEEK_MISS_US) {
                        return Problem("the stream cannot seek (asked ${wantUs / 1_000_000}s, got ${firstUs / 1_000_000}s)", permanent = true)
                    }
                    // A key frame before the asked time is fine; never report a time before it, or
                    // the next round would seek again for nothing.
                    readerUs = maxOf(firstUs, wantUs)
                    if (!announced) {
                        announced = true
                        Diag.log("autosync", "audio reader: reading from ${firstUs / 1_000_000}s")
                    }
                }
                if (readerUs >= playheadUs + AHEAD_US) {
                    sleep(PACE_MS)
                    continue
                }
                if (!extractor.advance()) {
                    readerUs = -1L
                    sleep(IDLE_MS)
                    continue
                }
                val next = extractor.sampleTime
                if (next >= 0) readerUs = next
            }
            return null
        } catch (_: InterruptedException) {
            return null
        } catch (error: Exception) {
            if (!alive(gen)) return null
            return Problem(error.message ?: error.javaClass.simpleName, permanent = false)
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** Interrupted by [stop]: the flag stays set, so [alive] ends this thread's loop (only this one). */
    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        /** How far ahead of the playhead the audio is read, as ExoPlayer's buffer would hold. */
        const val AHEAD_US = 75_000_000L
        const val BEHIND_US = 5_000_000L
        /** A playhead this far from the reader means the user seeked. */
        const val JUMP_US = 30_000_000L
        const val MAX_SEEK_MISS_US = 30_000_000L
        const val IDLE_MS = 500L
        const val PACE_MS = 250L
        const val RETRY_MS = 5_000L
        const val MAX_ATTEMPTS = 4
    }
}
