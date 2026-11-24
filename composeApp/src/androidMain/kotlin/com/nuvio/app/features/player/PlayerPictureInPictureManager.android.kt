package com.nuvio.app.features.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Rational
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import com.nuvio.app.R
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

internal const val PIP_ACTION_TOGGLE_PLAY_PAUSE = "com.nuvio.app.action.PIP_TOGGLE_PLAY_PAUSE"
internal const val PIP_ACTION_SEEK_BACK = "com.nuvio.app.action.PIP_SEEK_BACK"
internal const val PIP_ACTION_SEEK_FORWARD = "com.nuvio.app.action.PIP_SEEK_FORWARD"

internal object PlayerPictureInPictureManager {
    private data class SessionState(
        val isActive: Boolean = false,
        val isPlaying: Boolean = false,
        val videoSize: IntSize = IntSize.Zero,
        /** Nuvio RS: background audio on, so leaving the app keeps the sound instead of floating. */
        val backgroundAudio: Boolean = false,
        /** Nuvio RS: the app's own floating window is allowed, so the system one stays out of the way. */
        val ownWindow: Boolean = false,
    )

    private var sessionState = SessionState()
    private var lastAppliedSessionState: SessionState? = null
    private var lastAppliedSourceRectHint: Rect? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wasInPictureInPictureMode = false
    private var pendingPictureInPictureExitCheck: Runnable? = null
    private var pausePlaybackCallback: (() -> Unit)? = null
    private var togglePlaybackCallback: (() -> Unit)? = null
    private var seekByCallback: ((Long) -> Unit)? = null
    private var appStartedActivityAtMs = 0L

    fun updateSession(
        activity: Activity,
        isActive: Boolean,
        isPlaying: Boolean,
        videoSize: IntSize,
        backgroundAudio: Boolean = false,
    ) {
        sessionState = SessionState(
            isActive = isActive,
            isPlaying = isPlaying,
            videoSize = videoSize,
            backgroundAudio = backgroundAudio,
            ownWindow = FloatingPlayerWindow.isAvailable(activity),
        )
        applyPictureInPictureParams(activity)
    }

    /** Nuvio RS: the player's floating-window button. */
    fun enterNow(activity: Activity): Boolean {
        if (!sessionState.isActive) return false
        // The app's own window when allowed (it tucks into the screen edge on every phone); the
        // first time, ask for "Display over other apps", after that fall back to the system one.
        if (FloatingPlayerWindow.isAvailable(activity)) {
            if (FloatingPlayerWindow.show(activity)) {
                activity.moveTaskToBack(true)
                return true
            }
        } else if (FloatingPlayerWindow.requestPermissionOnce(activity)) {
            Toast.makeText(activity.applicationContext, floatingPermissionLabel, Toast.LENGTH_LONG).show()
            return true
        }
        if (!activity.canUsePictureInPicture()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInPictureInPictureMode) return false
        return runCatching { activity.enterPictureInPictureMode(buildParams(activity)) }.getOrDefault(false)
    }

    /** True when this device lets the app float its video (its own window works from Android 8). */
    @Suppress("UNUSED_PARAMETER")
    fun isSupported(activity: Activity): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    fun clearSession(activity: Activity) {
        sessionState = SessionState()
        wasInPictureInPictureMode = false
        clearPendingPictureInPictureExitCheck()
        applyPictureInPictureParams(activity)
    }

    fun registerPausePlaybackCallback(callback: (() -> Unit)?) {
        pausePlaybackCallback = callback
        if (callback == null) {
            clearPendingPictureInPictureExitCheck()
        }
    }

    fun registerTogglePlaybackCallback(callback: (() -> Unit)?) {
        togglePlaybackCallback = callback
    }

    /** Nuvio RS: the floating window's back/forward buttons move playback by this many ms. */
    fun registerSeekByCallback(callback: ((Long) -> Unit)?) {
        seekByCallback = callback
    }

    fun seekViaRemoteAction(deltaMs: Long) {
        seekByCallback?.invoke(deltaMs)
    }

    /** The app opened one of its own screens (file picker, settings…): the user is not leaving. */
    fun onAppStartsActivity() {
        appStartedActivityAtMs = SystemClock.uptimeMillis()
    }

