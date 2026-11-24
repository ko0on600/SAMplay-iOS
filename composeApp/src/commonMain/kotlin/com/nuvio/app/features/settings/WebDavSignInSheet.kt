package com.nuvio.app.features.settings

import com.nuvio.app.features.servers.webdav.PikPakApiClient
import com.nuvio.app.features.servers.webdav.PikPakApiSessions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.servers.ServerConnection
import com.nuvio.app.features.servers.ServerException
import com.nuvio.app.features.servers.ServerFailure
import com.nuvio.app.features.servers.ServerProvider
import com.nuvio.app.features.servers.ServerRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.servers_connect
import nuvio.composeapp.generated.resources.servers_connecting
import nuvio.composeapp.generated.resources.servers_error_address
import nuvio.composeapp.generated.resources.servers_error_auth
import nuvio.composeapp.generated.resources.servers_error_failed
import nuvio.composeapp.generated.resources.servers_error_forbidden
import nuvio.composeapp.generated.resources.servers_error_unreachable
import nuvio.composeapp.generated.resources.servers_password
import nuvio.composeapp.generated.resources.servers_username
import nuvio.composeapp.generated.resources.webdav_advanced
import nuvio.composeapp.generated.resources.webdav_alternative_endpoint
import nuvio.composeapp.generated.resources.webdav_alternative_hint
import nuvio.composeapp.generated.resources.webdav_client
import nuvio.composeapp.generated.resources.webdav_copy
import nuvio.composeapp.generated.resources.webdav_disabled_note
import nuvio.composeapp.generated.resources.webdav_pikpak_account_password
import nuvio.composeapp.generated.resources.webdav_pikpak_account_username
import nuvio.composeapp.generated.resources.webdav_pikpak_qualities_description
import nuvio.composeapp.generated.resources.webdav_pikpak_qualities
import nuvio.composeapp.generated.resources.webdav_enable
import nuvio.composeapp.generated.resources.webdav_folder
import nuvio.composeapp.generated.resources.webdav_folder_hint
import nuvio.composeapp.generated.resources.webdav_get_credentials
import nuvio.composeapp.generated.resources.webdav_legacy_endpoint
import nuvio.composeapp.generated.resources.webdav_legacy_note
import nuvio.composeapp.generated.resources.webdav_port
import nuvio.composeapp.generated.resources.webdav_scheme_note
import nuvio.composeapp.generated.resources.webdav_sheet_subtitle
import nuvio.composeapp.generated.resources.webdav_title
import nuvio.composeapp.generated.resources.webdav_universal_endpoint
import nuvio.composeapp.generated.resources.webdav_use_https
import org.jetbrains.compose.resources.stringResource

/** PikPak's WebDAV endpoints, as its own WebDAV settings page lists them. */
internal object PikPakWebDav {
    const val UNIVERSAL_ENDPOINT = "dav.pikpak.ai"
    const val LEGACY_ENDPOINT = "dav.mypikpak.com"

    /** PikPak's WebDAV settings page (the one the PikPak app opens), where WebDAV is
     *  switched on and the client username/password are shown. */
    const val SETTINGS_URL = "https://mypikpak.com/drive/app-extension/webdav"
}

private enum class WebDavEndpointChoice { Universal, Alternative, Legacy }

/** The WebDAV base URL the sheet's fields describe. */
internal fun webDavAddress(
    host: String,
    useHttps: Boolean,
    port: String,
    folder: String,
): String {
    val trimmedHost = host.trim().trimEnd('/')
    val base = if (trimmedHost.contains("://")) {
        trimmedHost
    } else {
        val portPart = port.trim().toIntOrNull()?.takeIf { it in 1..65535 }?.let { ":$it" }.orEmpty()
        (if (useHttps) "https://" else "http://") + trimmedHost + portPart
    }
    val path = folder.trim().trim('/').takeIf { it.isNotEmpty() }?.let { "/$it" }.orEmpty()
    return base + path
}

