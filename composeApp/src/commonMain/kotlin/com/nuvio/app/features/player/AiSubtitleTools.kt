package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.ai_action_generate
import nuvio.composeapp.generated.resources.ai_action_generate_desc
import nuvio.composeapp.generated.resources.ai_action_pro
import nuvio.composeapp.generated.resources.ai_action_pro_desc
import nuvio.composeapp.generated.resources.ai_action_translate
import nuvio.composeapp.generated.resources.ai_action_translate_desc
import nuvio.composeapp.generated.resources.ai_generate_unsupported
import nuvio.composeapp.generated.resources.ai_panel_title
import nuvio.composeapp.generated.resources.ai_pro_key_change
import nuvio.composeapp.generated.resources.ai_pro_key_hint
import nuvio.composeapp.generated.resources.ai_pro_key_save
import nuvio.composeapp.generated.resources.ai_pro_model_hint
import nuvio.composeapp.generated.resources.ai_pro_provider
import nuvio.composeapp.generated.resources.ai_spoken_language
import nuvio.composeapp.generated.resources.ai_spoken_auto
import nuvio.composeapp.generated.resources.ai_stop
import nuvio.composeapp.generated.resources.ai_target_language
import nuvio.composeapp.generated.resources.ai_way_this_subtitle
import nuvio.composeapp.generated.resources.ai_way_listening
import nuvio.composeapp.generated.resources.ai_listen_basic
import nuvio.composeapp.generated.resources.ai_listen_basic_desc
import nuvio.composeapp.generated.resources.ai_listen_pro
import nuvio.composeapp.generated.resources.ai_listen_pro_desc
import nuvio.composeapp.generated.resources.ai_show_title
import nuvio.composeapp.generated.resources.ai_show_translation_only
import nuvio.composeapp.generated.resources.ai_show_both
import nuvio.composeapp.generated.resources.ai_save_copies
import nuvio.composeapp.generated.resources.ai_save_now
import nuvio.composeapp.generated.resources.ai_saved_to
import org.jetbrains.compose.resources.stringResource

/** Id prefix of subtitles made from the video's own audio: they are in time and skip AutoSync. */
const val AI_GENERATED_SUBTITLE_PREFIX = "ai:gen:"

/** Nuvio RS (from SAMplay): what the AI subtitle tools are doing, as the panel shows it. */
data class AiToolStatus(
    val running: Boolean = false,
    val message: String? = null,
    /** 0..100, or -1 while the length of the step is unknown. */
    val progress: Int = -1,
    val failed: Boolean = false,
)

/**
 * The AI subtitle tools of SAMplay: subtitles from the video's audio (whisper.cpp), free on-device
 * translation (ML Kit), and "Pro AI" translation with Claude on the user's own key.
 */
class AiSubtitleTools(
    val status: AiToolStatus,
    /** whisper.cpp runs only on 64-bit ARMv8.2 phones. */
    val canGenerate: Boolean,
    val proConfigured: Boolean,
    /** Translation languages: code to name, in the app's language. */
    val languages: List<Pair<String, String>>,
    val targetLanguage: String,
    val spokenLanguage: String,
    /** [pro]: the heard lines are translated by Pro AI (only with [translateTo]). */
    val generate: (spoken: String, translateTo: String?, pro: Boolean) -> Unit,
    val translate: (target: String) -> Unit,
    val translatePro: (target: String) -> Unit,
    val cancel: () -> Unit,
    /** Pro AI services (Claude, ChatGPT, Gemini, Grok): id to name. */
    val proProviders: List<Pair<String, String>>,
    val proProvider: String,
    val proProviderName: String,
    /** How the chosen service's keys start ("sk-ant-…"). */
    val proKeyHint: String,
    val proModel: String,
    val proDefaultModel: String,
    val selectProProvider: (String) -> Unit,
    /** Saves the chosen service's key (a blank one keeps the saved key) and model (blank: default). */
    val saveProSettings: (key: String, model: String) -> Unit,
    /** SAMplay: a translation shows the original line above the translated one. */
    val showBoth: Boolean = false,
    val setShowBoth: (Boolean) -> Unit = {},
    /** SAMplay: every translation is also saved to Download/SAMplay/Subtitles. */
    val saveCopies: Boolean = false,
    val setSaveCopies: (Boolean) -> Unit = {},
    /** A translation was made and can be saved to the device now. */
    val canSaveNow: Boolean = false,
    val saveNow: () -> Unit = {},
    /** Where the last saved translation went. */
    val savedTo: String? = null,
)

/** What the tools work on: the stream, the video, and the subtitle shown now. */
class AiSubtitleSource(
    val streamUrl: () -> String?,
    val headers: () -> Map<String, String>,
    val videoKey: () -> String,
    val title: () -> String?,
    val currentSubtitle: () -> AddonSubtitle?,
    val subtitleHeaders: (String) -> Map<String, String>,
    /** The delay set on the subtitle shown now, kept by a translation of it. */
    val subtitleDelayMs: () -> Int,
)

/** The AI tools, or null where the platform has none. [onReady] shows a result as a subtitle. */
@Composable
expect fun rememberAiSubtitleTools(
    source: AiSubtitleSource,
    onReady: (AddonSubtitle) -> Unit,
): AiSubtitleTools?

