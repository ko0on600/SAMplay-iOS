package com.nuvio.app.features.player

import androidx.compose.runtime.Composable

@Composable
actual fun rememberSubtitleFileActions(
    onLoaded: (AddonSubtitle) -> Unit,
    headersFor: (String) -> Map<String, String>,
): SubtitleFileActions = SubtitleFileActions(load = null, save = null)
