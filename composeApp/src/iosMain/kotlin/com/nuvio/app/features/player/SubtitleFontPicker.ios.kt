package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import com.nuvio.app.samplay.IosPickers
import com.nuvio.app.samplay.IosPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_playback_subtitle_font_invalid
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember

/**
 * SAMplay iOS: the Reshaped section's own subtitle font (one imported TTF/OTF that replaces the
 * default font), kept with the app's subtitle fonts.
 */
internal object ReshapedSubtitleFontIos {
    private val prefs = IosPrefs("reshaped_subtitle_font")
    private val _fontId = MutableStateFlow(prefs.getString("id"))
    val fontId = _fontId.asStateFlow()

    fun set(font: SubtitleFont?) {
        _fontId.value?.takeIf { it != font?.id }?.let { old -> runCatching { SubtitleFonts.remove(old) } }
        prefs.putString("id", font?.id)
        prefs.putString("name", font?.name)
        _fontId.value = font?.id
    }

    fun name(): String? = prefs.getString("name")
}

@Composable
actual fun rememberSubtitleFontPicker(): SubtitleFontPickerState? {
    val invalidMessage = stringResource(Res.string.settings_playback_subtitle_font_invalid)
    val id by ReshapedSubtitleFontIos.fontId.collectAsState()
    var isImporting by remember { mutableStateOf(false) }
    val importFont = rememberSubtitleFontImporter { result ->
        isImporting = false
        val font = result.getOrNull()
        if (font == null) IosPickers.toast(invalidMessage) else ReshapedSubtitleFontIos.set(font)
    }
    return SubtitleFontPickerState(
        fontName = id?.let { ReshapedSubtitleFontIos.name() },
        isImporting = isImporting,
        // The picker does not report a cancel, so no "importing" state is shown.
        pick = { importFont() },
        reset = { ReshapedSubtitleFontIos.set(null) },
    )
}

@Composable
internal actual fun rememberReshapedSubtitleFontFamily(): FontFamily? {
    val id by ReshapedSubtitleFontIos.fontId.collectAsState()
    val family by produceState<FontFamily?>(null, id) {
        value = id?.let { fontId -> withContext(Dispatchers.Default) { runCatching { SubtitleFontStorage.fontFamily(fontId) }.getOrNull() } }
    }
    return family
}
