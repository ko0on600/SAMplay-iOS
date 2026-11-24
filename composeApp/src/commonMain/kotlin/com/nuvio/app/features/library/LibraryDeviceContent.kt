package com.nuvio.app.features.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioDropdownChip
import com.nuvio.app.core.ui.NuvioDropdownOption
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.device.DeviceVideo
import com.nuvio.app.features.device.DeviceVideoAccess
import com.nuvio.app.features.device.DeviceVideoFolder
import com.nuvio.app.features.device.DeviceVideoHidden
import com.nuvio.app.features.device.DeviceVideoLibrary
import com.nuvio.app.features.device.DeviceVideoPosition
import com.nuvio.app.features.device.DeviceVideoRepository
import com.nuvio.app.features.device.DeviceVideoSort
import com.nuvio.app.features.device.DeviceVideoThumbnail
import com.nuvio.app.features.device.DeviceVideosUiState
import com.nuvio.app.features.home.components.HomeEmptyStateCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_search_clear
import nuvio.composeapp.generated.resources.device_videos_all_folders
import nuvio.composeapp.generated.resources.device_videos_all_hidden_message
import nuvio.composeapp.generated.resources.device_videos_all_hidden_title
import nuvio.composeapp.generated.resources.device_videos_allow
import nuvio.composeapp.generated.resources.device_videos_autoplay_next
import nuvio.composeapp.generated.resources.device_videos_autoplay_next_summary
import nuvio.composeapp.generated.resources.device_videos_choose_folders
import nuvio.composeapp.generated.resources.device_videos_choose_folders_hint
import nuvio.composeapp.generated.resources.device_videos_continue
import nuvio.composeapp.generated.resources.device_videos_count
import nuvio.composeapp.generated.resources.device_videos_empty_message
import nuvio.composeapp.generated.resources.device_videos_empty_title
import nuvio.composeapp.generated.resources.device_videos_folders
import nuvio.composeapp.generated.resources.device_videos_folders_all_shown
import nuvio.composeapp.generated.resources.device_videos_hidden_count
import nuvio.composeapp.generated.resources.device_videos_hidden_videos
import nuvio.composeapp.generated.resources.device_videos_hidden_videos_empty
import nuvio.composeapp.generated.resources.device_videos_hidden_videos_hint
import nuvio.composeapp.generated.resources.device_videos_hide_folder
import nuvio.composeapp.generated.resources.device_videos_hide_video
import nuvio.composeapp.generated.resources.device_videos_hide_video_folder
import nuvio.composeapp.generated.resources.device_videos_hiding_note
import nuvio.composeapp.generated.resources.device_videos_item_hidden
import nuvio.composeapp.generated.resources.device_videos_left
import nuvio.composeapp.generated.resources.device_videos_no_matches
import nuvio.composeapp.generated.resources.device_videos_none
import nuvio.composeapp.generated.resources.device_videos_open_file
import nuvio.composeapp.generated.resources.device_videos_open_settings
import nuvio.composeapp.generated.resources.device_videos_options
import nuvio.composeapp.generated.resources.device_videos_options_title
import nuvio.composeapp.generated.resources.device_videos_partial_manage
import nuvio.composeapp.generated.resources.device_videos_partial_message
import nuvio.composeapp.generated.resources.device_videos_permission_message
import nuvio.composeapp.generated.resources.device_videos_permission_title
import nuvio.composeapp.generated.resources.device_videos_refresh
import nuvio.composeapp.generated.resources.device_videos_remove_continue
import nuvio.composeapp.generated.resources.device_videos_search
import nuvio.composeapp.generated.resources.device_videos_short_1m
import nuvio.composeapp.generated.resources.device_videos_short_30s
import nuvio.composeapp.generated.resources.device_videos_short_3m
import nuvio.composeapp.generated.resources.device_videos_short_5m
import nuvio.composeapp.generated.resources.device_videos_short_clips
import nuvio.composeapp.generated.resources.device_videos_short_off
import nuvio.composeapp.generated.resources.device_videos_show_all
import nuvio.composeapp.generated.resources.device_videos_sort
import nuvio.composeapp.generated.resources.device_videos_sort_largest
import nuvio.composeapp.generated.resources.device_videos_sort_longest
import nuvio.composeapp.generated.resources.device_videos_sort_name_asc
import nuvio.composeapp.generated.resources.device_videos_sort_name_desc
import nuvio.composeapp.generated.resources.device_videos_sort_newest
import nuvio.composeapp.generated.resources.device_videos_sort_oldest
import nuvio.composeapp.generated.resources.device_videos_sort_shortest
import nuvio.composeapp.generated.resources.device_videos_sort_smallest
import nuvio.composeapp.generated.resources.device_videos_undo
import nuvio.composeapp.generated.resources.device_videos_watched
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * SAMplay: the device library's menus and sheets (long press, «Library options»). Kept by the
 * library screen so a sheet stays open while the list scrolls under it.
 */
