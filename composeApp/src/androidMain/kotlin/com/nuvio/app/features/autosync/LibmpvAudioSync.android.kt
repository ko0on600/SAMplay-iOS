@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.autosync

import android.content.Context
import androidx.media3.common.C
import androidx.media3.extractor.text.CuesWithTiming
import com.nuvio.app.core.diagnostics.Diag
import com.nuvio.app.features.player.PlatformPlaybackDataSourceFactory
import com.nuvio.app.features.player.audiosync.AudioSyncFallback
import com.nuvio.app.features.player.audiosync.AudioSyncSubtitleHost
import com.nuvio.app.features.player.parseSidecarTimedCuesRobust
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Nuvio RS (from SAMplay, where it runs on libVLC): the audio sync on the libmpv engine.
 *
 * mpv gives no PCM tap, so the audio is read from the stream a second time
 * ([AudioSyncFallback] with no ExoPlayer, through its source reader). mpv also draws the subtitle
 * itself, so every correction is written as a file and loaded in place of the track
 * ([MpvAudioSyncSubtitles]). The engine and its log lines are the ones the ExoPlayer path uses.
 * Nothing is created until a subtitle is synced, so a stream without subtitles costs nothing.
 */
internal class LibmpvAudioSync(
    private val context: Context,
    private val scope: CoroutineScope,
    private val sourceUrl: String,
    private val sourceAudioUrl: String?,
    private val sourceHeaders: Map<String, String>,
    private val positionMs: () -> Long,
    private val durationMs: () -> Long,
    private val getSubtitleHeaders: (String) -> Map<String, String>,
    writeSubtitleFile: suspend (fileName: String, stalePrefix: String, content: String) -> String?,
) : AutoSyncMpvAudioSync {
    override var onSubtitleReplaced: ((url: String) -> Unit)? = null
    private val subtitles = MpvAudioSyncSubtitles(scope, writeSubtitleFile)
    private var fallback: AudioSyncFallback? = null
    private var candidates: List<Triple<String, String, String?>> = emptyList()
    private var content: Pair<String, String>? = null
    private var released = false

    private fun engine(): AudioSyncFallback? {
        if (released) return null
        fallback?.let { return it }
        return runCatching {
            AudioSyncFallback(
                context = context,
                scope = scope,
                player = null,
                sidecar = subtitles,
                sourceUrl = sourceUrl,
                sourceAudioUrl = sourceAudioUrl,
                dataSourceFactory = PlatformPlaybackDataSourceFactory.create(
                    context = context,
                    defaultRequestHeaders = sourceHeaders,
                    defaultResponseHeaders = emptyMap(),
                    useYoutubeChunkedPlayback = false,
                    playUrl = sourceUrl,
                ),
                positionMs = positionMs,
                durationMs = durationMs,
                getSubtitleHeaders = getSubtitleHeaders,
                onSubtitleReplaced = { url -> onSubtitleReplaced?.invoke(url) },
            ).also { created ->
                created.setCandidates(candidates)
                content?.let { (type, videoId) -> created.setContent(type, videoId) }
            }
        }.onFailure { Diag.log("autosync", "audio sync unavailable on mpv: $it") }
            .getOrNull()
            .also { fallback = it }
    }

    override val isActive: Boolean get() = fallback?.isActive == true

    override fun setCandidates(candidates: List<AutoSyncSubtitleCandidate>) {
        this.candidates = candidates.map { Triple(it.url, it.language, it.name) }
        fallback?.setCandidates(this.candidates)
    }

    override fun setContent(type: String, videoId: String) {
        content = type to videoId
        fallback?.setContent(type, videoId)
    }

    override fun arm() {
        subtitles.clear()
        engine()?.arm()
    }

    override fun disarm() {
        subtitles.clear()
        fallback?.disarm()
    }

    override suspend fun takeOver(
        url: String,
        body: String,
        loadedUrl: String,
        replace: (replacedUrl: String, path: String) -> Unit,
    ): Boolean {
        val engine = engine() ?: return false
        val cues = parse(body, url)
        if (cues.isEmpty()) {
            Diag.log("autosync", "audio takeover dropped: the subtitle has no readable lines")
            engine.disarm()
            return false
        }
        subtitles.show(url, loadedUrl, cues, replace)
        return engine.takeOver(url).also { taken -> if (!taken) subtitles.clear() }
    }

    override suspend fun watchForDrift(
        url: String,
        shownBody: String,
        loadedUrl: String,
        replace: (replacedUrl: String, path: String) -> Unit,
    ): Boolean {
        val engine = engine() ?: return false
        val cues = parse(shownBody, url)
        if (cues.isEmpty()) return false
        // AutoSync's own copy is already loaded: the first file is written only on a correction.
        subtitles.show(url, loadedUrl, cues, replace)
        return engine.watchForDrift(url).also { watching -> if (!watching) subtitles.clear() }
    }

    override fun stop() {
        // Forget the subtitle first: a stop that follows a subtitle change must not load anything.
        subtitles.clear()
        fallback?.stop()
    }

    override fun freeze() {
        fallback?.freeze()
        subtitles.clear()
    }

    override fun release() {
        released = true
        subtitles.clear()
        fallback?.release()
        fallback = null
    }

    private suspend fun parse(body: String, url: String): List<CuesWithTiming> =
        withContext(Dispatchers.Default) {
            runCatching { parseSidecarTimedCuesRobust(body, url).cues }.getOrDefault(emptyList())
        }
}

