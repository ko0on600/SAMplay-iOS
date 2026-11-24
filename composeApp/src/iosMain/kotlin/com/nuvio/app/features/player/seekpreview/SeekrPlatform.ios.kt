package com.nuvio.app.features.player.seekpreview

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

internal actual fun createSeekrHttpClient(): HttpClient = HttpClient(Darwin) {
    expectSuccess = false
}

internal actual fun decodeSeekrSpriteSheet(bytes: ByteArray): ImageBitmap? =
    runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

// On-device previews need the Android extractor and decoder; iOS falls back to Seekr.
internal actual fun openLocalSeekPreviewTrack(cacheKey: String, durationMs: Long): SeekPreviewTrack? = null
