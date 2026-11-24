package com.nuvio.app.features.servers.webdav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.PosterZoomOverlayAction
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.catalog.CatalogRepository
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.home.HomeRepository
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.servers.ServerItemRef
import com.nuvio.app.features.servers.ServerRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.subtitle_search_movie
import nuvio.composeapp.generated.resources.subtitle_search_series
import nuvio.composeapp.generated.resources.webdav_info_action
import nuvio.composeapp.generated.resources.webdav_info_background
import nuvio.composeapp.generated.resources.webdav_info_cancel
import nuvio.composeapp.generated.resources.webdav_info_description
import nuvio.composeapp.generated.resources.webdav_info_details
import nuvio.composeapp.generated.resources.webdav_info_failed
import nuvio.composeapp.generated.resources.webdav_info_fetch
import nuvio.composeapp.generated.resources.webdav_info_imdb
import nuvio.composeapp.generated.resources.webdav_info_link
import nuvio.composeapp.generated.resources.webdav_info_name
import nuvio.composeapp.generated.resources.webdav_info_no_results
import nuvio.composeapp.generated.resources.webdav_info_not_found
import nuvio.composeapp.generated.resources.webdav_info_pick_image
import nuvio.composeapp.generated.resources.webdav_info_poster
import nuvio.composeapp.generated.resources.webdav_info_reset
import nuvio.composeapp.generated.resources.webdav_info_save
import nuvio.composeapp.generated.resources.webdav_info_search
import nuvio.composeapp.generated.resources.webdav_info_search_action
import nuvio.composeapp.generated.resources.webdav_info_year
import nuvio.composeapp.generated.resources.webdav_kind_folder
import nuvio.composeapp.generated.resources.webdav_kind_folder_note
import nuvio.composeapp.generated.resources.webdav_kind_movie
import nuvio.composeapp.generated.resources.webdav_kind_series
import org.jetbrains.compose.resources.stringResource

/** True when [preview] is a file or folder on a WebDAV (PikPak) connection. */
internal fun isWebDavItem(preview: MetaPreview): Boolean {
    val ref = ServerItemRef.parse(preview.id) ?: return false
    return ServerRepository.connection(ref.connectionId)?.providerId == WebDavProvider.ID
}

