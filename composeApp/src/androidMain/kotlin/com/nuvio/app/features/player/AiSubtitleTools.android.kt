package com.nuvio.app.features.player

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.nuvio.app.features.ai.AiCue
import com.nuvio.app.features.ai.AiSettings
import com.nuvio.app.features.ai.AiSubtitleJobs
import com.nuvio.app.features.ai.MlTranslate
import com.nuvio.app.features.ai.ProProvider
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.ai_generate_unsupported
import nuvio.composeapp.generated.resources.ai_label_generated
import nuvio.composeapp.generated.resources.ai_label_pro
import nuvio.composeapp.generated.resources.ai_label_translated
import nuvio.composeapp.generated.resources.ai_status_done
import nuvio.composeapp.generated.resources.ai_status_downloading_language
import nuvio.composeapp.generated.resources.ai_status_downloading_model
import nuvio.composeapp.generated.resources.ai_status_extracting
import nuvio.composeapp.generated.resources.ai_status_failed
import nuvio.composeapp.generated.resources.ai_status_no_audio
import nuvio.composeapp.generated.resources.ai_status_no_subtitle
import nuvio.composeapp.generated.resources.ai_status_pro_invalid
import nuvio.composeapp.generated.resources.ai_status_pro_key
import nuvio.composeapp.generated.resources.ai_status_pro_refused
import nuvio.composeapp.generated.resources.ai_status_pro_translating
import nuvio.composeapp.generated.resources.ai_status_transcribing
import nuvio.composeapp.generated.resources.ai_status_translating
import nuvio.composeapp.generated.resources.ai_status_unsupported_language
import org.jetbrains.compose.resources.stringResource

private val aiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

@Composable
actual fun rememberAiSubtitleTools(
    source: AiSubtitleSource,
    onReady: (AddonSubtitle) -> Unit,
): AiSubtitleTools? {
    val context = LocalContext.current
    remember { AiSettings.initialize(context) }
    val currentOnReady by rememberUpdatedState(onReady)
    val state by AiSubtitleJobs.state.collectAsState()
    val proKeySet by AiSettings.proAiKeySet.collectAsState()
    val proProvider by AiSettings.proProvider.collectAsState()
    // Bumped when the model is saved, so the field shows what was kept.
    var proSettingsVersion by remember { mutableIntStateOf(0) }
    val proModel = remember(proProvider, proSettingsVersion) { AiSettings.proModel(proProvider) }
    var showBoth by remember { mutableStateOf(AiSettings.translateShowBoth) }
    var saveCopies by remember { mutableStateOf(AiSettings.saveTranslations) }
    val savedTo by AiSubtitleJobs.savedTo.collectAsState()
    val saveFailed = stringResource(Res.string.ai_status_failed, "save")

    val labelGenerated = stringResource(Res.string.ai_label_generated, "%s")
    val labelTranslated = stringResource(Res.string.ai_label_translated, "%s")
    val labelPro = stringResource(Res.string.ai_label_pro, "%s")
    val labels = object : AiSubtitleJobs.Labels {
        override fun generated(language: String?) = labelGenerated.replace("%s", languageName(language))
        override fun translated(language: String) = labelTranslated.replace("%s", languageName(language))
        override fun pro(language: String) = labelPro.replace("%s", languageName(language))
    }
    val sink = AiSubtitleJobs.Sink { file, label, language, _ ->
        currentOnReady(
            AddonSubtitle(
                // One entry per kind of result: a newer version replaces the one listed.
                id = if (file.name.startsWith("ai_generated")) {
                    "$AI_GENERATED_SUBTITLE_PREFIX${file.parentFile?.name}"
                } else {
                    "ai:${file.parentFile?.name}:${file.name.substringBeforeLast('.')}"
                },
                url = Uri.fromFile(file).toString(),
                language = language ?: "und",
                display = label,
                addonName = "AI",
            ),
        )
    }

    val status = statusOf(state)
    val languages = remember {
        val all = MlTranslate.languages()
        val first = listOf("ar", "en").filter { it in all }
        (first + (all - first.toSet())).map { it to MlTranslate.displayName(it) }
    }
    val unsupported = stringResource(Res.string.ai_generate_unsupported)

    return AiSubtitleTools(
        status = status,
        canGenerate = remember { runCatching { AiSubtitleJobs.canGenerate() }.getOrDefault(false) },
        proConfigured = proKeySet,
        languages = languages,
        targetLanguage = AiSettings.targetLanguage,
        spokenLanguage = AiSettings.spokenLanguage,
        generate = { spoken, translateTo, pro ->
            val url = source.streamUrl()
            if (url.isNullOrBlank()) {
                Toast.makeText(context, unsupported, Toast.LENGTH_SHORT).show()
            } else {
                AiSettings.spokenLanguage = spoken
                translateTo?.let { AiSettings.targetLanguage = it }
                AiSubtitleJobs.generate(
                    context = context,
                    url = url,
                    headers = source.headers(),
                    videoKey = source.videoKey(),
                    model = AiSubtitleJobs.defaultModel(),
                    spokenLang = spoken,
                    translateTo = translateTo,
                    labels = labels,
                    sink = sink,
                    pro = pro,
                    title = source.title(),
                )
            }
        },
        translate = { target ->
            AiSettings.targetLanguage = target
            withCurrentCues(context, source) { cues, subtitle ->
                AiSubtitleJobs.translate(
                    context = context,
                    videoKey = source.videoKey(),
                    cues = cues,
                    sourceLang = subtitle?.language?.takeIf { it != "und" },
                    target = target,
                    subtitleName = subtitle?.display,
                    labels = labels,
                    sink = sink,
                )
            }
        },
        translatePro = { target ->
            AiSettings.targetLanguage = target
            withCurrentCues(context, source) { cues, subtitle ->
                AiSubtitleJobs.translatePro(
                    context = context,
                    videoKey = source.videoKey(),
                    cues = cues,
                    sourceLang = subtitle?.language?.takeIf { it != "und" },
                    target = target,
                    title = source.title(),
                    subtitleName = subtitle?.display,
                    labels = labels,
                    sink = sink,
                )
            }
        },
        cancel = { AiSubtitleJobs.cancel() },
        proProviders = ProProvider.entries.map { it.id to it.label },
        proProvider = proProvider.id,
        proProviderName = proProvider.label,
        proKeyHint = proProvider.keyHint,
        proModel = proModel,
        proDefaultModel = proProvider.defaultModel,
        selectProProvider = { id -> AiSettings.selectProProvider(ProProvider.byId(id)) },
        saveProSettings = { key, model ->
            if (key.isNotBlank()) AiSettings.setProKey(proProvider, key)
            AiSettings.setProModel(proProvider, model.takeIf { it != proProvider.defaultModel }.orEmpty())
            proSettingsVersion++
        },
        showBoth = showBoth,
        setShowBoth = { AiSettings.translateShowBoth = it; showBoth = it },
        saveCopies = saveCopies,
        setSaveCopies = { AiSettings.saveTranslations = it; saveCopies = it },
        canSaveNow = state is AiSubtitleJobs.State.Done && AiSubtitleJobs.hasTranslation,
        saveNow = {
            aiScope.launch {
                val where = AiSubtitleJobs.saveLastToDevice(context)
                Toast.makeText(context, where ?: saveFailed, Toast.LENGTH_LONG).show()
            }
        },
        savedTo = savedTo,
    )
}

