package com.nuvio.app.features.ai

import com.nuvio.app.samplay.Charsets

import kotlin.concurrent.Volatile

import com.nuvio.app.samplay.Context
import com.nuvio.app.samplay.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nuvio.app.samplay.File

/**
 * The AI subtitle tools of SAMplay, for Nuvio: subtitles made from the video's own audio with
 * whisper.cpp ("Create with AI"), free on-device translation with ML Kit, and "Pro AI" translation
 * with Claude on the user's own key. A result is a SubRip file the player loads like any other
 * subtitle file ([Sink]); a running job keeps going if the player is closed.
 */
object AiSubtitleJobs {
    private const val TAG = "NuvioAi"

    sealed class State {
        object Idle : State()
        data class DownloadingModel(val pct: Int) : State()
        data class Extracting(val pct: Int) : State()
        data class Transcribing(val pct: Int) : State()
        object DownloadingLanguage : State()
        data class Translating(val pct: Int) : State()
        data class ProTranslating(val pct: Int) : State()
        data class Done(val lines: Int, val detected: String?, val pro: Boolean = false) : State()
        data class Error(val message: String, val kind: Kind = Kind.GENERIC, val pro: Boolean = false) : State()

        enum class Kind { GENERIC, CPU, NO_AUDIO, TRANSLATE_UNSUPPORTED, PRO_NOT_CONFIGURED, PRO_INVALID_KEY, PRO_REFUSED, NO_SUBTITLE }
    }