/**
 * The subtitle libmpv shows, as the audio sync sees it. A commit writes the cues as SubRip and
 * has mpv load that file in place of the track loaded from the original link, keeping its label.
 */
internal class MpvAudioSyncSubtitles(
    private val scope: CoroutineScope,
    private val writeSubtitleFile: suspend (fileName: String, stalePrefix: String, content: String) -> String?,
) : AudioSyncSubtitleHost {
    private var generationCounter = 0L
    private var generation = 0L
    private var loadedUrl: String? = null
    private var replace: ((replacedUrl: String, path: String) -> Unit)? = null
    private var writeJob: Job? = null

    override var activeSidecarSubtitleKey: String? = null
        private set
    override var sidecarTimedCues: List<CuesWithTiming> = emptyList()
        private set

    /** mpv shows [url] (loaded from [loadedUrl]) with [cues]. */
    fun show(
        url: String,
        loadedUrl: String,
        cues: List<CuesWithTiming>,
        replace: (replacedUrl: String, path: String) -> Unit,
    ) {
        writeJob?.cancel()
        activeSidecarSubtitleKey = url
        this.loadedUrl = loadedUrl
        this.replace = replace
        sidecarTimedCues = cues
        generation = ++generationCounter
    }

    fun clear() {
        writeJob?.cancel()
        writeJob = null
        activeSidecarSubtitleKey = null
        loadedUrl = null
        replace = null
        sidecarTimedCues = emptyList()
        generation = ++generationCounter
    }

    override fun currentGenerationFor(url: String): Long? =
        generation.takeIf { activeSidecarSubtitleKey == url && it != 0L }

    override fun commitPreparedSidecarSubtitle(
        expectedCurrentUrl: String,
        newUrl: String,
        cues: List<CuesWithTiming>,
        expectedGeneration: Long?,
    ): Boolean {
        if (cues.isEmpty() || activeSidecarSubtitleKey != expectedCurrentUrl) return false
        if (expectedGeneration != null && generation != expectedGeneration) return false
        val replaceTrack = replace ?: return false
        val replacedUrl = loadedUrl ?: return false
        if (newUrl != expectedCurrentUrl) {
            activeSidecarSubtitleKey = newUrl
            generation = ++generationCounter
        }
        sidecarTimedCues = cues
        val committed = generation
        writeJob?.cancel()
        writeJob = scope.launch {
            val text = withContext(Dispatchers.Default) { toSubRip(cues) }
            if (text.isBlank()) return@launch
            val prefix = "$LIBMPV_AUTOSYNC_FILE_PREFIX${newUrl.hashCode().toUInt().toString(16)}_"
            val path = writeSubtitleFile("${prefix}a${text.hashCode().toUInt().toString(16)}.srt", prefix, text)
            if (path == null) {
                Diag.log("autosync", "audio result not applied: the subtitle file could not be written")
                return@launch
            }
            // Another correction or another subtitle came meanwhile: that one is loaded instead.
            if (generation != committed || activeSidecarSubtitleKey != newUrl) return@launch
            replaceTrack(replacedUrl, path)
        }
        return true
    }

    companion object {
        /** [cues] as SubRip text; lines without a start time are left out. */
        internal fun toSubRip(cues: List<CuesWithTiming>): String {
            val out = StringBuilder()
            var index = 0
            cues.asSequence()
                .filter { it.startTimeUs != C.TIME_UNSET }
                .sortedBy { it.startTimeUs }
                .forEach { entry ->
                    val text = entry.cues
                        .mapNotNull { cue -> cue.text?.toString() }
                        .flatMap { it.split('\n') }
                        .map { it.trimEnd('\r') }
                        .filter { it.isNotBlank() }
                        .joinToString("\n")
                    if (text.isEmpty()) return@forEach
                    val startMs = (entry.startTimeUs / 1_000L).coerceAtLeast(0L)
                    val endMs = when {
                        entry.durationUs != C.TIME_UNSET -> (entry.startTimeUs + entry.durationUs) / 1_000L
                        entry.endTimeUs != C.TIME_UNSET -> entry.endTimeUs / 1_000L
                        else -> startMs + DEFAULT_LINE_MS
                    }.coerceAtLeast(startMs + 1L)
                    index++
                    out.append(index).append('\n')
                        .append(timestamp(startMs)).append(" --> ").append(timestamp(endMs)).append('\n')
                        .append(text).append("\n\n")
                }
            return out.toString()
        }

        private fun timestamp(ms: Long): String {
            val hours = ms / 3_600_000L
            val minutes = ms / 60_000L % 60L
            val seconds = ms / 1_000L % 60L
            val millis = ms % 1_000L
            return "%02d:%02d:%02d,%03d".format(java.util.Locale.ROOT, hours, minutes, seconds, millis)
        }

        private const val DEFAULT_LINE_MS = 3_000L
    }
}
