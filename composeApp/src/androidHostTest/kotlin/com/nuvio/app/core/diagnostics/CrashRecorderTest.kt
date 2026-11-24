package com.nuvio.app.core.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrashRecorderTest {
    @Test
    fun tombstoneKeepsTheSignalLibrariesAndFunctions() {
        // A tombstone protobuf: readable strings between binary field tags.
        val bytes = byteArrayOf(0x0a, 0x07) + "SIGSEGV".toByteArray() + byteArrayOf(0x12, 0x0b) +
            "SEGV_MAPERR".toByteArray() + byteArrayOf(0x1a, 0x00, 0x05) +
            "/data/app/~~x/com.nuvioreshaped.app-y/lib/arm64/libmpv.so".toByteArray() + byteArrayOf(0x22, 0x01) +
            "_ZN6mpv_render_context_render".toByteArray() + byteArrayOf(0x00) +
            "uninteresting text".toByteArray() + byteArrayOf(0x00) +
            "fetched https://pikpak.example/file?sign=SECRET libplacebo.so".toByteArray()
        val summary = assertNotNull(CrashRecorder.tombstoneSummary(bytes))
        val lines = summary.lines()
        assertEquals("Native trace:", lines.first())
        assertTrue("SIGSEGV" in lines)
        assertTrue("SEGV_MAPERR" in lines)
        assertTrue("libmpv.so" in lines, summary)
        assertTrue(lines.any { it.startsWith("_ZN6mpv") })
        assertTrue(lines.none { "uninteresting" in it })
        // Links are cut to their host, so a signed drive link never reaches the shared log.
        assertTrue(lines.none { "SECRET" in it }, summary)
    }

    @Test
    fun tombstoneWithNothingReadableGivesNoTrace() {
        assertNull(CrashRecorder.tombstoneSummary(byteArrayOf(0x01, 0x02, 0x03)))
    }

    @Test
    fun anrKeepsOnlyTheMainThread() {
        val trace = """
            ----- pid 123 at 2026-11-15 -----
            "Signal Catcher" daemon prio=10 tid=2 Runnable
              at a.b.c(Unknown)

            "main" prio=5 tid=1 Blocked
              at com.nuvio.app.Player.release(Player.kt:10)
              at android.os.Looper.loop(Looper.java:288)

            "NuvioAudioSyncFetch" prio=5 tid=20 Runnable
        """.trimIndent()
        val main = assertNotNull(CrashRecorder.anrMainThread(trace))
        assertTrue("Player.release" in main)
        assertTrue("Signal Catcher" !in main)
        assertTrue("NuvioAudioSyncFetch" !in main)
    }
}