/** The AI page of the subtitle panel. */
@Composable
internal fun AiSubtitlePanel(
    tools: AiSubtitleTools,
    maxHeight: Dp,
    onClose: () -> Unit,
) {
    var target by remember { mutableStateOf(tools.targetLanguage) }
    var spoken by remember { mutableStateOf(tools.spokenLanguage) }
    var keyDraft by remember { mutableStateOf("") }
    var modelDraft by remember(tools.proProvider, tools.proModel) { mutableStateOf(tools.proModel) }
    var askKey by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier
            .widthIn(max = 640.dp)
            .heightIn(max = maxHeight)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xE6141414))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = null, tint = Color.White)
            }
            Text(stringResource(Res.string.ai_panel_title), color = Color.White, style = MaterialTheme.typography.titleLarge)
        }

        tools.status.message?.let { message ->
            Text(message, color = if (tools.status.failed) Color(0xFFFF8A80) else Color.White.copy(alpha = 0.9f))
            if (tools.status.running) {
                if (tools.status.progress >= 0) {
                    LinearProgressIndicator(
                        progress = { tools.status.progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = accent,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = accent)
                }
            }
        }
        if (tools.status.running) {
            AiActionRow(stringResource(Res.string.ai_stop), null, onClick = tools.cancel)
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(stringResource(Res.string.ai_target_language), color = Color.White.copy(alpha = 0.7f))
                LanguageChips(tools.languages, target) { target = it }
            }
            item {
                Text(stringResource(Res.string.ai_way_this_subtitle), color = Color.White.copy(alpha = 0.7f))
                AiActionRow(
                    title = stringResource(Res.string.ai_action_translate),
                    description = stringResource(Res.string.ai_action_translate_desc),
                    onClick = { tools.translate(target) },
                )
            }
            item {
                AiActionRow(
                    title = stringResource(Res.string.ai_action_pro) + " · " + tools.proProviderName,
                    description = stringResource(Res.string.ai_action_pro_desc),
                    onClick = { if (tools.proConfigured) tools.translatePro(target) else askKey = true },
                )
            }
            if (tools.canGenerate) {
                item {
                    Text(stringResource(Res.string.ai_way_listening), color = Color.White.copy(alpha = 0.7f))
                    AiActionRow(
                        title = stringResource(Res.string.ai_listen_basic),
                        description = stringResource(Res.string.ai_listen_basic_desc),
                        onClick = { tools.generate(spoken, target, false) },
                    )
                }
                item {
                    AiActionRow(
                        title = stringResource(Res.string.ai_listen_pro) + " · " + tools.proProviderName,
                        description = stringResource(Res.string.ai_listen_pro_desc),
                        onClick = { if (tools.proConfigured) tools.generate(spoken, target, true) else askKey = true },
                    )
                }
            }
            item {
                Text(stringResource(Res.string.ai_show_title), color = Color.White.copy(alpha = 0.7f))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    AiChip(stringResource(Res.string.ai_show_translation_only), selected = !tools.showBoth) { tools.setShowBoth(false) }
                    AiChip(stringResource(Res.string.ai_show_both), selected = tools.showBoth) { tools.setShowBoth(true) }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    AiChip(stringResource(Res.string.ai_save_copies), selected = tools.saveCopies) { tools.setSaveCopies(!tools.saveCopies) }
                    if (tools.canSaveNow) {
                        AiChip(stringResource(Res.string.ai_save_now), selected = false, onClick = tools.saveNow)
                    }
                }
                tools.savedTo?.let {
                    Text(stringResource(Res.string.ai_saved_to, it), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                Text(stringResource(Res.string.ai_pro_provider), color = Color.White.copy(alpha = 0.7f))
                LanguageChips(tools.proProviders, tools.proProvider) {
                    tools.selectProProvider(it)
                    keyDraft = ""
                    askKey = false
                }
            }
            if (tools.proConfigured) {
                item {
                    AiChip(stringResource(Res.string.ai_pro_key_change), selected = askKey) { askKey = !askKey }
                }
            }
            if (askKey || !tools.proConfigured) {
                item {
                    val fieldColors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = keyDraft,
                            onValueChange = { keyDraft = it.trim() },
                            singleLine = true,
                            placeholder = {
                                Text(stringResource(Res.string.ai_pro_key_hint, tools.proProviderName, tools.proKeyHint))
                            },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            colors = fieldColors,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = modelDraft,
                                onValueChange = { modelDraft = it.trim() },
                                singleLine = true,
                                placeholder = { Text(stringResource(Res.string.ai_pro_model_hint, tools.proDefaultModel)) },
                                modifier = Modifier.weight(1f),
                                colors = fieldColors,
                            )
                            val changed = keyDraft.isNotBlank() || modelDraft != tools.proModel
                            AiChip(stringResource(Res.string.ai_pro_key_save), selected = changed) {
                                if (changed) {
                                    tools.saveProSettings(keyDraft, modelDraft)
                                    keyDraft = ""
                                    askKey = false
                                }
                            }
                        }
                    }
                }
            }
            item {
                if (tools.canGenerate) {
                    AiActionRow(
                        title = stringResource(Res.string.ai_action_generate),
                        description = stringResource(Res.string.ai_action_generate_desc),
                        onClick = { tools.generate(spoken, null, false) },
                    )
                    Text(stringResource(Res.string.ai_spoken_language), color = Color.White.copy(alpha = 0.7f))
                    LanguageChips(
                        listOf("auto" to stringResource(Res.string.ai_spoken_auto)) + tools.languages,
                        spoken,
                    ) { spoken = it }
                } else {
                    Text(stringResource(Res.string.ai_generate_unsupported), color = Color.White.copy(alpha = 0.6f))
                }
            }
        }
    }
}

@Composable
private fun LanguageChips(languages: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        items(languages, key = { it.first }) { (code, name) ->
            AiChip(name, selected = code == selected) { onPick(code) }
        }
    }
}

@Composable
private fun AiChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        color = if (selected) Color.Black else Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Color.White else Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun AiActionRow(title: String, description: String?, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium)
        description?.let { Text(it, color = Color.White.copy(alpha = 0.65f), style = MaterialTheme.typography.bodySmall) }
    }
}
