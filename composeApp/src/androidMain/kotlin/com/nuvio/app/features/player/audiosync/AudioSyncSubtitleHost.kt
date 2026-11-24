package com.nuvio.app.features.player.audiosync

import androidx.media3.extractor.text.CuesWithTiming

/**
 * Nuvio RS (from SAMplay): where the audio sync reads the subtitle it syncs and puts it back
 * retimed. ExoPlayer's sidecar renderer on one engine; on libmpv a file mpv loads in place of the
 * track (see LibmpvAudioSync), since mpv draws its subtitles itself.
 */
internal interface AudioSyncSubtitleHost {
    /** The subtitle shown now, or null. */
    val activeSidecarSubtitleKey: String?

    /** Its cues as shown now. */
    val sidecarTimedCues: List<CuesWithTiming>

    /** A number that changes whenever another subtitle (or another copy of it) is shown. */
    fun currentGenerationFor(url: String): Long?

    /**
     * Shows [cues] as [newUrl] in place of [expectedCurrentUrl], when that is still the subtitle
     * shown (and still [expectedGeneration] of it). False when the subtitle moved on.
     */
    fun commitPreparedSidecarSubtitle(
        expectedCurrentUrl: String,
        newUrl: String,
        cues: List<CuesWithTiming>,
        expectedGeneration: Long? = null,
    ): Boolean
}