/**
 * Connect a WebDAV drive, laid out like PikPak's own WebDAV dialog: the enable switch,
 * the Universal / Alternative / Legacy endpoints (with copy buttons), the HTTP/HTTPS
 * note, the client's username and password, and an Advanced section.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WebDavSignInSheet(
    provider: ServerProvider,
    onConnected: (ServerConnection) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var enabled by rememberSaveable { mutableStateOf(true) }
    var endpoint by rememberSaveable { mutableStateOf(WebDavEndpointChoice.Universal) }
    var alternativeExpanded by rememberSaveable { mutableStateOf(false) }
    var alternative by rememberSaveable { mutableStateOf("") }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var useHttps by rememberSaveable { mutableStateOf(true) }
    var port by rememberSaveable { mutableStateOf("") }
    var folder by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var accountUsername by rememberSaveable { mutableStateOf("") }
    var accountPassword by remember { mutableStateOf("") }
    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<ServerFailure?>(null) }

    val host = when (endpoint) {
        WebDavEndpointChoice.Universal -> PikPakWebDav.UNIVERSAL_ENDPOINT
        WebDavEndpointChoice.Legacy -> PikPakWebDav.LEGACY_ENDPOINT
        WebDavEndpointChoice.Alternative -> alternative
    }
    val canConnect = enabled && !connecting && host.isNotBlank() && username.isNotBlank()

    fun connect() {
        if (!canConnect) return
        connecting = true
        error = null
        scope.launch {
            try {
                val address = webDavAddress(host, useHttps, port, folder)
                val connection = ServerRepository.connect(provider, address, username.trim(), password)
                if (PikPakApiClient.isPikPakAddress(connection.address)) {
                    val apiUser = accountUsername.trim().ifBlank { username.trim() }
                    val apiPass = accountPassword.ifBlank { password }
                    if (apiUser.isNotBlank() && apiPass.isNotBlank()) {
                        runCatching {
                            val apiSession = PikPakApiClient().signIn(apiUser, apiPass)
                            PikPakApiSessions.putForConnection(connection.id, apiSession)
                        }
                    }
                }
                password = ""
                accountPassword = ""
                dismissNuvioBottomSheet(sheetState) { onConnected(connection) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ServerException) {
                error = failure.failure
            } catch (_: Exception) {
                error = ServerFailure.FAILED
            } finally {
                connecting = false
            }
        }
    }

    NuvioModalBottomSheet(
        onDismissRequest = { if (!connecting) onDismiss() },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = tokens.spacing.sheetPadding,
                    end = tokens.spacing.sheetPadding,
                    bottom = tokens.spacing.sheetPadding,
                ),
        ) {
            Text(
                text = stringResource(Res.string.webdav_title),
                style = MaterialTheme.typography.titleLarge,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(NuvioTokens.Space.s4))
            Text(
                text = stringResource(Res.string.webdav_sheet_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textSecondary,
            )
            Spacer(modifier = Modifier.height(NuvioTokens.Space.s16))

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.webdav_enable),
                    style = MaterialTheme.typography.bodyLarge,
                    color = tokens.colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                    enabled = !connecting,
                    colors = SwitchDefaults.colors(checkedTrackColor = tokens.colors.accent),
                )
            }
            HorizontalDivider(color = tokens.colors.borderDefault)

            EndpointRow(
                label = stringResource(Res.string.webdav_universal_endpoint),
                value = PikPakWebDav.UNIVERSAL_ENDPOINT,
                selected = endpoint == WebDavEndpointChoice.Universal,
                onSelect = { endpoint = WebDavEndpointChoice.Universal },
                onCopy = { clipboard.setText(AnnotatedString(PikPakWebDav.UNIVERSAL_ENDPOINT)) },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { alternativeExpanded = !alternativeExpanded }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = endpoint == WebDavEndpointChoice.Alternative,
                    onClick = {
                        endpoint = WebDavEndpointChoice.Alternative
                        alternativeExpanded = true
                    },
                    colors = RadioButtonDefaults.colors(selectedColor = tokens.colors.accent),
                )
                Text(
                    text = stringResource(Res.string.webdav_alternative_endpoint),
                    style = MaterialTheme.typography.bodyLarge,
                    color = tokens.colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (alternativeExpanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = tokens.colors.textMuted,
                )
            }
            AnimatedVisibility(visible = alternativeExpanded) {
                OutlinedTextField(
                    value = alternative,
                    onValueChange = {
                        alternative = it
                        endpoint = WebDavEndpointChoice.Alternative
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth().padding(start = 48.dp, bottom = 6.dp),
                    enabled = !connecting,
                    singleLine = true,
                    placeholder = { Text(stringResource(Res.string.webdav_alternative_hint)) },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                    shape = tokens.shapes.button,
                    colors = webDavFieldColors(),
                )
            }
            EndpointRow(
                label = stringResource(Res.string.webdav_legacy_endpoint),
                value = PikPakWebDav.LEGACY_ENDPOINT,
                selected = endpoint == WebDavEndpointChoice.Legacy,
                onSelect = { endpoint = WebDavEndpointChoice.Legacy },
                onCopy = { clipboard.setText(AnnotatedString(PikPakWebDav.LEGACY_ENDPOINT)) },
            )
            Text(
                text = stringResource(Res.string.webdav_legacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textSecondary,
                modifier = Modifier.padding(start = 48.dp, bottom = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = tokens.colors.textMuted,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(Res.string.webdav_scheme_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textSecondary,
                )
            }
            HorizontalDivider(color = tokens.colors.borderDefault)
            Spacer(modifier = Modifier.height(NuvioTokens.Space.s12))

            Text(
                text = stringResource(Res.string.webdav_client),
                style = MaterialTheme.typography.labelLarge,
                color = tokens.colors.textMuted,
            )
            Spacer(modifier = Modifier.height(NuvioTokens.Space.s8))
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12)) {
                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !connecting,
                    singleLine = true,
                    label = { Text(stringResource(Res.string.servers_username)) },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Next,
                    ),
                    shape = tokens.shapes.button,
                    colors = webDavFieldColors(),
                )
                SettingsSecretTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        error = null
                    },
                    label = stringResource(Res.string.servers_password),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !connecting,
                    shape = tokens.shapes.button,
                    colors = webDavFieldColors(),
                    imeAction = ImeAction.Done,
                    keyboardActions = KeyboardActions(onDone = { connect() }),
                )
            }
            TextButton(onClick = { uriHandler.openUri(PikPakWebDav.SETTINGS_URL) }) {
                Text(
                    text = stringResource(Res.string.webdav_get_credentials),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.accent,
                )
            }

            if (endpoint == WebDavEndpointChoice.Universal || endpoint == WebDavEndpointChoice.Legacy || host.contains("pikpak", ignoreCase = true)) {
                Spacer(modifier = Modifier.height(NuvioTokens.Space.s16))
                Text(
                    text = stringResource(Res.string.webdav_pikpak_qualities),
                    style = MaterialTheme.typography.labelLarge,
                    color = tokens.colors.textMuted,
                )
                Spacer(modifier = Modifier.height(NuvioTokens.Space.s4))
                Text(
                    text = stringResource(Res.string.webdav_pikpak_qualities_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textSecondary,
                )
                Spacer(modifier = Modifier.height(NuvioTokens.Space.s8))
                Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12)) {
                    OutlinedTextField(
                        value = accountUsername,
                        onValueChange = { accountUsername = it },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !connecting,
                        singleLine = true,
                        label = { Text(stringResource(Res.string.webdav_pikpak_account_username)) },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Next,
                        ),
                        shape = tokens.shapes.button,
                        colors = webDavFieldColors(),
                    )
                    SettingsSecretTextField(
                        value = accountPassword,
                        onValueChange = { accountPassword = it },
                        label = stringResource(Res.string.webdav_pikpak_account_password),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !connecting,
                        shape = tokens.shapes.button,
                        colors = webDavFieldColors(),
                        imeAction = ImeAction.Done,
                        keyboardActions = KeyboardActions(onDone = { connect() }),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { advancedExpanded = !advancedExpanded }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.webdav_advanced),
                    style = MaterialTheme.typography.bodyLarge,
                    color = tokens.colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (advancedExpanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = tokens.colors.textMuted,
                )
            }
            AnimatedVisibility(visible = advancedExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(Res.string.webdav_use_https),
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = useHttps,
                            onCheckedChange = { useHttps = it },
                            enabled = !connecting,
                            colors = SwitchDefaults.colors(checkedTrackColor = tokens.colors.accent),
                        )
                    }
                    OutlinedTextField(
                        value = port,
                        onValueChange = { value -> port = value.filter { it.isDigit() }.take(5) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !connecting,
                        singleLine = true,
                        label = { Text(stringResource(Res.string.webdav_port)) },
                        placeholder = { Text(if (useHttps) "443" else "80") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        shape = tokens.shapes.button,
                        colors = webDavFieldColors(),
                    )
                    OutlinedTextField(
                        value = folder,
                        onValueChange = { folder = it },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !connecting,
                        singleLine = true,
                        label = { Text(stringResource(Res.string.webdav_folder)) },
                        placeholder = { Text(stringResource(Res.string.webdav_folder_hint)) },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Done,
                        ),
                        shape = tokens.shapes.button,
                        colors = webDavFieldColors(),
                    )
                }
            }

            if (!enabled) {
                Spacer(modifier = Modifier.height(NuvioTokens.Space.s10))
                Text(
                    text = stringResource(Res.string.webdav_disabled_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
            }
            error?.let { failure ->
                Spacer(modifier = Modifier.height(NuvioTokens.Space.s10))
                Text(
                    text = failure.webDavMessage(),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.danger,
                )
            }
            Spacer(modifier = Modifier.height(NuvioTokens.Space.s20))
            Button(
                onClick = ::connect,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(NuvioTokens.Space.s56),
                enabled = canConnect,
                shape = tokens.shapes.button,
                colors = ButtonDefaults.buttonColors(
                    containerColor = tokens.colors.accent,
                    contentColor = tokens.colors.onAccent,
                ),
            ) {
                if (connecting) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NuvioLoadingIndicator(
                            modifier = Modifier.size(tokens.icons.sm),
                            color = tokens.colors.onAccent,
                        )
                        Text(stringResource(Res.string.servers_connecting))
                    }
                } else {
                    Text(stringResource(Res.string.servers_connect))
                }
            }
        }
    }
}

@Composable
private fun EndpointRow(
    label: String,
    value: String,
    selected: Boolean,
    onSelect: () -> Unit,
    onCopy: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(selectedColor = tokens.colors.accent),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textPrimary,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.accent,
            )
        }
        IconButton(onClick = onCopy) {
            Icon(
                imageVector = Icons.Rounded.ContentCopy,
                contentDescription = stringResource(Res.string.webdav_copy),
                tint = tokens.colors.textMuted,
            )
        }
    }
}

@Composable
private fun webDavFieldColors(): TextFieldColors {
    val colors = MaterialTheme.nuvio.colors
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = colors.borderFocus,
        unfocusedBorderColor = colors.borderDefault,
        focusedTextColor = colors.textPrimary,
        unfocusedTextColor = colors.textPrimary,
        focusedLabelColor = colors.textSecondary,
        unfocusedLabelColor = colors.textMuted,
        cursorColor = colors.accent,
    )
}

@Composable
private fun ServerFailure.webDavMessage(): String = when (this) {
    ServerFailure.AUTH_REQUIRED -> stringResource(Res.string.servers_error_auth)
    ServerFailure.NOT_FOUND -> stringResource(Res.string.servers_error_address)
    ServerFailure.UNREACHABLE -> stringResource(Res.string.servers_error_unreachable)
    ServerFailure.FORBIDDEN -> stringResource(Res.string.servers_error_forbidden)
    ServerFailure.UNSUPPORTED,
    ServerFailure.INCOMPLETE,
    ServerFailure.FAILED -> stringResource(Res.string.servers_error_failed)
}