    fun onUserLeaveHint(activity: Activity): Boolean {
        // Nuvio RS: leaving with background audio on keeps the sound; say so while still in front,
        // since a toast posted once the app has stopped is dropped.
        if (sessionState.isActive && sessionState.isPlaying && sessionState.backgroundAudio) {
            Toast.makeText(activity.applicationContext, backgroundAudioLabel, Toast.LENGTH_SHORT).show()
        }
        if (sessionState.isActive && sessionState.isPlaying && !sessionState.backgroundAudio &&
            FloatingPlayerWindow.isAvailable(activity) &&
            SystemClock.uptimeMillis() - appStartedActivityAtMs > 1_000L
        ) {
            return FloatingPlayerWindow.show(activity)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return false
        }
        return enterIfEligible(activity)
    }

    fun onPictureInPictureModeChanged(
        activity: ComponentActivity,
        isInPictureInPictureMode: Boolean,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val wasInPictureInPicture = wasInPictureInPictureMode
        wasInPictureInPictureMode = isInPictureInPictureMode
        clearPendingPictureInPictureExitCheck()

        if (!wasInPictureInPicture || isInPictureInPictureMode) return

        val exitCheck = Runnable {
            val returnedToForeground = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (!returnedToForeground || activity.isFinishing || activity.isDestroyed) {
                pausePlaybackCallback?.invoke()
            }
        }
        pendingPictureInPictureExitCheck = exitCheck
        mainHandler.postDelayed(exitCheck, 250L)
    }

    fun toggleViaRemoteAction(context: Context) {
        val wasPlaying = sessionState.isPlaying
        togglePlaybackCallback?.invoke()
        sessionState = sessionState.copy(isPlaying = !wasPlaying)
        if (context is Activity) {
            applyPictureInPictureParams(context)
        }
    }

    private fun applyPictureInPictureParams(activity: Activity) {
        if (!activity.canUsePictureInPicture()) return
        val inPictureInPicture = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInPictureInPictureMode
        // Inside the floating window the player is window-sized, so keep the full-screen rect
        // (the one the window grows back to), and only re-send params when something visible
        // changed: the launcher may move a window tucked into the screen edge back out.
        val hint = if (inPictureInPicture) lastAppliedSourceRectHint else sourceRectHint(activity)
        if (sessionState == lastAppliedSessionState && hint == lastAppliedSourceRectHint) return
        if (inPictureInPicture && sessionState.withoutBackgroundAudio() == lastAppliedSessionState?.withoutBackgroundAudio()) return
        if (runCatching { activity.setPictureInPictureParams(buildParams(activity, hint)) }.isSuccess) {
            lastAppliedSessionState = sessionState
            lastAppliedSourceRectHint = hint
        }
    }

    private fun SessionState.withoutBackgroundAudio(): SessionState = copy(backgroundAudio = false)

