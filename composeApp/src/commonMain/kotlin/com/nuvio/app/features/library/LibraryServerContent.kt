package com.nuvio.app.features.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.format.formatReleaseDateForDisplay
import com.nuvio.app.core.ui.NuvioPosterWatchedOverlay
import com.nuvio.app.core.ui.NuvioShelfSectionHeader
import com.nuvio.app.core.ui.NuvioViewAllPillSize
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.home.components.HomeEmptyStateCard
import com.nuvio.app.features.home.components.HomeSkeletonRow
import com.nuvio.app.features.home.components.PosterGridRow
import com.nuvio.app.features.home.stableKey
import com.nuvio.app.features.servers.ServerCatalog
import com.nuvio.app.features.servers.ServerFailure
import com.nuvio.app.features.servers.ServerLibraryRef
import com.nuvio.app.features.servers.message
import com.nuvio.app.features.servers.serverFailure
import com.nuvio.app.features.watching.application.WatchingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_retry
import nuvio.composeapp.generated.resources.library_server_load_failed
import nuvio.composeapp.generated.resources.library_servers_empty_message
import nuvio.composeapp.generated.resources.library_servers_empty_title
import org.jetbrains.compose.resources.stringResource

internal data class LibraryServerShelf(
    val ref: ServerLibraryRef,
    val section: HomeCatalogSection? = null,
    val failure: ServerFailure? = null,
)

internal suspend fun loadServerShelves(): List<LibraryServerShelf> = coroutineScope {
    ServerCatalog.libraries().map { ref ->
        async {
            try {
                LibraryServerShelf(ref, section = ServerCatalog.librarySection(ref, SERVER_SHELF_LIMIT))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                LibraryServerShelf(ref, failure = error.serverFailure())
            }
        }
    }.awaitAll()
}

internal fun LazyListScope.serverLibraryContent(
    shelves: List<LibraryServerShelf>?,
    layout: ServerLibraryLayout,
    gridColumns: Int,
    watchedKeys: Set<String>,
    fullyWatchedSeriesKeys: Set<String>,
    onCatalogClick: ((HomeCatalogSection) -> Unit)?,
    onPosterClick: ((MetaPreview) -> Unit)?,
    onPosterLongClick: ((MetaPreview) -> Unit)?,
    onRetry: () -> Unit,
) {
    when {
        shelves == null -> items(3) { HomeSkeletonRow(horizontalPadding = 16.dp) }

        shelves.isEmpty() -> item {
            HomeEmptyStateCard(
                modifier = Modifier.padding(horizontal = 16.dp),
                title = stringResource(Res.string.library_servers_empty_title),
                message = stringResource(Res.string.library_servers_empty_message),
            )
        }

        else -> shelves.forEach { shelf ->
            val section = shelf.section
            if (section != null && section.items.isNotEmpty()) {
                item(key = "server-shelf:${section.key}") {
                    NuvioShelfSectionHeader(
                        title = section.title,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                        onViewAllClick = onCatalogClick?.takeIf { section.hasMore }?.let { open -> { open(section) } },
                        viewAllPillSize = NuvioViewAllPillSize.Compact,
                    )
                }
                when (layout) {
                    ServerLibraryLayout.GRID -> items(
                        items = section.items.chunked(gridColumns),
                        key = { row -> "server-grid:${section.key}:${row.first().stableKey()}" },
                    ) { row ->
                        PosterGridRow(
                            items = row,
                            columns = gridColumns,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                            watchedKeys = watchedKeys,
                            fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
                            onPosterClick = onPosterClick,
                            onPosterLongClick = onPosterLongClick,
                        )
                    }

                    ServerLibraryLayout.LIST -> items(
                        items = section.items,
                        key = { item -> "server-list:${section.key}:${item.stableKey()}" },
                    ) { item ->
                        ServerLibraryListRow(
                            item = item,
                            isWatched = WatchingState.isPosterWatched(
                                watchedKeys = watchedKeys,
                                item = item,
                                fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
                            ),
                            onClick = onPosterClick?.let { { it(item) } },
                            onLongClick = onPosterLongClick?.let { { it(item) } },
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                        )
                    }
                }
                item(key = "server-shelf-end:${section.key}") { Spacer(modifier = Modifier.height(12.dp)) }
            } else if (shelf.failure != null) {
                item(key = "server-shelf-error:${shelf.ref.connection.id}:${shelf.ref.library.id}") {
                    HomeEmptyStateCard(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        title = stringResource(Res.string.library_server_load_failed, shelf.ref.title),
                        message = stringResource(shelf.failure.message()),
                        actionLabel = stringResource(Res.string.action_retry),
                        onActionClick = onRetry,
                    )
                }
            }
        }
    }
}

/** One title of the large list: a big picture beside its name, year and details. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ServerLibraryListRow(
    item: MetaPreview,
    isWatched: Boolean,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val (imageWidth, imageRatio) = when (item.posterShape) {
        PosterShape.Poster -> 96.dp to 0.68f
        PosterShape.Square -> 120.dp to 1f
        PosterShape.Landscape -> 160.dp to 1.78f
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(
                enabled = onClick != null || onLongClick != null,
                onClick = { onClick?.invoke() },
                onLongClick = onLongClick,
            )
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(imageWidth)
                .aspectRatio(imageRatio)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (item.poster != null) {
                AsyncImage(
                    model = item.poster,
                    contentDescription = item.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            NuvioPosterWatchedOverlay(isWatched = isWatched)
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val details = listOfNotNull(
                item.releaseInfo?.let(::formatReleaseDateForDisplay)?.ifBlank { null },
                item.genres.take(2).joinToString(" • ").ifBlank { null },
            ).joinToString(" • ")
            if (details.isNotBlank()) {
                Text(
                    text = details,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            item.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private const val SERVER_SHELF_LIMIT = 60
