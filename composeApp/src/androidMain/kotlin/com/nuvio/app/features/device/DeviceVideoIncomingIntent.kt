package com.nuvio.app.features.device

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * SAMplay: a video another app opens with SAMplay («Open with», a file manager, the gallery).
 * The file is played at once; it resumes where the viewer stopped last time.
 */
internal object DeviceVideoIncomingIntent {
    private const val TAG = "SamplayOpenWith"
    private val videoExtensions = setOf(
        "mp4", "m4v", "mkv", "webm", "avi", "mov", "wmv", "flv", "ts", "m2ts", "mts", "3gp", "mpg", "mpeg", "ogv",
    )

    fun accept(context: Context, intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        val uri = intent.data ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme != "content" && scheme != "file") return false
        if (!isVideo(context, intent, uri)) return false
        val app = context.applicationContext
        // A provider can be slow to answer, so the name is read off the main thread.
        Thread({
            runCatching {
                DeviceVideoRepository.requestPlay(DeviceVideoPlatform.describe(app, uri))
            }.onFailure { Log.w(TAG, "Could not open the shared video", it) }
        }, "SamplayOpenWith").apply { isDaemon = true }.start()
        return true
    }

    private fun isVideo(context: Context, intent: Intent, uri: Uri): Boolean {
        val type = (intent.type ?: runCatching { context.contentResolver.getType(uri) }.getOrNull())?.lowercase()
        if (type != null && type.startsWith("video/")) return true
        if (type != null && type != "application/octet-stream") return false
        val extension = uri.lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase()
        return extension in videoExtensions
    }
}