/**
 * Lets the user set a PikPak file's or folder's poster and details: from a link (TMDB, IMDb,
 * elCinema, TheTVDB, an image), from a search by name, or typed by hand. Saved per path; tiles,
 * pages and subtitles use it from then on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WebDavInfoEditorSheet(
    preview: MetaPreview,
    onDismiss: () -> Unit,
) {
    val ref = remember(preview.id) { ServerItemRef.parse(preview.id) } ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.nuvio.colors
    val saved = remember(preview.id) { WebDavInfoOverrides.get(ref.connectionId, ref.itemId) }
    val fileName = remember(ref.itemId) { ref.itemId.trimEnd('/').substringAfterLast('/') }

    var link by remember { mutableStateOf(saved?.sourceUrl.orEmpty()) }
    var query by remember { mutableStateOf(WebDavNames.lookupTitle(fileName)) }
    var name by remember { mutableStateOf(saved?.name ?: preview.name) }
    var poster by remember { mutableStateOf(saved?.poster ?: preview.poster.orEmpty()) }
    var background by remember { mutableStateOf(saved?.background ?: preview.banner.orEmpty()) }
    var year by remember { mutableStateOf(saved?.year ?: preview.releaseInfo.orEmpty()) }
    var imdbId by remember { mutableStateOf(saved?.imdbId.orEmpty()) }
    var description by remember { mutableStateOf(saved?.description.orEmpty()) }
    var genres by remember { mutableStateOf(saved?.genres ?: preview.genres) }
    var kind by remember {
        mutableStateOf(
            when {
                saved?.isFolder == true -> EditorKind.FOLDER
                saved?.type == "series" -> EditorKind.SERIES
                saved?.type == "movie" || saved?.imdbId != null -> EditorKind.MOVIE
                ref.itemId.endsWith('/') -> EditorKind.FOLDER
                else -> EditorKind.MOVIE
            },
        )
    }
    var sourceUrl by remember { mutableStateOf(saved?.sourceUrl) }
    var results by remember { mutableStateOf<List<WebDavTitleMeta>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val notFoundText = stringResource(Res.string.webdav_info_not_found)
    val failedText = stringResource(Res.string.webdav_info_failed)
    val movieLabel = stringResource(Res.string.subtitle_search_movie)
    val seriesLabel = stringResource(Res.string.subtitle_search_series)
    val pickImage = rememberPosterImagePicker { picked -> poster = picked }

    fun fill(info: WebDavInfoOverride) {
        info.name?.let { name = it }
        info.poster?.let { poster = it }
        info.background?.let { background = it }
        info.year?.let { year = it }
        info.imdbId?.let { imdbId = it }
        info.description?.let { description = it }
        if (info.genres.isNotEmpty()) genres = info.genres
        when (info.type) {
            "series" -> kind = EditorKind.SERIES
            "movie" -> kind = EditorKind.MOVIE
        }
        info.sourceUrl?.let { sourceUrl = it }
    }

    fun runTask(block: suspend () -> Unit) {
        busy = true
        message = null
        scope.launch {
            try {
                block()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                message = failedText
            } finally {
                busy = false
            }
        }
    }

    fun close() {
        scope.launch { dismissNuvioBottomSheet(sheetState) { onDismiss() } }
    }

    fun applyEdit(value: WebDavInfoOverride?) {
        WebDavInfoOverrides.set(ref.connectionId, ref.itemId, value)
        val update: (MetaPreview) -> MetaPreview = { item ->
            if (value == null) {
                item
            } else {
                item.copy(
                    name = value.name?.ifBlank { null } ?: item.name,
                    poster = value.poster ?: item.poster,
                    banner = value.background ?: item.banner,
                    posterShape = if (value.poster != null) PosterShape.Poster else item.posterShape,
                    releaseInfo = value.year ?: item.releaseInfo,
                    genres = value.genres.ifEmpty { item.genres },
                )
            }
        }
        CatalogRepository.updateItem(preview.id, update)
        HomeRepository.updateItem(preview.id, update)
        MetaDetailsRepository.forget(preview.id)
    }

    fun store(value: WebDavInfoOverride?) {
        try {
            applyEdit(value)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            message = failedText
            return
        }
        close()
    }

    NuvioModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(72.dp)
                        .height(108.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.textPrimary.copy(alpha = 0.08f)),
                ) {
                    if (poster.isNotBlank()) {
                        AsyncImage(
                            model = poster,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize(),
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.webdav_info_action),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                    )
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (busy) NuvioLoadingIndicator()
            }
            message?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium, color = colors.textMuted) }

            // What it is: a film or a series (looked up from a link or a search), or a plain section.
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    EditorKind.MOVIE to stringResource(Res.string.webdav_kind_movie),
                    EditorKind.SERIES to stringResource(Res.string.webdav_kind_series),
                    (EditorKind.FOLDER to stringResource(Res.string.webdav_kind_folder))
                        .takeIf { ref.itemId.endsWith('/') || kind == EditorKind.FOLDER },
                ).filterNotNull().forEach { (value, label) ->
                    FilterChip(
                        selected = kind == value,
                        onClick = {
                            kind = value
                            message = null
                        },
                        label = { Text(label) },
                    )
                }
            }
            val section = kind == EditorKind.FOLDER
            if (section) {
                Text(
                    text = stringResource(Res.string.webdav_kind_folder_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }

            if (!section) {
            // From a link
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EditorField(
                    value = link,
                    onValueChange = { link = it },
                    label = stringResource(Res.string.webdav_info_link),
                    modifier = Modifier.weight(1f),
                    keyboardType = KeyboardType.Uri,
                    onDone = {
                        if (link.isNotBlank()) runTask {
                            val info = WebDavOnlineInfo.fromLink(link)
                            if (info == null) message = notFoundText else fill(info)
                        }
                    },
                )
                Button(
                    enabled = !busy && link.isNotBlank(),
                    onClick = {
                        runTask {
                            val info = WebDavOnlineInfo.fromLink(link)
                            if (info == null) message = notFoundText else fill(info)
                        }
                    },
                ) { Text(stringResource(Res.string.webdav_info_fetch)) }
            }

            // From a search by name
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EditorField(
                    value = query,
                    onValueChange = { query = it },
                    label = stringResource(Res.string.webdav_info_search),
                    modifier = Modifier.weight(1f),
                    imeAction = ImeAction.Search,
                    onDone = { if (query.isNotBlank()) runTask { results = WebDavOnlineInfo.searchTitles(query) } },
                )
                Button(
                    enabled = !busy && query.isNotBlank(),
                    onClick = { runTask { results = WebDavOnlineInfo.searchTitles(query) } },
                ) { Text(stringResource(Res.string.webdav_info_search_action)) }
            }
            results?.let { list ->
                if (list.isEmpty()) {
                    Text(stringResource(Res.string.webdav_info_no_results), color = colors.textMuted)
                } else {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        list.forEach { result ->
                            Column(
                                modifier = Modifier
                                    .width(96.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable(enabled = !busy) {
                                        runTask { fill(WebDavOnlineInfo.details(result)) }
                                    },
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                AsyncImage(
                                    model = result.poster,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .width(96.dp)
                                        .height(144.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(colors.textPrimary.copy(alpha = 0.08f)),
                                )
                                Text(
                                    text = result.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textPrimary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val kind = when (result.type) {
                                    "series" -> seriesLabel
                                    "movie" -> movieLabel
                                    else -> if (result.sourceUrl != null) "elCinema" else null
                                }
                                Text(
                                    text = listOfNotNull(kind, result.releaseInfo).joinToString(" • "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textMuted,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
            }

            // By hand
            Text(
                text = stringResource(Res.string.webdav_info_details),
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
            )
            EditorField(name, { name = it }, stringResource(Res.string.webdav_info_name))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EditorField(
                    value = poster,
                    onValueChange = { poster = it },
                    label = stringResource(Res.string.webdav_info_poster),
                    modifier = Modifier.weight(1f),
                    keyboardType = KeyboardType.Uri,
                )
                if (pickImage != null) {
                    TextButton(onClick = pickImage) { Text(stringResource(Res.string.webdav_info_pick_image)) }
                }
            }
            if (!section) {
            EditorField(background, { background = it }, stringResource(Res.string.webdav_info_background), keyboardType = KeyboardType.Uri)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EditorField(
                    value = year,
                    onValueChange = { value -> year = value.filter { it.isDigit() || it == '-' }.take(9) },
                    label = stringResource(Res.string.webdav_info_year),
                    modifier = Modifier.width(110.dp),
                    keyboardType = KeyboardType.Number,
                )
                EditorField(
                    value = imdbId,
                    onValueChange = { imdbId = it.trim() },
                    label = stringResource(Res.string.webdav_info_imdb),
                    modifier = Modifier.weight(1f),
                )
            }
            EditorField(description, { description = it }, stringResource(Res.string.webdav_info_description), singleLine = false)
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (saved != null) {
                    TextButton(onClick = { store(null) }) { Text(stringResource(Res.string.webdav_info_reset)) }
                }
                TextButton(onClick = { close() }) { Text(stringResource(Res.string.webdav_info_cancel)) }
                Button(
                    enabled = !busy,
                    onClick = {
                        if (section) {
                            store(
                                WebDavInfoOverride(
                                    name = name.trim().ifBlank { null },
                                    poster = poster.trim().ifBlank { null },
                                    type = WebDavInfoOverride.TYPE_FOLDER,
                                ),
                            )
                            return@Button
                        }
                        store(
                            WebDavInfoOverride(
                                name = name.trim().ifBlank { null }?.takeIf { it != preview.name || saved?.name != null },
                                poster = poster.trim().ifBlank { null },
                                background = background.trim().ifBlank { null },
                                description = description.trim().ifBlank { null },
                                year = year.trim().ifBlank { null },
                                imdbId = imdbId.trim().takeIf { it.startsWith("tt") },
                                type = if (kind == EditorKind.SERIES) "series" else "movie",
                                genres = genres,
                                sourceUrl = sourceUrl,
                            ),
                        )
                    },
                ) { Text(stringResource(Res.string.webdav_info_save)) }
            }
        }
    }
}

/** What the user says a drive file or folder is. */
private enum class EditorKind { MOVIE, SERIES, FOLDER }

