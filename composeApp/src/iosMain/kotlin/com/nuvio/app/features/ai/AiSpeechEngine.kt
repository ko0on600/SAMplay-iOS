package com.nuvio.app.features.ai

import kotlin.concurrent.Volatile

/**
 * SAMplay iOS: speech to text from a video's audio ("Create with AI" / listening translation).
 * The native part (whisper.cpp with FFmpeg audio decoding) is installed from Swift through
 * [bridge]; without it the tools say listening is not available on this device.
 */
object AiSpeechEngine {
    class NoAudioException : Exception("no audio track")

    /** Implemented in Swift (iosApp): decodes the stream's audio and runs whisper.cpp on it. */
    interface Bridge {
        /** Whether this device can run it. */
        fun isSupported(): Boolean
        /**
         * Transcribes [url] (read with [headers]) with the ggml model at [modelPath]. Calls
         * [onSegment] with "start\tend\ttext" lines (ms) as they are made, [onProgress] 0..100,
         * and returns the detected language ("" when unknown); "!noaudio" when there is no audio.
         * [isCancelled] is polled; returning false from [onSegment] stops early.
         */
        fun transcribe(
            url: String,
            headers: Map<String, String>,
            modelPath: String,
            language: String,
            onProgress: (Int, Boolean) -> Unit,
            onSegment: (String, String) -> Boolean,
            isCancelled: () -> Boolean,
        ): String
        fun cancel()
    }

    @Volatile var bridge: Bridge? = null

    fun isAvailable(): Boolean = runCatching { bridge?.isSupported() == true }.getOrDefault(false)

    fun cancel() { runCatching { bridge?.cancel() } }

    /**
     * Runs the transcription. [onLines] gets the detected language and the new cues; it returns
     * false to stop. Returns false when stopped early.
     */
    suspend fun transcribe(
        url: String,
        headers: Map<String, String>,
        model: ModelManager.Model,
        spokenLang: String,
        isCancelled: () -> Boolean,
        onState: (AiSubtitleJobs.State) -> Unit,
        onLines: (String?, List<AiCue>) -> Boolean,
    ): Boolean {
        val engine = bridge ?: throw IllegalStateException("speech engine unavailable")
        val modelFile = ModelManager.ensure(com.nuvio.app.samplay.IosContext, model) { onState(AiSubtitleJobs.State.DownloadingModel(it)) }
        onState(AiSubtitleJobs.State.Extracting(0))
        var stopped = false
        var language: String? = if (spokenLang == "auto") null else spokenLang
        val pending = ArrayList<AiCue>()
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            engine.transcribe(
                url = url,
                headers = headers,
                modelPath = modelFile.path,
                language = spokenLang,
                onProgress = { pct, transcribing ->
                    onState(if (transcribing) AiSubtitleJobs.State.Transcribing(pct) else AiSubtitleJobs.State.Extracting(pct))
                },
                onSegment = { lang, line ->
                    if (language == null && lang.isNotBlank()) language = lang
                    // An empty line ends a chunk: its lines are shown together, as on Android.
                    if (line.isEmpty()) {
                        val go = if (pending.isEmpty()) true else onLines(language, pending.toList()).also { pending.clear() }
                        if (!go) stopped = true
                        go && !isCancelled()
                    } else {
                        val parts = line.split('\t', limit = 3)
                        if (parts.size == 3) {
                            val s = parts[0].toLongOrNull(); val e = parts[1].toLongOrNull()
                            if (s != null && e != null) pending += AiCue(s, e, parts[2])
                        }
                        !isCancelled()
                    }
                },
                isCancelled = isCancelled,
            )
        }
        if (result == "!noaudio") throw NoAudioException()
        if (pending.isNotEmpty() && !stopped) onLines(language, pending.toList())
        return !stopped
    }
}
