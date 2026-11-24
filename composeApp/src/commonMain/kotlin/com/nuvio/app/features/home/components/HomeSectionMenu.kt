package com.nuvio.app.features.home.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.home.HomeCatalogSettingsItem
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.home_hidden_sections_empty
import nuvio.composeapp.generated.resources.home_hidden_sections_footer
import nuvio.composeapp.generated.resources.home_hidden_sections_hint
import nuvio.composeapp.generated.resources.home_hidden_sections_show_all
import nuvio.composeapp.generated.resources.home_section_hide
import nuvio.composeapp.generated.resources.home_section_manage
import nuvio.composeapp.generated.resources.home_section_move_down
import nuvio.composeapp.generated.resources.home_section_move_up
import org.jetbrains.compose.resources.stringResource

// Nuvio RS: SAMplay's long press on a Home section, on Nuvio's own Home settings. Hiding a section
// turns it off in Settings › Home, so both places agree and the choice syncs with the account.

/** What a long press on a section's title offers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeSectionMenuSheet(
    title: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onHide: () -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val act: (() -> Unit) -> Unit = { action ->
        action()
        scope.launch { dismissNuvioBottomSheet(sheetState, onDismiss) }
    }
    NuvioModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        HomeSheetTitle(title)
        NuvioBottomSheetDivider()
        Spacer(Modifier.height(NuvioTokens.Space.s4))
        if (canMoveUp) {
            NuvioBottomSheetActionRow(
                title = stringResource(Res.string.home_section_move_up),
                icon = Icons.Rounded.ArrowUpward,
                onClick = { act(onMoveUp) },
            )
        }
        if (canMoveDown) {
            NuvioBottomSheetActionRow(
                title = stringResource(Res.string.home_section_move_down),
                icon = Icons.Rounded.ArrowDownward,
                onClick = { act(onMoveDown) },
            )
        }
        NuvioBottomSheetActionRow(
            title = stringResource(Res.string.home_section_hide),
            icon = Icons.Rounded.VisibilityOff,
            onClick = { act(onHide) },
        )
        NuvioBottomSheetActionRow(
            title = stringResource(Res.string.home_section_manage),
            icon = Icons.Rounded.Tune,
            onClick = { act(onManage) },
        )
        Spacer(Modifier.height(NuvioTokens.Space.s16))
    }
}

/** The hidden sections; a tap shows one again. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeHiddenSectionsSheet(
    hidden: List<HomeCatalogSettingsItem>,
    onShow: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    NuvioModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        HomeSheetTitle(stringResource(Res.string.home_section_manage))
        Text(
            text = stringResource(
                if (hidden.isEmpty()) Res.string.home_hidden_sections_empty else Res.string.home_hidden_sections_hint,
            ),
            modifier = Modifier.padding(horizontal = tokens.spacing.screenHorizontal, vertical = NuvioTokens.Space.s4),
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textMuted,
        )
        if (hidden.isNotEmpty()) {
            NuvioBottomSheetDivider(Modifier.padding(top = NuvioTokens.Space.s8))
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                hidden.forEach { item ->
                    NuvioBottomSheetActionRow(
                        title = item.displayTitle,
                        icon = Icons.Rounded.Visibility,
                        onClick = { onShow(listOf(item.key)) },
                    )
                }
                if (hidden.size > 1) {
                    NuvioBottomSheetActionRow(
                        title = stringResource(Res.string.home_hidden_sections_show_all),
                        icon = Icons.Rounded.Visibility,
                        onClick = { onShow(hidden.map { it.key }) },
                    )
                }
            }
        }
        Spacer(Modifier.height(NuvioTokens.Space.s16))
    }
}

/** The line at the bottom of Home while sections are hidden. */
@Composable
internal fun HomeHiddenSectionsFooter(
    count: Int,
    horizontalPadding: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Text(
        text = stringResource(Res.string.home_hidden_sections_footer, count),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = NuvioTokens.Space.s16),
        style = MaterialTheme.typography.bodyMedium,
        color = tokens.colors.textMuted,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun HomeSheetTitle(title: String) {
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