@Composable
private fun EditorField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    singleLine: Boolean = true,
    onDone: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }, onSearch = { onDone?.invoke() }),
        colors = editorFieldColors(),
        modifier = modifier,
    )
}

@Composable
private fun editorFieldColors(): TextFieldColors {
    val colors = MaterialTheme.nuvio.colors
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = colors.borderFocus,
        unfocusedBorderColor = colors.borderDefault,
        focusedTextColor = colors.textPrimary,
        unfocusedTextColor = colors.textPrimary,
        focusedLabelColor = colors.textMuted,
        unfocusedLabelColor = colors.textMuted,
    )
}

/** Picks an image from the device as a poster (copied into the app); null where unsupported. */
@Composable
internal expect fun rememberPosterImagePicker(onPicked: (String) -> Unit): (() -> Unit)?

/** "Edit poster and info" in a PikPak tile's long-press menu. */
@Composable
internal fun webDavInfoPosterActions(preview: MetaPreview, onEdit: () -> Unit): List<PosterZoomOverlayAction> {
    if (!isWebDavItem(preview)) return emptyList()
    return listOf(
        PosterZoomOverlayAction(
            icon = Icons.Default.Edit,
            label = stringResource(Res.string.webdav_info_action),
            onSelected = onEdit,
        ),
    )
}