/** The cues of the subtitle shown now, with its delay, or none when no subtitle file is shown. */
private fun withCurrentCues(
    context: Context,
    source: AiSubtitleSource,
    run: (List<AiCue>, AddonSubtitle?) -> Unit,
) {
    val subtitle = source.currentSubtitle()
    if (subtitle == null) {
        run(emptyList(), null)
        return
    }
    val delayMs = source.subtitleDelayMs().toLong()
    aiScope.launch {
        val cues = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = subtitleBytes(context, subtitle.url, source.subtitleHeaders(subtitle.url))
                val text = subtitleText(bytes).text
                PlayerSubtitleCueParser.parse(text, subtitle.url).map { cue ->
                    AiCue(
                        startMs = (cue.startTimeMs + delayMs).coerceAtLeast(0L),
                        endMs = (cue.endTimeMs + delayMs).coerceAtLeast(0L),
                        text = cue.text,
                    )
                }
            }.getOrDefault(emptyList())
        }
        run(cues, subtitle)
    }
}

private fun languageName(code: String?): String {
    if (code.isNullOrBlank()) return "?"
    return Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault())
        .replaceFirstChar { it.titlecase(Locale.getDefault()) }
        .ifBlank { code }
}

@Composable
private fun statusOf(state: AiSubtitleJobs.State): AiToolStatus {
    val running = AiSubtitleJobs.isRunning
    return when (state) {
        AiSubtitleJobs.State.Idle -> AiToolStatus()
        is AiSubtitleJobs.State.DownloadingModel ->
            AiToolStatus(true, stringResource(Res.string.ai_status_downloading_model) + " ${state.pct}%", state.pct)
        is AiSubtitleJobs.State.Extracting ->
            AiToolStatus(true, stringResource(Res.string.ai_status_extracting) + " ${state.pct}%", state.pct)
        is AiSubtitleJobs.State.Transcribing ->
            AiToolStatus(true, stringResource(Res.string.ai_status_transcribing) + " ${state.pct}%", state.pct)
        AiSubtitleJobs.State.DownloadingLanguage ->
            AiToolStatus(true, stringResource(Res.string.ai_status_downloading_language), -1)
        is AiSubtitleJobs.State.Translating ->
            AiToolStatus(true, stringResource(Res.string.ai_status_translating) + " ${state.pct}%", state.pct)
        is AiSubtitleJobs.State.ProTranslating ->
            AiToolStatus(true, stringResource(Res.string.ai_status_pro_translating) + " ${state.pct}%", state.pct)
        is AiSubtitleJobs.State.Done ->
            AiToolStatus(running, stringResource(Res.string.ai_status_done, state.lines), 100)
        is AiSubtitleJobs.State.Error -> AiToolStatus(
            running = running,
            message = when (state.kind) {
                AiSubtitleJobs.State.Kind.CPU -> stringResource(Res.string.ai_generate_unsupported)
                AiSubtitleJobs.State.Kind.NO_AUDIO -> stringResource(Res.string.ai_status_no_audio)
                AiSubtitleJobs.State.Kind.NO_SUBTITLE -> stringResource(Res.string.ai_status_no_subtitle)
                AiSubtitleJobs.State.Kind.TRANSLATE_UNSUPPORTED -> stringResource(Res.string.ai_status_unsupported_language)
                AiSubtitleJobs.State.Kind.PRO_NOT_CONFIGURED -> stringResource(Res.string.ai_status_pro_key)
                AiSubtitleJobs.State.Kind.PRO_INVALID_KEY -> stringResource(Res.string.ai_status_pro_invalid)
                AiSubtitleJobs.State.Kind.PRO_REFUSED -> stringResource(Res.string.ai_status_pro_refused)
                AiSubtitleJobs.State.Kind.GENERIC -> stringResource(Res.string.ai_status_failed, state.message)
            },
            failed = true,
        )
    }
}
