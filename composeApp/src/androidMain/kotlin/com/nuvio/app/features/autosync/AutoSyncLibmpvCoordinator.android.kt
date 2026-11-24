package com.nuvio.app.features.autosync

import android.content.Context
import com.nuvio.app.features.autosync.bubble.AutoSyncBubbleKind
import com.nuvio.app.features.autosync.bubble.showAutoSyncMessage
import com.nuvio.app.features.reshaped.livetv.LiveTvPlaybackRegistry
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.nuvio.app.features.player.audiosync.SyncLog
import com.nuvio.app.features.streams.StreamSubtitle
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Creates the AutoSync coordinator for one libmpv source and disposes it with that source.
 * Pass the libmpv controller through [AutoSyncMpvCoordinator.wrap] before `onControllerReady`.
 */
@Composable
internal fun rememberLibmpvAutoSyncCoordinator(
    scope: CoroutineScope,
    sourceUrl: String,
    sourceHeaders: Map<String, String>,
    externalSubtitles: List<StreamSubtitle>,
    preferredSubtitleLanguage: String?,
    sourceAudioUrl: String? = null,
    /** The libmpv playhead and length in ms, read on the main thread (for the audio sync). */
    positionMs: () -> Long = { 0L },
    durationMs: () -> Long = { 0L },
): AutoSyncMpvCoordinator {
    val context = LocalContext.current
    val latestExternalSubtitles = rememberUpdatedState(externalSubtitles)
    val latestPreferredLanguage = rememberUpdatedState(preferredSubtitleLanguage)
    val latestPositionMs = rememberUpdatedState(positionMs)
    val latestDurationMs = rememberUpdatedState(durationMs)
    val coordinator = remember(sourceUrl, sourceAudioUrl, sourceHeaders, scope) {
        val getSubtitleHeaders: (String) -> Map<String, String> = { subtitleUrl ->
            latestExternalSubtitles.value
                .firstOrNull { it.url == subtitleUrl }
                ?.headers
                .orEmpty()
        }
        val platform = AndroidAutoSyncMpvPlatform(context.applicationContext, sourceUrl)
        // Nuvio RS (from SAMplay's libVLC path): the audio sync on mpv, for streams AutoSync
        // could not time and to watch the ones it did for a later drift.
        platform.audioSync = LibmpvAudioSync(
            context = context.applicationContext,
            scope = scope,
            sourceUrl = sourceUrl,
            sourceAudioUrl = sourceAudioUrl,
            sourceHeaders = sourceHeaders,
            positionMs = { latestPositionMs.value() },
            durationMs = { latestDurationMs.value() },
            getSubtitleHeaders = getSubtitleHeaders,
            writeSubtitleFile = { name, stalePrefix, text -> platform.writeSubtitleFile(name, stalePrefix, text) },
        )
        AutoSyncMpvCoordinator(
            scope = scope,
            sourceUrl = sourceUrl,
            sourceHeaders = sourceHeaders,
            getSubtitleHeaders = getSubtitleHeaders,
            getPreferredLanguage = { latestPreferredLanguage.value },
            platform = platform,
        )
    }
    DisposableEffect(coordinator) {
        onDispose { coordinator.dispose() }
    }
    return coordinator
}

private class AndroidAutoSyncMpvPlatform(private val context: Context, private val sourceUrl: String) : AutoSyncMpvPlatform {
    override val isLiveTv: Boolean get() = LiveTvPlaybackRegistry.isLiveTv(sourceUrl)
    override var audioSync: AutoSyncMpvAudioSync? = null

    override fun log(message: String) = SyncLog.i(message)
    override suspend fun writeSubtitleFile(fileName: String, stalePrefix: String, content: String): String? =
        withContext(Dispatchers.IO) {
            val cacheDir = File(context.cacheDir, "subtitles").also { it.mkdirs() }
            val file = File(cacheDir, fileName)
            cacheDir.listFiles { candidate -> candidate.name.startsWith(stalePrefix) && candidate != file }
                ?.forEach { it.delete() }
            file.writeText(content, Charsets.UTF_8)
            file.absolutePath
        }

    override fun showMessage(kind: AutoSyncBubbleKind, text: String) {
        showAutoSyncMessage(context, kind, text)
    }

    override fun showMessage(text: String) {
        showAutoSyncMessage(context, AutoSyncBubbleKind.Failure, text)
    }
}

/** Keeps the Android construction API used by existing callers and controller regression tests. */
internal fun LibmpvAutoSyncCoordinator(
    context: Context,
    scope: CoroutineScope,
    sourceUrl: String,
    sourceHeaders: Map<String, String>,
    getSubtitleHeaders: (String) -> Map<String, String>,
    getPreferredLanguage: () -> String?,
): AutoSyncMpvCoordinator = AutoSyncMpvCoordinator(
    scope, sourceUrl, sourceHeaders, getSubtitleHeaders, getPreferredLanguage,
    AndroidAutoSyncMpvPlatform(context.applicationContext, sourceUrl),
)
