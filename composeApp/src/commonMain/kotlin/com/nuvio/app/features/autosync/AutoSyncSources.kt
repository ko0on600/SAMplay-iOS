package com.nuvio.app.features.autosync

/**
 * SAMplay: the sources AutoSync works on. Streams, and files on this device (the library's
 * «On device» videos, «Open file», another app's «Open with», downloads): the player reads those
 * itself, so the embedded reference and the audio reach AutoSync the same way. Live TV stays out
 * (checked by the callers).
 */
internal fun isAutoSyncSource(url: String): Boolean =
    url.startsWith("https://", ignoreCase = true) ||
        url.startsWith("http://", ignoreCase = true) ||
        url.startsWith("content://", ignoreCase = true) ||
        url.startsWith("file://", ignoreCase = true)