    private fun enterIfEligible(activity: Activity): Boolean {
        if (!activity.canUsePictureInPicture()) return false
        if (!sessionState.isActive || !sessionState.isPlaying || sessionState.backgroundAudio) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInPictureInPictureMode) return false
        return runCatching { activity.enterPictureInPictureMode(buildParams(activity)) }.getOrDefault(false)
    }

    private fun buildParams(activity: Activity, hint: Rect? = sourceRectHint(activity)): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        buildAspectRatio(sessionState.videoSize)?.let(builder::setAspectRatio)
        // Nuvio RS: where the picture sits on screen, so the system shrinks the video itself into
        // the floating window (and grows it back) instead of cutting the whole screen away.
        hint?.let(builder::setSourceRectHint)
        builder.setActions(buildActions(activity))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(
                sessionState.isActive && sessionState.isPlaying && !sessionState.backgroundAudio && !sessionState.ownWindow,
            )
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    /**
     * The video's rectangle in window coordinates: the full-screen player letterboxes the picture
     * to the floating window's aspect ratio, centred, so that is the rectangle to animate from.
     */
    private fun sourceRectHint(activity: Activity): Rect? {
        val aspect = buildAspectRatio(sessionState.videoSize) ?: return null
        val decor = activity.window?.decorView ?: return null
        val width = decor.width
        val height = decor.height
        if (width <= 0 || height <= 0 || aspect.numerator <= 0 || aspect.denominator <= 0) return null
        val ratio = aspect.numerator.toFloat() / aspect.denominator.toFloat()
        return if (ratio >= width.toFloat() / height.toFloat()) {
            val pictureHeight = (width / ratio).roundToInt().coerceIn(1, height)
            val top = (height - pictureHeight) / 2
            Rect(0, top, width, top + pictureHeight)
        } else {
            val pictureWidth = (height * ratio).roundToInt().coerceIn(1, width)
            val left = (width - pictureWidth) / 2
            Rect(left, 0, left + pictureWidth, height)
        }
    }

    private fun buildAspectRatio(videoSize: IntSize): Rational? {
        if (videoSize.width <= 0 || videoSize.height <= 0) return null

        val width = videoSize.width.coerceAtLeast(1)
        val height = videoSize.height.coerceAtLeast(1)
        val ratio = width.toDouble() / height.toDouble()

        return when {
            ratio > MaxPictureInPictureAspectRatio ->
                Rational((MaxPictureInPictureAspectRatio * 100).roundToInt(), 100)
            ratio < MinPictureInPictureAspectRatio ->
                Rational(100, (MaxPictureInPictureAspectRatio * 100).roundToInt())
            else -> Rational(width, height)
        }
    }

    private fun buildActions(activity: Activity): List<RemoteAction> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return emptyList()

        val isPlaying = sessionState.isPlaying
        val iconRes = if (isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play
        val title = if (isPlaying) pipPauseLabel else pipPlayLabel

        val flags = android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        fun action(intentAction: String, requestCode: Int, icon: Icon, label: String): RemoteAction {
            val intent = Intent(intentAction).setPackage(activity.packageName)
            val pendingIntent = android.app.PendingIntent.getBroadcast(activity, requestCode, intent, flags)
            icon.setTint(android.graphics.Color.WHITE)
            return RemoteAction(icon, label, label, pendingIntent)
        }
        // Nuvio RS: back 10 s · play/pause · forward 10 s, like PikPak's floating window.
        return listOf(
            action(PIP_ACTION_SEEK_BACK, REQUEST_CODE_SEEK_BACK, Icon.createWithResource("android", android.R.drawable.ic_media_rew), pipSeekBackLabel),
            action(PIP_ACTION_TOGGLE_PLAY_PAUSE, REQUEST_CODE_TOGGLE, Icon.createWithResource(activity, iconRes), title),
            action(PIP_ACTION_SEEK_FORWARD, REQUEST_CODE_SEEK_FORWARD, Icon.createWithResource("android", android.R.drawable.ic_media_ff), pipSeekForwardLabel),
        )
    }

    private fun clearPendingPictureInPictureExitCheck() {
        pendingPictureInPictureExitCheck?.let(mainHandler::removeCallbacks)
        pendingPictureInPictureExitCheck = null
    }

    private fun Activity.canUsePictureInPicture(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (isFinishing || isDestroyed) return false
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
        if (this is ComponentActivity && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return false
        return true
    }

    private const val REQUEST_CODE_TOGGLE = 1
    private const val REQUEST_CODE_SEEK_BACK = 2
    private const val REQUEST_CODE_SEEK_FORWARD = 3
    internal const val PIP_SEEK_STEP_MS = 10_000L
    private const val MaxPictureInPictureAspectRatio = 2.39
    private const val MinPictureInPictureAspectRatio = 1.0 / MaxPictureInPictureAspectRatio

    private val pipPlayLabel: String by lazy { runBlocking { getString(Res.string.action_play) } }
    private val pipPauseLabel: String by lazy { runBlocking { getString(Res.string.compose_action_pause) } }
    private val backgroundAudioLabel: String by lazy { runBlocking { getString(Res.string.player_background_audio_playing) } }
    private val pipSeekBackLabel: String by lazy { runBlocking { getString(Res.string.player_pip_seek_back) } }
    private val pipSeekForwardLabel: String by lazy { runBlocking { getString(Res.string.player_pip_seek_forward) } }
    private val floatingPermissionLabel: String by lazy { runBlocking { getString(Res.string.player_floating_permission) } }
}

internal class PipRemoteActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        when (intent?.action) {
            PIP_ACTION_TOGGLE_PLAY_PAUSE -> PlayerPictureInPictureManager.toggleViaRemoteAction(context)
            PIP_ACTION_SEEK_BACK -> PlayerPictureInPictureManager.seekViaRemoteAction(-PlayerPictureInPictureManager.PIP_SEEK_STEP_MS)
            PIP_ACTION_SEEK_FORWARD -> PlayerPictureInPictureManager.seekViaRemoteAction(PlayerPictureInPictureManager.PIP_SEEK_STEP_MS)
        }
    }

    companion object {
        fun register(context: Context): PipRemoteActionReceiver {
            val filter = IntentFilter(PIP_ACTION_TOGGLE_PLAY_PAUSE).apply {
                addAction(PIP_ACTION_SEEK_BACK)
                addAction(PIP_ACTION_SEEK_FORWARD)
            }
            val receiver = PipRemoteActionReceiver()
            androidx.core.content.ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
            return receiver
        }
    }
}
