package com.nuvio.app.features.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.features.device.DeviceVideo
import com.nuvio.app.features.device.DeviceVideoLibrary
import com.nuvio.app.features.device.DeviceVideoRepository
import com.nuvio.app.features.device.DeviceVideoThumbnail
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.detail_btn_play
import nuvio.composeapp.generated.resources.player_device_next_cancel
import nuvio.composeapp.generated.resources.player_device_next_countdown
import nuvio.composeapp.generated.resources.player_device_next_label
import org.jetbrains.compose.resources.stringResource

/**
 * SAMplay: «play the next video» for a video on this device, as SAMplay v32 did. When one ends,
 * the next video in its folder (the library's order, hidden ones skipped) plays after a short
 * countdown, in the same player screen. One per player screen.
 */
internal class DeviceVideoNextState {
    /** What plays when the countdown ends; null when nothing is coming. */
    var upNext by mutableStateOf<DeviceVideo?>(null)
    var countdownSec by mutableStateOf<Int?>(null)
    /** The viewer cancelled «next» at the end of this file. */
    var cancelledUri by mutableStateOf<String?>(null)
    /** The title of the file playing now, once the player moved on from the one it opened. */
    var title by mutableStateOf<String?>(null)

    fun clear() {
        upNext = null
        countdownSec = null
    }
}

private const val DEVICE_NEXT_COUNTDOWN_SEC = 5

@Composable
internal fun PlayerScreenRuntime.BindDeviceVideoNextEffects() {
    val ended = isDeviceVideo &&
        playbackSnapshot.isEnded &&
        playbackSnapshotKey == activePlaybackKey &&
        initialLoadCompleted &&
        errorMessage == null
    val sourceUrl = activeSourceUrl
    val cancelledUri = deviceNext.cancelledUri
    LaunchedEffect(ended, sourceUrl, cancelledUri) {
        deviceNext.clear()
        if (!ended) {
            // Played again after a cancel (rewound, or replayed): offer «next» at its end again.
            if (cancelledUri != null && cancelledUri == sourceUrl) deviceNext.cancelledUri = null
            return@LaunchedEffect
        }
        if (cancelledUri == sourceUrl || isPartyControlLocked) return@LaunchedEffect
        val next = DeviceVideoRepository.nextVideoAfter(sourceUrl) ?: return@LaunchedEffect
        deviceNext.upNext = next
        for (seconds in DEVICE_NEXT_COUNTDOWN_SEC downTo 1) {
            deviceNext.countdownSec = seconds
            delay(1_000L)
        }
        switchToDeviceVideo(next)
    }
}

/**
 * Plays [video] in this player screen. The one that played is saved first (finished, when it
 * ended), and [video] starts where the viewer stopped in it before.
 */
internal fun PlayerScreenRuntime.switchToDeviceVideo(video: DeviceVideo) {
    if (video.uri.isBlank() || video.uri == activeSourceUrl) return
    deviceNext.clear()
    flushWatchProgress()
    val nextTitle = DeviceVideoLibrary.title(video.name).ifBlank { video.name }
    deviceNext.title = nextTitle
    externalSubtitles = emptyList()
    activeSourceUrl = video.uri
    activeSourceAudioUrl = null
    activeSourceHeaders = emptyMap()
    activeSourceResponseHeaders = emptyMap()
    activeStreamType = null
    activeSourceIdentityKey = "url:${video.uri}"
    activeSourceFilename = video.name.takeIf(String::isNotBlank)
    activeStreamTitle = nextTitle
    activeStreamSubtitle = video.folderName.takeIf(String::isNotBlank)
    activeStreamFilename = video.name.takeIf(String::isNotBlank) ?: sourceFilenameFromUrl(video.uri)
    activeStream = null
    activeSubtitleExtras = null
    currentStreamFilename = null
    currentStreamInfoHash = null
    activeInitialPositionMs = DeviceVideoRepository.resumePositionMs(video.uri)
    activeInitialProgressFraction = null
    controlsVisible = true
}

/** The «Next video» card, where Nuvio shows its next-episode card. */
@Composable
internal fun BoxScope.DeviceVideoNextOverlay(runtime: PlayerScreenRuntime) {
    if (!runtime.isDeviceVideo) return
    val isInPip = rememberIsInPictureInPicture()
    runtime.run {
        if (playerControlsLocked || isInPip) return@run
        DeviceVideoUpNextCard(
            video = deviceNext.upNext,
            countdownSec = deviceNext.countdownSec,
            onPlayNow = { deviceNext.upNext?.let { switchToDeviceVideo(it) } },
            onCancel = {
                deviceNext.cancelledUri = activeSourceUrl
                deviceNext.clear()
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = sliderEdgePadding, bottom = overlayBottomPadding),
        )
    }
}

@Composable
private fun DeviceVideoUpNextCard(
    video: DeviceVideo?,
    countdownSec: Int?,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Kept while the card slides away.
    var lastVideo by remember { mutableStateOf<DeviceVideo?>(null) }
    LaunchedEffect(video) { if (video != null) lastVideo = video }
    PlatformBackHandler(enabled = video != null, onBack = onCancel)

    AnimatedVisibility(
        visible = video != null,
        enter = slideInHorizontally(animationSpec = tween(260), initialOffsetX = { it / 2 }) +
            fadeIn(animationSpec = tween(220)),
        exit = slideOutHorizontally(animationSpec = tween(200), targetOffsetX = { it / 2 }) +
            fadeOut(animationSpec = tween(160)),
        modifier = modifier,
    ) {
        val item = video ?: lastVideo ?: return@AnimatedVisibility
        val shape = RoundedCornerShape(16.dp)
        Row(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .clip(shape)
                .background(Color(0xFF191919).copy(alpha = 0.89f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
                .clickable(onClick = onPlayNow)
                .padding(start = 9.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 78.dp, height = 44.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Movie,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp),
                )
                DeviceVideoThumbnail(video = item, modifier = Modifier.fillMaxSize())
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(
                    text = stringResource(Res.string.player_device_next_label),
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = DeviceVideoLibrary.title(item.name).ifBlank { item.name },
                    color = Color.White,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                )
                if (countdownSec != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(Res.string.player_device_next_countdown, countdownSec),
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = 10.sp,
                        maxLines = 1,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .padding(start = 4.dp)
                    .clip(CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = stringResource(Res.string.detail_btn_play),
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 3.dp),
                )
            }
            Box(
                modifier = Modifier
                    .padding(start = 2.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(Res.string.player_device_next_cancel),
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
