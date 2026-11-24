package com.nuvio.app.core.diagnostics

import com.nuvio.app.features.player.audiosync.SyncLog

/**
 * Nuvio RS: the diagnostics entry point of the subtitle code ported from SAMplay. SAMplay keeps
 * its own event log; here every line goes to the audio sync log (Settings › share the sync log),
 * which already shortens each link to its host so no add-on key or signed link reaches it.
 */
internal object Diag {
    private val link = Regex("""https?://([^/\s?#]+)[^\s]*""")

    fun log(tag: String, message: String) = SyncLog.i("[$tag] ${scrub(message)}")

    /** A link reduced to its host, the same way the sync log writes it. */
    fun scrub(text: String): String = link.replace(text) { "<${it.groupValues[1]}>" }
}