    /** Where a result goes: the player shows [file] as a subtitle named [label] in [language]. */
    fun interface Sink {
        /** [partial]: lines made so far while the rest of the video is still being processed. */
        fun show(file: File, label: String, language: String?, partial: Boolean)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null
    @Volatile private var cancelled = false

    val isRunning: Boolean get() = job?.isActive == true

    /** Whether "Create with AI" can run here: whisper.cpp needs a 64-bit ARMv8.2 CPU. */
    fun canGenerate(): Boolean = AiSpeechEngine.isAvailable()

    fun cancel() {
        cancelled = true
        AiSpeechEngine.cancel()
        job?.cancel()
        _state.value = State.Idle
    }

    fun clearResult() {
        if (!isRunning) _state.value = State.Idle
    }

    private fun folder(context: Context, videoKey: String): File =
        File(context.applicationContext.filesDir, "ai_subtitles/${videoKey.hashCode().toUInt().toString(16)}")
            .apply { mkdirs() }

    private suspend fun write(file: File, text: String): File = withContext(Dispatchers.IO) { writeNow(file, text) }

    private fun writeNow(file: File, text: String): File {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
        return file
    }

    /**
     * The generated subtitle as it stands with [count] lines. Each version gets its own name, so a
     * player that loaded an earlier one sees a new file; only the two newest are kept.
     */
    private fun generatedFile(folder: File, count: Int): File {
        folder.listFiles { f -> f.name.startsWith(GENERATED_PREFIX) && f.name.endsWith(".srt") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(1)
            ?.forEach { it.delete() }
        return File(folder, "$GENERATED_PREFIX$count.srt")
    }

    private const val GENERATED_PREFIX = "ai_generated_"

    private suspend fun deliver(sink: Sink, file: File, label: String, language: String?, partial: Boolean) {
        withContext(Dispatchers.Main) { runCatching { sink.show(file, label, language, partial) } }
    }

    /**
     * Subtitles from the video's audio with whisper.cpp ([url] read with [headers]), then, when
     * [translateTo] is set, translated with ML Kit. Lines show up while the rest is processed.
     */
    fun generate(
        context: Context,
        url: String,
        headers: Map<String, String>,
        videoKey: String,
        model: ModelManager.Model,
        spokenLang: String,
        translateTo: String?,
        labels: Labels,
        sink: Sink,
        /** SAMplay: the heard lines are translated by Pro AI instead of the free translation. */
        pro: Boolean = false,
        title: String? = null,
    ) {
        if (isRunning) return
        val app = context.applicationContext
        val previous = job
        job = scope.launch {
            previous?.join()
            cancelled = false
            if (pro && translateTo != null && !ProTranslate.isConfigured) {
                _state.value = State.Error("key", State.Kind.PRO_NOT_CONFIGURED, pro = true)
                return@launch
            }
            try {
                if (!canGenerate()) {
                    _state.value = State.Error("cpu", State.Kind.CPU)
                    return@launch
                }
                val cues = ArrayList<AiCue>()
                var detected: String? = if (spokenLang == "auto") null else spokenLang
                val outFolder = folder(app, videoKey)
                var published = 0
                _state.value = State.DownloadingModel(0)
                val ok = AiSpeechEngine.transcribe(
                    url = url,
                    headers = headers,
                    model = model,
                    spokenLang = spokenLang,
                    isCancelled = { cancelled },
                    onState = { _state.value = it },
                ) { lang, newCues ->
                    if (detected == null && !lang.isNullOrBlank()) detected = lang
                    newCues.forEach { c -> cleanWhisper(c.text).takeIf { it.isNotBlank() }?.let { cues += c.copy(text = it) } }
                    if (cues.size > published) {
                        published = cues.size
                        val l = detected
                        val file = runCatching { writeNow(generatedFile(outFolder, cues.size), AiSrt.toSrt(cues)) }.getOrNull()
                        if (file != null) scope.launch { deliver(sink, file, labels.generated(l), l, partial = true) }
                    }
                    !cancelled
                }
                if (cancelled || !ok && cues.isEmpty() && cancelled) return@launch
                if (cues.isEmpty()) {
                    _state.value = State.Error("no speech", State.Kind.NO_AUDIO)
                    return@launch
                }
                val file = write(generatedFile(outFolder, cues.size + 1), AiSrt.toSrt(cues))
                deliver(sink, file, labels.generated(detected), detected, partial = false)
                if (translateTo != null) {
                    if (pro) {
                        runPro(app, videoKey, cues, detected, translateTo, title, title ?: "ai_generated", labels, sink, heard = true)
                    } else {
                        translateInternal(app, videoKey, cues, detected, translateTo, title ?: "ai_generated", labels, sink, heard = true)
                    }
                } else {
                    _state.value = State.Done(cues.size, detected)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiSpeechEngine.NoAudioException) {
                _state.value = State.Error("no audio", State.Kind.NO_AUDIO)
            } catch (e: ProTranslate.NotConfigured) {
                _state.value = State.Error("key", State.Kind.PRO_NOT_CONFIGURED, pro = true)
            } catch (e: ProTranslate.InvalidKey) {
                _state.value = State.Error("key", State.Kind.PRO_INVALID_KEY, pro = true)
            } catch (e: ProTranslate.Refused) {
                _state.value = State.Error(e.category ?: "refusal", State.Kind.PRO_REFUSED, pro = true)
            } catch (e: Throwable) {
                Log.w(TAG, "AI subtitles failed", e)
                _state.value = State.Error(e.message ?: (e::class.simpleName ?: "error"))
            }
        }
    }

    /** Free on-device translation of [cues] (the subtitle shown now) into [target]. */
    fun translate(
        context: Context,
        videoKey: String,
        cues: List<AiCue>,
        sourceLang: String?,
        target: String,
        subtitleName: String?,
        labels: Labels,
        sink: Sink,
    ) {
        if (isRunning) return
        if (cues.isEmpty()) {
            _state.value = State.Error("subtitle", State.Kind.NO_SUBTITLE)
            return
        }
        val app = context.applicationContext
        val previous = job
        job = scope.launch {
            previous?.join()
            cancelled = false
            try {
                translateInternal(app, videoKey, cues, sourceLang, target, subtitleName, labels, sink)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "translation failed", e)
                _state.value = State.Error(e.message ?: (e::class.simpleName ?: "error"))
            }
        }
    }

    /**
     * "Pro AI": Claude translates [cues] in context, on the user's own key. The result is kept as
     * translate_<Language>_<subtitle>_Pro.srt for the video and loaded from there next time.
     */
    fun translatePro(
        context: Context,
        videoKey: String,
        cues: List<AiCue>,
        sourceLang: String?,
        target: String,
        title: String?,
        subtitleName: String?,
        labels: Labels,
        sink: Sink,
    ) {
        if (isRunning) return
        if (cues.isEmpty()) {
            _state.value = State.Error("subtitle", State.Kind.NO_SUBTITLE, pro = true)
            return
        }
        val app = context.applicationContext
        val previous = job
        job = scope.launch {
            previous?.join()
            cancelled = false
            try {
                runPro(app, videoKey, cues, sourceLang, target, title, subtitleName, labels, sink, heard = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProTranslate.NotConfigured) {
                _state.value = State.Error("key", State.Kind.PRO_NOT_CONFIGURED, pro = true)
            } catch (e: ProTranslate.InvalidKey) {
                _state.value = State.Error("key", State.Kind.PRO_INVALID_KEY, pro = true)
            } catch (e: ProTranslate.Refused) {
                _state.value = State.Error(e.category ?: "refusal", State.Kind.PRO_REFUSED, pro = true)
            } catch (e: Throwable) {
                Log.w(TAG, "Pro AI translation failed", e)
                _state.value = State.Error(e.message ?: (e::class.simpleName ?: "error"), pro = true)
            }
        }
    }

    /** Pro AI translation of [cues] (a saved one is reused), shown and, if asked, saved on the device. */
    private suspend fun runPro(
        app: Context,
        videoKey: String,
        cues: List<AiCue>,
        sourceLang: String?,
        target: String,
        title: String?,
        subtitleName: String?,
        labels: Labels,
        sink: Sink,
        heard: Boolean,
    ) {
        if (!ProTranslate.isConfigured) {
            _state.value = State.Error("key", State.Kind.PRO_NOT_CONFIGURED, pro = true)
            return
        }
        val saved = withContext(Dispatchers.IO) {
            ProTranslationFiles.load(app, videoKey, target, subtitleName, cues)
        }
        val out = saved ?: run {
            _state.value = State.ProTranslating(0)
            val translated = ProTranslate.translate(cues, target, title, sourceLang) { _state.value = State.ProTranslating(it) }
            withContext(Dispatchers.IO) { ProTranslationFiles.save(app, videoKey, target, subtitleName, translated) }
                ?: throw IllegalStateException("could not save the translation")
            translated
        }
        val cache = ProTranslationFiles.file(app, videoKey, target, subtitleName)
        val shown = if (AiSettings.translateShowBoth) {
            write(File(cache.parentFile, "both_" + cache.name), AiSrt.toSrt(out, translated = true, both = true))
        } else {
            cache
        }
        deliver(sink, shown, labels.pro(target), target, partial = false)
        finished(app, out, target, subtitleName, pro = true, heard = heard)
        _state.value = State.Done(out.size, sourceLang, pro = true)
    }

    /**
     * Free translation: Google's web translation in batches first (like XPlayer), ML Kit on the
     * device for the lines it could not reach. Says why when nothing at all was translated.
     */
    private suspend fun translateInternal(
        app: Context,
        videoKey: String,
        cues: List<AiCue>,
        sourceLang: String?,
        target: String,
        subtitleName: String?,
        labels: Labels,
        sink: Sink,
        heard: Boolean = false,
    ) {
        val tgt = MlTranslate.mlCode(target) ?: target.lowercase().substringBefore('-')
        val base = subtitleName?.substringBeforeLast('.')?.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
            ?.take(60)?.ifBlank { null } ?: "subtitle"
        val file = File(folder(app, videoKey), "ml_${tgt}_$base.srt")
        _state.value = State.Translating(0)
        var detected: String? = MlTranslate.mlCode(sourceLang)
        val lines = arrayOfNulls<String>(cues.size)
        var googleError: Throwable? = null
        try {
            val result = GoogleTranslate.translate(cues.map { it.text }, detected, tgt) {
                _state.value = State.Translating(it * 9 / 10)
            }
            result.lines.forEachIndexed { i, s -> lines[i] = s }
            if (detected == null) detected = GoogleTranslate.fromGoogle(result.detected)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            googleError = e
            Log.w(TAG, "Google translation failed, trying ML Kit", e)
        }
        val missing = cues.indices.filter { lines[it].isNullOrBlank() && GoogleTranslate.clean(cues[it].text).isNotEmpty() }
        if (missing.isNotEmpty()) {
            val src = detected ?: runCatching { MlTranslate.mlCode(MlTranslate.detectCues(cues)) }.getOrNull()
            val mlTarget = MlTranslate.mlCode(target)
            if (src != null && mlTarget != null) {
                if (src == mlTarget) {
                    missing.forEach { lines[it] = cues[it].text }
                } else {
                    runCatching {
                        val part = MlTranslate.translateCues(
                            missing.map { cues[it] }, src, mlTarget,
                            onDownloading = { _state.value = State.DownloadingLanguage },
                            onProgress = { _state.value = State.Translating(90 + it / 10) },
                        )
                        part.forEachIndexed { k, c -> if (!c.translated.isNullOrBlank()) lines[missing[k]] = c.translated }
                    }.onFailure {
                        if (it is CancellationException) throw it
                        Log.w(TAG, "ML Kit translation failed", it)
                    }
                }
            }
        }
        val translatedCount = lines.count { !it.isNullOrBlank() }
        if (translatedCount == 0) {
            _state.value = if (googleError != null) {
                State.Error(googleError.message ?: (googleError::class.simpleName ?: "error"))
            } else {
                State.Error("source", State.Kind.TRANSLATE_UNSUPPORTED)
            }
            return
        }
        val out = cues.mapIndexed { i, c -> c.copy(translated = lines[i]) }
        write(file, AiSrt.toSrt(out, translated = true, both = AiSettings.translateShowBoth))
        deliver(sink, file, labels.translated(tgt), tgt, partial = false)
        finished(app, out, tgt, subtitleName, pro = false, heard = heard)
        _state.value = State.Done(translatedCount, detected)
    }

    /** The last translation made, for "save to the device" after the fact. */
    private class LastTranslation(val cues: List<AiCue>, val target: String, val name: String?, val pro: Boolean, val heard: Boolean)

    @Volatile private var last: LastTranslation? = null

    /** Where the last save went (null: nothing saved yet); the panel shows it. */
    private val _savedTo = MutableStateFlow<String?>(null)
    val savedTo: StateFlow<String?> = _savedTo.asStateFlow()

    val hasTranslation: Boolean get() = last != null

    private suspend fun finished(app: Context, cues: List<AiCue>, target: String, name: String?, pro: Boolean, heard: Boolean) {
        val item = LastTranslation(cues, target, name, pro, heard)
        last = item
        if (AiSettings.saveTranslations) saveToDevice(app, item)
    }

    /** Saves the last translation to Download/SAMplay/Subtitles; the place it went, or null. */
    suspend fun saveLastToDevice(context: Context): String? {
        val item = last ?: return null
        return saveToDevice(context.applicationContext, item)
    }

    private suspend fun saveToDevice(app: Context, item: LastTranslation): String? = withContext(Dispatchers.IO) {
        runCatching {
            val name = SavedSubtitles.fileName(item.target, item.name, item.pro, item.heard)
            SavedSubtitles.save(app, name, AiSrt.toSrt(item.cues, translated = true))
        }.onFailure { Log.w(TAG, "saving the translation failed", it) }.getOrNull()
            .also { _savedTo.value = it }
    }

    /** How results are named in the subtitle list, in the app's own language. */
    interface Labels {
        fun generated(language: String?): String
        fun translated(language: String): String
        fun pro(language: String): String
    }

    private val noise = Regex("""^\s*[\[(（【][^\])）】]*[\])）】]\s*$""")

    fun cleanWhisper(s: String): String {
        val t = s.trim()
        if (t.isEmpty() || noise.matches(t)) return ""
        if (t.contains("BLANK_AUDIO")) return ""
        return t
    }

    fun defaultModel(): ModelManager.Model = ModelManager.byId(AiSettings.whisperModel)
}
