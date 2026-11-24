package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.currentRoute
import platform.Foundation.NSOperationQueue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import platform.AVFAudio.outputVolume
import platform.Foundation.NSNotificationCenter
import platform.MediaPlayer.MPVolumeView
import platform.UIKit.UIApplication
import platform.UIKit.UIControlEventValueChanged
import platform.UIKit.UIScreen
import platform.UIKit.UISlider

private const val lockPlayerToLandscapeNotification = "NuvioPlayerLockLandscape"
private const val unlockPlayerOrientationNotification = "NuvioPlayerUnlockOrientation"
private var playerLandscapeLockCount = 0

@Composable
actual fun LockPlayerToLandscape() {
    DisposableEffect(Unit) {
        playerLandscapeLockCount += 1
        if (playerLandscapeLockCount == 1) {
            NSNotificationCenter.defaultCenter.postNotificationName(
                lockPlayerToLandscapeNotification,
                null,
            )
        }

        onDispose {
            playerLandscapeLockCount -= 1
            if (playerLandscapeLockCount == 0) {
                NSNotificationCenter.defaultCenter.postNotificationName(
                    unlockPlayerOrientationNotification,
                    null,
                )
            }
        }
    }
}

@Composable
actual fun FullscreenPlayerDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            usePlatformInsets = false,
            useSoftwareKeyboardInset = false,
        ),
        content = content,
    )
}

@Composable
actual fun HidePlayerSystemBars() = Unit

@Composable
actual fun EnterImmersivePlayerMode(keepScreenAwake: Boolean) {
    SideEffect {
        UIApplication.sharedApplication.setIdleTimerDisabled(keepScreenAwake)
    }

    DisposableEffect(Unit) {
        onDispose {
            UIApplication.sharedApplication.setIdleTimerDisabled(false)
        }
    }
}

@Composable
actual fun ManagePlayerPictureInPicture(
    isPlaying: Boolean,
    videoSize: IntSize,
) = Unit

@Composable
actual fun rememberIsInPictureInPicture(): Boolean = false

/**
 * The current media output (built-in speaker, AirPods, a car, AirPlay...) as a stable key, like
 * Android's "type:...|name:..." one, so the audio delay can be remembered per device. Updates on
 * route changes; null while [enabled] is false.
 */
@Composable
internal actual fun rememberAudioOutputKey(enabled: Boolean): String? {
    var outputKey by remember(enabled) { mutableStateOf(if (enabled) currentAudioOutputKey() else null) }
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
        val observer = NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVAudioSessionRouteChangeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            val key = currentAudioOutputKey()
            if (key != outputKey) outputKey = key
        }
        onDispose { NSNotificationCenter.defaultCenter.removeObserver(observer) }
    }
    return outputKey
}

internal fun currentAudioOutputKey(): String? = runCatching {
    val port = AVAudioSession.sharedInstance().currentRoute.outputs.firstOrNull() as? AVAudioSessionPortDescription
        ?: return@runCatching null
    audioOutputKeyOf(port.portType, port.portName)
}.getOrNull()

/** Same shape and cleanup as the Android key. */
internal fun audioOutputKeyOf(portType: String?, portName: String?): String {
    fun clean(v: String?) = v?.trim()?.lowercase()
        ?.replace(Regex("\\s+"), "_")
        ?.replace(Regex("[^a-z0-9._:-]"), "")
        ?.takeIf { it.isNotEmpty() } ?: "unknown"
    return "type:${clean(portType)}|name:${clean(portName)}"
}

@Composable
internal actual fun rememberPlatformPlayerGestureController(): PlayerGestureController? {
    val controller = remember { IOSPlayerGestureController() }

    DisposableEffect(controller) {
        onDispose {
            controller.restoreBrightness()
        }
    }

    return controller
}

private class IOSPlayerGestureController : PlayerGestureController {
    private val volumeView = MPVolumeView().apply {
        hidden = true
        alpha = 0.01
    }
    private val originalBrightness = UIScreen.mainScreen.brightness
    private var brightnessRestored = false

    override fun currentBrightness(): Float =
        UIScreen.mainScreen.brightness.toFloat().coerceIn(0f, 1f)

    override fun setBrightness(level: Float): Float {
        val target = level.coerceIn(0f, 1f)
        UIScreen.mainScreen.brightness = target.toDouble()
        return target
    }

    override fun currentVolume(): PlayerAudioLevel {
        val current = AVAudioSession.sharedInstance().outputVolume.coerceIn(0f, 1f)
        return PlayerAudioLevel(
            fraction = current,
            isMuted = current <= 0.001f,
        )
    }

    override fun setVolume(level: Float): PlayerAudioLevel {
        val target = level.coerceIn(0f, 1f)
        val slider = volumeView.subviews.filterIsInstance<UISlider>().firstOrNull()
            ?: return currentVolume()
        slider.value = target
        slider.sendActionsForControlEvents(UIControlEventValueChanged)
        return PlayerAudioLevel(
            fraction = target,
            isMuted = target <= 0.001f,
        )
    }

    fun restoreBrightness() {
        if (brightnessRestored) return
        brightnessRestored = true
        UIScreen.mainScreen.brightness = originalBrightness
    }
}
