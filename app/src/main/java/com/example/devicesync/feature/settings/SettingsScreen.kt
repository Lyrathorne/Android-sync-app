package com.example.devicesync.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.devicesync.R
import com.example.devicesync.ui.theme.DeviceSyncTheme

@Composable
fun SettingsRoute(
    onBackClick: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val uiState = viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(
        uiState = uiState.value,
        onBackClick = onBackClick,
        onAutoConnectChanged = viewModel::setAutoConnectTrustedComputers,
        onRestoreConnectionChanged = viewModel::setRestoreConnectionAfterDisconnect,
        onConnectionNotificationChanged = viewModel::setShowConnectionNotification,
        onBackgroundWorkChanged = viewModel::setAllowBackgroundWork,
        onDarkThemeChanged = viewModel::setUseDarkTheme,
        onAboutClick = viewModel::showAboutDialog,
        onDismissAboutDialog = viewModel::dismissAboutDialog,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onBackClick: () -> Unit,
    onAutoConnectChanged: (Boolean) -> Unit,
    onRestoreConnectionChanged: (Boolean) -> Unit,
    onConnectionNotificationChanged: (Boolean) -> Unit,
    onBackgroundWorkChanged: (Boolean) -> Unit,
    onDarkThemeChanged: (Boolean) -> Unit,
    onAboutClick: () -> Unit,
    onDismissAboutDialog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBackClick) {
                    Text(stringResource(R.string.back))
                }
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SettingSwitchRow(
                text = stringResource(R.string.auto_connect),
                checked = uiState.autoConnectTrustedComputers,
                onCheckedChange = onAutoConnectChanged,
            )
            SettingSwitchRow(
                text = stringResource(R.string.restore_connection),
                checked = uiState.restoreConnectionAfterDisconnect,
                onCheckedChange = onRestoreConnectionChanged,
            )
            SettingSwitchRow(
                text = stringResource(R.string.connection_notification),
                checked = uiState.showConnectionNotification,
                onCheckedChange = onConnectionNotificationChanged,
            )
            SettingSwitchRow(
                text = stringResource(R.string.background_work),
                checked = uiState.allowBackgroundWork,
                onCheckedChange = onBackgroundWorkChanged,
            )
            SettingSwitchRow(
                text = stringResource(R.string.dark_theme),
                checked = uiState.useDarkTheme,
                onCheckedChange = onDarkThemeChanged,
            )
            TextButton(
                onClick = onAboutClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.about_app))
            }
        }
    }

    if (uiState.showAboutDialog) {
        AlertDialog(
            onDismissRequest = onDismissAboutDialog,
            title = { Text(stringResource(R.string.about_app)) },
            text = { Text(stringResource(R.string.about_app_body)) },
            confirmButton = {
                TextButton(onClick = onDismissAboutDialog) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun SettingSwitchRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    DeviceSyncTheme {
        SettingsScreen(
            uiState = SettingsUiState(),
            onBackClick = {},
            onAutoConnectChanged = {},
            onRestoreConnectionChanged = {},
            onConnectionNotificationChanged = {},
            onBackgroundWorkChanged = {},
            onDarkThemeChanged = {},
            onAboutClick = {},
            onDismissAboutDialog = {},
        )
    }
}
