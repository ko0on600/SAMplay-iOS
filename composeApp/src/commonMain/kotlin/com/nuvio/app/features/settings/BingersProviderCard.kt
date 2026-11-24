package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.format.formatLocalDateTime
import com.nuvio.app.features.bingers.BingersError
import com.nuvio.app.features.bingers.BingersTracker
import com.nuvio.app.features.profiles.ProfileRepository
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Nuvio RS: Bingers in Tracking. The user pastes the personal access token they create on
 * bingers.app/integrations; Nuvio then records plays and watched marks in their account.
 */
@Composable
internal fun BingersProviderCard(modifier: Modifier = Modifier) {
    val state by remember { BingersTracker.ensureLoaded(); BingersTracker.state }.collectAsStateWithLifecycle()
    // Never saved across process death or shown again: the token only lives in the field until it is checked.
    var token by remember(ProfileRepository.activeProfileId) { mutableStateOf("") }
    TrackingProviderCard(
        brand = TrackingBrand.BINGERS,
        mode = if (state.isConnected) TrackingConnectionCardMode.CONNECTED else TrackingConnectionCardMode.DISCONNECTED,
        credentialsConfigured = true,
        isLoading = state.isBusy,
        connectedLabel = stringResource(
            Res.string.settings_bingers_connected_as,
            state.accountName ?: stringResource(Res.string.settings_bingers_account_fallback),
        ),
        connectedDescription = stringResource(Res.string.settings_bingers_connected_description),
        signInDescription = stringResource(Res.string.settings_bingers_sign_in_description),
        finishSignInLabel = stringResource(Res.string.settings_bingers_connect),
        approvalDescription = stringResource(Res.string.settings_bingers_sign_in_description),
        connectLabel = stringResource(Res.string.settings_bingers_connect),
        openLoginLabel = stringResource(Res.string.settings_bingers_create_token),
        disconnectLabel = stringResource(Res.string.settings_bingers_disconnect),
        missingCredentialsMessage = stringResource(Res.string.settings_bingers_error_invalid_token),
        statusMessage = state.lastRecordedEpochMs
            ?.takeIf { state.isConnected }
            ?.let { stringResource(Res.string.settings_bingers_last_recorded, formatLocalDateTime(it)) },
        errorMessage = state.error?.let { bingersErrorMessage(it) },
        websiteLabel = stringResource(Res.string.settings_bingers_create_token),
        websiteUrl = BINGERS_TOKENS_URL,
        onConnectRequested = {
            if (BingersTracker.connect(token) == null) token = ""
            null
        },
        onResumeAuthorization = { BINGERS_TOKENS_URL },
        onCancelAuthorization = BingersTracker::clearError,
        onDisconnect = BingersTracker::disconnect,
        modifier = modifier,
        disconnectedContent = {
            OutlinedTextField(
                value = token,
                onValueChange = { value ->
                    token = value.trim()
                    if (state.error != null) BingersTracker.clearError()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isBusy,
                singleLine = true,
                label = { Text(stringResource(Res.string.settings_bingers_token_label)) },
                placeholder = { Text(stringResource(Res.string.settings_bingers_token_placeholder)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                    autoCorrectEnabled = false,
                ),
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color.White.copy(alpha = 0.85f),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.4f),
                    focusedLabelColor = Color.White.copy(alpha = 0.9f),
                    unfocusedLabelColor = Color.White.copy(alpha = 0.7f),
                    focusedPlaceholderColor = Color.White.copy(alpha = 0.45f),
                    unfocusedPlaceholderColor = Color.White.copy(alpha = 0.45f),
                    cursorColor = Color.White,
                    focusedContainerColor = Color.Black.copy(alpha = 0.18f),
                    unfocusedContainerColor = Color.Black.copy(alpha = 0.18f),
                    disabledContainerColor = Color.Black.copy(alpha = 0.18f),
                    disabledTextColor = Color.White.copy(alpha = 0.5f),
                ),
            )
        },
    )
}

@Composable
private fun bingersErrorMessage(error: BingersError): String = stringResource(
    when (error) {
        BingersError.INVALID_TOKEN -> Res.string.settings_bingers_error_invalid_token
        BingersError.READ_ONLY_TOKEN -> Res.string.settings_bingers_error_read_only
        BingersError.NOT_ENABLED -> Res.string.settings_bingers_error_not_enabled
        BingersError.RATE_LIMITED -> Res.string.settings_bingers_error_rate_limited
        BingersError.NETWORK -> Res.string.settings_bingers_error_network
        BingersError.SERVER -> Res.string.settings_bingers_error_server
    },
)

private const val BINGERS_TOKENS_URL = "https://bingers.app/integrations"