internal class DeviceLibraryMenus {
    /** The long-pressed folder, or video. */
    var folder by mutableStateOf<DeviceVideoFolder?>(null)
    var video by mutableStateOf<DeviceVideo?>(null)
    var showOptions by mutableStateOf(false)
    var showFolderChooser by mutableStateOf(false)
    var showHiddenVideos by mutableStateOf(false)
    var showShortClips by mutableStateOf(false)
}

/**
 * SAMplay: Library › On this device. The phone's own videos by folder, a «Continue watching»
 * row for files started here, sort and search, and «Open file» for one file anywhere. A long
 * press hides a folder or a video; «Library options» picks the folders, brings hidden videos
 * back, leaves out short clips and turns «play the next video» on or off.
 */
internal fun LazyListScope.deviceLibraryContent(
    state: DeviceVideosUiState,
    folders: List<DeviceVideoFolder>,
    access: DeviceVideoAccess,
    selectedFolderId: String?,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onFolderSelected: (String?) -> Unit,
    menus: DeviceLibraryMenus,
) {
    val continueWatching = state.continueWatching
    if (!state.hasPermission) {
        item(key = "device-permission") {
            DeviceVideoPermissionCard(access = access, modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (continueWatching.isNotEmpty()) deviceContinueRow(continueWatching)
        return
    }

    item(key = "device-toolbar") {
        DeviceVideoToolbar(
            sort = state.sort,
            isLoading = state.isLoading,
            access = access,
            onOptions = { menus.showOptions = true },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
    if (state.isPartialAccess) {
        item(key = "device-partial-access") {
            DeviceVideoPartialAccessBar(
                access = access,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        }
    }
    // An «Undo» offer that ran out while the viewer was away (watching, say) is not shown again.
    state.lastHidden?.takeIf { DeviceVideoRepository.undoTimeLeftMs(it) > 0L }?.let { hidden ->
        item(key = "device-undo") {
            DeviceVideoUndoBar(
                hidden = hidden,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        }
    }
    if (state.videos.isNotEmpty()) {
        item(key = "device-search") {
            DeviceVideoSearchField(
                query = searchQuery,
                onQueryChange = onSearchQueryChange,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }

    when {
        !state.isLoaded || (state.isLoading && state.videos.isEmpty()) -> {
            items(count = 4, key = { "device-skeleton-$it" }) {
                DeviceVideoSkeletonRow(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
            }
        }

        state.videos.isEmpty() && state.allVideos.isNotEmpty() -> {
            if (continueWatching.isNotEmpty()) deviceContinueRow(continueWatching)
            item(key = "device-all-hidden") {
                HomeEmptyStateCard(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    title = stringResource(Res.string.device_videos_all_hidden_title),
                    message = stringResource(Res.string.device_videos_all_hidden_message),
                    actionLabel = stringResource(Res.string.device_videos_options),
                    onActionClick = { menus.showOptions = true },
                )
            }
        }

        state.videos.isEmpty() -> {
            if (continueWatching.isNotEmpty()) deviceContinueRow(continueWatching)
            item(key = "device-empty") {
                HomeEmptyStateCard(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    title = stringResource(Res.string.device_videos_empty_title),
                    message = stringResource(Res.string.device_videos_empty_message),
                    actionLabel = access.openFile?.let { stringResource(Res.string.device_videos_open_file) },
                    onActionClick = access.openFile,
                )
            }
        }

        searchQuery.isNotBlank() -> {
            val matches = DeviceVideoLibrary.sortVideos(DeviceVideoLibrary.search(state.videos, searchQuery), state.sort)
            if (matches.isEmpty()) {
                item(key = "device-no-matches") {
                    HomeEmptyStateCard(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        title = stringResource(Res.string.device_videos_no_matches),
                        message = "",
                    )
                }
            } else {
                items(items = matches, key = { "device-match:${it.uri}" }) { video ->
                    DeviceVideoRow(
                        video = video,
                        position = state.positions[video.uri],
                        showFolder = true,
                        onLongClick = { menus.video = video },
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                    )
                }
            }
        }

        else -> {
            val folder = folders.firstOrNull { it.id == selectedFolderId }
            if (folder != null) {
                item(key = "device-folder-header:${folder.id}") {
                    DeviceFolderHeader(
                        folder = folder,
                        onBack = { onFolderSelected(null) },
                        modifier = Modifier.padding(start = 8.dp, end = 16.dp, bottom = 8.dp),
                    )
                }
                items(items = folder.videos, key = { "device-video:${it.uri}" }) { video ->
                    DeviceVideoRow(
                        video = video,
                        position = state.positions[video.uri],
                        showFolder = false,
                        onLongClick = { menus.video = video },
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                    )
                }
            } else {
                if (continueWatching.isNotEmpty()) deviceContinueRow(continueWatching)
                item(key = "device-folders-title") {
                    DeviceSectionTitle(
                        text = stringResource(Res.string.device_videos_folders),
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
                    )
                }
                items(items = folders, key = { "device-folder:${it.id}" }) { item ->
                    DeviceFolderRow(
                        folder = item,
                        onClick = { onFolderSelected(item.id) },
                        onLongClick = { menus.folder = item },
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                    )
                }
            }
        }
    }
    item(key = "device-bottom-space") { Spacer(modifier = Modifier.height(16.dp)) }
}

private fun LazyListScope.deviceContinueRow(positions: List<DeviceVideoPosition>) {
    item(key = "device-continue-title") {
        DeviceSectionTitle(
            text = stringResource(Res.string.device_videos_continue),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
        )
    }
    item(key = "device-continue-row") {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(bottom = 18.dp),
        ) {
            items(items = positions, key = { it.uri }) { position ->
                DeviceContinueCard(position = position)
            }
        }
    }
}

@Composable
private fun DeviceSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onBackground,
    )
}

@Composable
private fun DeviceVideoPermissionCard(access: DeviceVideoAccess, modifier: Modifier = Modifier) {
    NuvioSurfaceCard(modifier = modifier) {
        Icon(
            imageVector = Icons.Rounded.FolderOpen,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(36.dp),
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(Res.string.device_videos_permission_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(Res.string.device_videos_permission_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        NuvioPrimaryButton(
            text = stringResource(Res.string.device_videos_allow),
            onClick = access.requestPermission,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            access.openFile?.let { openFile ->
                TextButton(onClick = openFile) { Text(stringResource(Res.string.device_videos_open_file)) }
            }
            TextButton(onClick = access.openSettings) { Text(stringResource(Res.string.device_videos_open_settings)) }
        }
    }
}

/** Limited access (Android 14+): the system's dialog lets the viewer pick more, or allow all. */
@Composable
private fun DeviceVideoPartialAccessBar(access: DeviceVideoAccess, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.VideoLibrary,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = stringResource(Res.string.device_videos_partial_message),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = access.requestPermission) {
                Text(stringResource(Res.string.device_videos_partial_manage))
            }
        }
    }
}

@Composable
private fun DeviceVideoToolbar(
    sort: DeviceVideoSort,
    isLoading: Boolean,
    access: DeviceVideoAccess,
    onOptions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = DeviceVideoSort.entries.map { NuvioDropdownOption(key = it.name, label = stringResource(it.label())) }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NuvioDropdownChip(
            title = stringResource(Res.string.device_videos_sort),
            label = stringResource(sort.label()),
            selectedKey = sort.name,
            options = options,
            onSelected = { option ->
                DeviceVideoSort.entries.firstOrNull { it.name == option.key }?.let(DeviceVideoRepository::setSort)
            },
        )
        access.openFile?.let { openFile ->
            Surface(
                modifier = Modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = openFile),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(Res.string.device_videos_open_file),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = onOptions) {
            Icon(
                imageVector = Icons.Rounded.Tune,
                contentDescription = stringResource(Res.string.device_videos_options),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = DeviceVideoRepository::refresh, enabled = !isLoading) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = stringResource(Res.string.device_videos_refresh),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeviceVideoSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        placeholder = { Text(stringResource(Res.string.device_videos_search)) },
        leadingIcon = { Icon(imageVector = Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(Res.string.compose_search_clear),
                    )
                }
            }
        },
    )
}

@Composable
private fun DeviceFolderHeader(folder: DeviceVideoFolder, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(Res.string.device_videos_all_folders),
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = folder.name,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = folderDetails(folder),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun folderDetails(folder: DeviceVideoFolder): String =
    listOf(
        pluralStringResource(Res.plurals.device_videos_count, folder.videos.size, folder.videos.size),
        DeviceVideoLibrary.formatSize(folder.totalBytes),
    ).filter(String::isNotBlank).joinToString(" • ", transform = ::isolated)

/**
 * Each part keeps its own direction: «44 MB» or «480p» inside an Arabic line would otherwise
 * show as «MB 44».
 */
private fun isolated(part: String): String = "\u2068$part\u2069"

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceFolderRow(
    folder: DeviceVideoFolder,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            )
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(112.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(32.dp),
            )
            // The folder's first video as its cover.
            folder.videos.firstOrNull()?.let { cover ->
                DeviceVideoThumbnail(video = cover, modifier = Modifier.fillMaxSize())
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))),
                )
                Icon(
                    imageVector = Icons.Rounded.Folder,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).size(18.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = folder.name,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = folderDetails(folder),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceVideoRow(
    video: DeviceVideo,
    position: DeviceVideoPosition?,
    showFolder: Boolean,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val watched = DeviceVideoLibrary.isWatched(position)
    val progress = DeviceVideoLibrary.progressFraction(position)?.takeIf { !watched }
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(
                onClick = { DeviceVideoRepository.requestPlay(video) },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            )
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DeviceVideoPicture(
            video = video,
            durationMs = video.durationMs.takeIf { it > 0L } ?: position?.durationMs ?: 0L,
            progress = progress,
            modifier = Modifier.width(136.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = DeviceVideoLibrary.title(video.name),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val details = listOfNotNull(
                video.folderName.takeIf { showFolder && it.isNotBlank() },
                DeviceVideoLibrary.qualityLabel(video.width, video.height),
                DeviceVideoLibrary.formatSize(video.sizeBytes).ifBlank { null },
            ).joinToString(" • ", transform = ::isolated)
            if (details.isNotBlank()) {
                Text(
                    text = details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (watched) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = stringResource(Res.string.device_videos_watched),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else if (progress != null && position != null) {
                remainingText(position)?.let { left ->
                    Text(
                        text = left,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun remainingText(position: DeviceVideoPosition): String? {
    val leftMs = position.durationMs - position.positionMs
    if (position.durationMs <= 0L || leftMs <= 0L) return null
    return stringResource(Res.string.device_videos_left, DeviceVideoLibrary.formatDuration(leftMs))
}

@Composable
private fun DeviceContinueCard(position: DeviceVideoPosition) {
    val video = DeviceVideoRepository.videoFor(position)
    Column(
        modifier = Modifier
            .width(188.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable { DeviceVideoRepository.requestPlay(video) },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            DeviceVideoPicture(
                video = video,
                durationMs = 0L,
                progress = DeviceVideoLibrary.progressFraction(position),
                modifier = Modifier.fillMaxWidth(),
                showPlay = true,
            )
            IconButton(
                onClick = { DeviceVideoRepository.clearPosition(position.uri) },
                modifier = Modifier.align(Alignment.TopEnd).size(32.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(Res.string.device_videos_remove_continue),
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        Text(
            text = DeviceVideoLibrary.title(video.name).ifBlank { position.title },
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        remainingText(position)?.let { left ->
            Text(
                text = left,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

/** 16:9 picture: the frame, the length in a corner, and how far the viewer got. */
@Composable
private fun DeviceVideoPicture(
    video: DeviceVideo,
    durationMs: Long,
    progress: Float?,
    modifier: Modifier = Modifier,
    showPlay: Boolean = false,
) {
    Box(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Movie,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(28.dp),
        )
        DeviceVideoThumbnail(video = video, modifier = Modifier.fillMaxSize())
        if (showPlay) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        val length = DeviceVideoLibrary.formatDuration(durationMs)
        if (length.isNotEmpty()) {
            Text(
                text = length,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
        if (progress != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color.White.copy(alpha = 0.25f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

@Composable
private fun DeviceVideoSkeletonRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(112.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
    }
}

/** «Name is hidden · Undo», for a few seconds after a long press hid something. */
@Composable
private fun DeviceVideoUndoBar(hidden: DeviceVideoHidden, modifier: Modifier = Modifier) {
    LaunchedEffect(hidden.id) {
        delay(DeviceVideoRepository.undoTimeLeftMs(hidden))
        DeviceVideoRepository.dismissUndo(hidden.id)
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.VisibilityOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = stringResource(Res.string.device_videos_item_hidden, isolated(hidden.name)),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = DeviceVideoRepository::undoHide) {
                Text(stringResource(Res.string.device_videos_undo))
            }
        }
    }
}

/** The long-press menus and «Library options», shown over the library while one is open. */
@Composable
internal fun DeviceLibrarySheets(state: DeviceVideosUiState, menus: DeviceLibraryMenus) {
    menus.folder?.let { folder ->
        DeviceLibrarySheet(title = folder.name, onDismiss = { menus.folder = null }) { act ->
            NuvioBottomSheetActionRow(
                title = stringResource(Res.string.device_videos_hide_folder),
                icon = Icons.Rounded.VisibilityOff,
                onClick = { act { DeviceVideoRepository.hideFolder(folder.id, folder.name) } },
            )
            NuvioBottomSheetActionRow(
                title = stringResource(Res.string.device_videos_choose_folders),
                icon = Icons.Rounded.FolderOpen,
                onClick = { act { menus.showFolderChooser = true } },
            )
        }
    }
    menus.video?.let { video ->
        DeviceLibrarySheet(title = DeviceVideoLibrary.title(video.name), onDismiss = { menus.video = null }) { act ->
            NuvioBottomSheetActionRow(
                title = stringResource(Res.string.device_videos_hide_video),
                icon = Icons.Rounded.VisibilityOff,
                onClick = { act { DeviceVideoRepository.hideVideo(video) } },
            )
            if (video.folderId.isNotBlank()) {
                val folderName = video.folderName.ifBlank { video.folderId }
                NuvioBottomSheetActionRow(
                    title = stringResource(Res.string.device_videos_hide_video_folder, isolated(folderName)),
                    icon = Icons.Rounded.Folder,
                    onClick = { act { DeviceVideoRepository.hideFolder(video.folderId, folderName) } },
                )
            }
        }
    }
    if (menus.showOptions) {
        DeviceLibraryOptionsSheet(state = state, menus = menus)
    }
    if (menus.showFolderChooser) {
        DeviceFolderChooserSheet(state = state, onDismiss = { menus.showFolderChooser = false })
    }
    if (menus.showHiddenVideos) {
        DeviceHiddenVideosSheet(state = state, onDismiss = { menus.showHiddenVideos = false })
    }
    if (menus.showShortClips) {
        DeviceLibrarySheet(
            title = stringResource(Res.string.device_videos_short_clips),
            onDismiss = { menus.showShortClips = false },
        ) { act ->
            DeviceVideoLibrary.SHORT_CLIP_CHOICES_SEC.forEach { seconds ->
                NuvioBottomSheetActionRow(
                    title = stringResource(shortClipLabel(seconds)),
                    selected = seconds == state.minDurationSec,
                    onClick = { act { DeviceVideoRepository.setMinDurationSec(seconds) } },
                )
            }
        }
    }
}

/** A sheet with a title; [content] gets `act`, which runs an action and then closes the sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceLibrarySheet(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable (act: (() -> Unit) -> Unit) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val act: (() -> Unit) -> Unit = { action ->
        action()
        scope.launch { dismissNuvioBottomSheet(sheetState, onDismiss) }
    }
    NuvioModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        DeviceSheetTitle(title)
        NuvioBottomSheetDivider()
        Spacer(Modifier.height(NuvioTokens.Space.s4))
        content(act)
        Spacer(Modifier.height(NuvioTokens.Space.s16))
    }
}

@Composable
private fun DeviceLibraryOptionsSheet(state: DeviceVideosUiState, menus: DeviceLibraryMenus) {
    val tokens = MaterialTheme.nuvio
    val presentFolderIds = remember(state.allVideos) { state.allVideos.mapTo(HashSet()) { it.folderId } }
    val hiddenFolderCount = state.hiddenFolders.keys.count { it in presentFolderIds }
    DeviceLibrarySheet(
        title = stringResource(Res.string.device_videos_options_title),
        onDismiss = { menus.showOptions = false },
    ) { act ->
        NuvioBottomSheetActionRow(
            title = stringResource(Res.string.device_videos_choose_folders),
            icon = Icons.Rounded.FolderOpen,
            onClick = { act { menus.showFolderChooser = true } },
            trailingContent = {
                DeviceSheetValue(
                    if (hiddenFolderCount == 0) {
                        stringResource(Res.string.device_videos_folders_all_shown)
                    } else {
                        pluralStringResource(Res.plurals.device_videos_hidden_count, hiddenFolderCount, hiddenFolderCount)
                    },
                )
            },
        )
        NuvioBottomSheetActionRow(
            title = stringResource(Res.string.device_videos_hidden_videos),
            icon = Icons.Rounded.VisibilityOff,
            onClick = { act { menus.showHiddenVideos = true } },
            trailingContent = {
                DeviceSheetValue(
                    if (state.hiddenVideos.isEmpty()) {
                        stringResource(Res.string.device_videos_none)
                    } else {
                        pluralStringResource(Res.plurals.device_videos_hidden_count, state.hiddenVideos.size, state.hiddenVideos.size)
                    },
                )
            },
        )
        NuvioBottomSheetActionRow(
            title = stringResource(Res.string.device_videos_short_clips),
            icon = Icons.Rounded.Timer,
            onClick = { act { menus.showShortClips = true } },
            trailingContent = { DeviceSheetValue(stringResource(shortClipLabel(state.minDurationSec))) },
        )
        NuvioBottomSheetActionRow(
            title = stringResource(Res.string.device_videos_autoplay_next),
            icon = Icons.Rounded.SkipNext,
            onClick = { DeviceVideoRepository.setAutoplayNext(!state.autoplayNext) },
            trailingContent = { Switch(checked = state.autoplayNext, onCheckedChange = null) },
        )
        Text(
            text = stringResource(Res.string.device_videos_autoplay_next_summary),
            modifier = Modifier.padding(start = 66.dp, end = tokens.spacing.screenHorizontal),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
        )
        NuvioBottomSheetDivider(Modifier.padding(top = NuvioTokens.Space.s12))
        Text(
            text = stringResource(Res.string.device_videos_hiding_note),
            modifier = Modifier.padding(horizontal = tokens.spacing.screenHorizontal, vertical = NuvioTokens.Space.s12),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
        )
    }
}

/** Every folder on the device, ticked when it shows; a tap shows or hides it at once. */
@Composable
private fun DeviceFolderChooserSheet(state: DeviceVideosUiState, onDismiss: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val folders = remember(state.allVideos) { DeviceVideoLibrary.folders(state.allVideos, DeviceVideoSort.NameAscending) }
    DeviceLibrarySheet(title = stringResource(Res.string.device_videos_choose_folders), onDismiss = onDismiss) {
        Text(
            text = stringResource(Res.string.device_videos_choose_folders_hint),
            modifier = Modifier.padding(horizontal = tokens.spacing.screenHorizontal, vertical = NuvioTokens.Space.s4),
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textMuted,
        )
        Column(
            modifier = Modifier
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            folders.forEach { folder ->
                val shown = folder.id !in state.hiddenFolders
                NuvioBottomSheetActionRow(
                    title = folder.name,
                    onClick = { DeviceVideoRepository.setFolderShown(folder.id, folder.name, !shown) },
                    trailingContent = {
                        DeviceSheetValue(isolated(folder.videos.size.toString()))
                        Checkbox(checked = shown, onCheckedChange = null)
                    },
                )
            }
            // Always there (dimmed when every folder shows), so ticking never moves the rows.
            val anyHidden = folders.any { it.id in state.hiddenFolders }
            NuvioBottomSheetActionRow(
                title = stringResource(Res.string.device_videos_show_all),
                icon = Icons.Rounded.Visibility,
                onClick = { if (anyHidden) DeviceVideoRepository.showAllFolders() },
                modifier = Modifier.alpha(if (anyHidden) 1f else DISABLED_ROW_ALPHA),
            )
        }
    }
}

private const val DISABLED_ROW_ALPHA = 0.38f

/**
 * The videos that were hidden when the sheet opened, ticked when they show; a tap shows one in
 * the library again (or hides it again). The list stays put while the sheet is open.
 */
@Composable
private fun DeviceHiddenVideosSheet(state: DeviceVideosUiState, onDismiss: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val listed = remember {
        state.hiddenVideos.entries
            .map { it.key to it.value }
            .sortedWith { a, b -> DeviceVideoLibrary.naturalOrder.compare(a.second, b.second) }
    }
    DeviceLibrarySheet(title = stringResource(Res.string.device_videos_hidden_videos), onDismiss = onDismiss) {
        Text(
            text = stringResource(
                if (listed.isEmpty()) Res.string.device_videos_hidden_videos_empty else Res.string.device_videos_hidden_videos_hint,
            ),
            modifier = Modifier.padding(horizontal = tokens.spacing.screenHorizontal, vertical = NuvioTokens.Space.s4),
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textMuted,
        )
        if (listed.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                listed.forEach { (uri, name) ->
                    val shown = uri !in state.hiddenVideos
                    NuvioBottomSheetActionRow(
                        title = DeviceVideoLibrary.title(name).ifBlank { uri },
                        onClick = { DeviceVideoRepository.setVideoHidden(uri, name, hidden = shown) },
                        trailingContent = { Checkbox(checked = shown, onCheckedChange = null) },
                    )
                }
                if (listed.size > 1) {
                    val stillHidden = listed.map { it.first }.filter { it in state.hiddenVideos }
                    NuvioBottomSheetActionRow(
                        title = stringResource(Res.string.device_videos_show_all),
                        icon = Icons.Rounded.Visibility,
                        onClick = { if (stillHidden.isNotEmpty()) DeviceVideoRepository.showVideos(stillHidden) },
                        modifier = Modifier.alpha(if (stillHidden.isNotEmpty()) 1f else DISABLED_ROW_ALPHA),
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceSheetTitle(title: String) {
    val tokens = MaterialTheme.nuvio
    Text(
        text = title,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.spacing.screenHorizontal, vertical = NuvioTokens.Space.s8),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = tokens.colors.textPrimary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun DeviceSheetValue(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.nuvio.colors.textMuted,
        maxLines = 1,
    )
}

private fun shortClipLabel(seconds: Int): StringResource = when {
    seconds <= 0 -> Res.string.device_videos_short_off
    seconds <= 30 -> Res.string.device_videos_short_30s
    seconds <= 60 -> Res.string.device_videos_short_1m
    seconds <= 180 -> Res.string.device_videos_short_3m
    else -> Res.string.device_videos_short_5m
}

private fun DeviceVideoSort.label(): StringResource = when (this) {
    DeviceVideoSort.NameAscending -> Res.string.device_videos_sort_name_asc
    DeviceVideoSort.NameDescending -> Res.string.device_videos_sort_name_desc
    DeviceVideoSort.Newest -> Res.string.device_videos_sort_newest
    DeviceVideoSort.Oldest -> Res.string.device_videos_sort_oldest
    DeviceVideoSort.Largest -> Res.string.device_videos_sort_largest
    DeviceVideoSort.Smallest -> Res.string.device_videos_sort_smallest
    DeviceVideoSort.Longest -> Res.string.device_videos_sort_longest
    DeviceVideoSort.Shortest -> Res.string.device_videos_sort_shortest
}
