package com.nuvio.app.features.player

import androidx.compose.runtime.Composable

/** The AI subtitle tools (whisper.cpp, ML Kit, Pro AI) are Android-only. */
@Composable
actual fun rememberAiSubtitleTools(
    source: AiSubtitleSource,
    onReady: (AddonSubtitle) -> Unit,
): AiSubtitleTools? = null
