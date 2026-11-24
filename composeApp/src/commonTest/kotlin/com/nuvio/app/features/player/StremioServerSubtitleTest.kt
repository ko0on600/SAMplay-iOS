package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StremioServerSubtitleTest {
    @Test
    fun subtitlesThroughStremiosOwnServerAreRecognised() {
        assertTrue("http://127.0.0.1:11470/subtitles.vtt?from=https%3A%2F%2Fexample.org%2Fa.zip".needsStremioStreamingServer())
        assertTrue("http://localhost:11470/subtitles.vtt?from=x".needsStremioStreamingServer())
    }

    @Test
    fun ordinarySubtitleLinksAreKept() {
        assertFalse("https://subs5.strem.io/en/download/file/1".needsStremioStreamingServer())
        assertFalse("http://127.0.0.1:8090/subtitle.srt".needsStremioStreamingServer())
    }
}
