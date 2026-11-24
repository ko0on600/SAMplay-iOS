package com.nuvio.app.features.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi

/**
 * Nuvio RS: the file has a picture, but no decoder on this device can show it, such as MPEG-2
 * on most phones (Android's own MPEG-2 decoder is enabled on TVs only). ExoPlayer then plays the
 * sound over a black screen and reports no error, so the switch to libmpv never started.
 *
 * Returns the video format nothing here can decode ("video/mpeg2"), or null when a picture can
 * play or the file has none. A track that only goes past a decoder's stated limits counts as
 * playable, as ExoPlayer still tries it.
 */
@OptIn(UnstableApi::class)
internal fun Tracks.undecodableVideoFormat(): String? {
    val video = groups.filter { it.type == C.TRACK_TYPE_VIDEO }
    if (video.isEmpty() || video.any { it.isSupported(/* allowExceedsCapabilities = */ true) }) return null
    val format = video.first().getTrackFormat(0)
    return listOfNotNull(format.sampleMimeType, format.codecs?.takeIf { it.isNotBlank() })
        .joinToString(" ")
        .ifBlank { "video" }
}
