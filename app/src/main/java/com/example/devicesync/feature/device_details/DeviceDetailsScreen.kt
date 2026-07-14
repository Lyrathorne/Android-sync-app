package com.example.devicesync.feature.device_details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.devicesync.R
import com.example.devicesync.core.model.SampleDevices
import com.example.devicesync.core.ui.components.ConnectionStatusIndicator
import com.example.devicesync.core.ui.components.connectionStatusText
import com.example.devicesync.ui.theme.DeviceSyncTheme
import kotlinx.coroutines.launch

@Composable
fun DeviceDetailsRoute(
    deviceId: String,
    onBackClick: () -> Unit,
    onSendFileClick: () -> Unit,
    viewModel: DeviceDetailsViewModel = viewModel(),
) {
    LaunchedEffect(deviceId) {
        viewModel.loadDevice(deviceId)
    }
    val uiState = viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.value.isDeleted) {
        if (uiState.value.isDeleted) {
            onBackClick()
        }
    }

    DeviceDetailsScreen(
        uiState = uiState.value,
        onBackClick = onBackClick,
        onSendFileClick = onSendFileClick,
        onUnavailableActionClick = {},
        onConnectClick = viewModel::connect,
        onDisconnectClick = viewModel::disconnect,
        onDeleteClick = viewModel::requestDelete,
        onDismissDeleteDialog = viewModel::dismissDeleteDialog,
        onConfirmDelete = viewModel::confirmDelete,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailsScreen(
    uiState: DeviceDetailsUiState,
    onBackClick: () -> Unit,
    onSendFileClick: () -> Unit,
    onUnavailableActionClick: () -> Unit,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onDismissDeleteDialog: () -> Unit,
    onConfirmDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val featureLaterText = stringResource(R.string.feature_later)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                    text = uiState.device?.name ?: stringResource(R.string.unknown_device),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
        },
    ) { padding ->
        val device = uiState.device
        if (device == null) {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(20.dp),
            ) {
                Text(stringResource(R.string.unknown_device))
            }
        } else {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                DeviceSummaryCard(uiState)
                DiagnosticsCard(uiState)
                FutureFeaturesCard()
                Button(
                    onClick = {
                        onUnavailableActionClick()
                        scope.launch { snackbarHostState.showSnackbar(featureLaterText) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.send_text))
                }
                Button(
                    onClick = onSendFileClick,
                    enabled = device.connectionStatus == com.example.devicesync.core.model.ConnectionStatus.CONNECTED,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.send_file))
                }
                OutlinedButton(
                    onClick = {
                        onUnavailableActionClick()
                        scope.launch { snackbarHostState.showSnackbar(featureLaterText) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.edit_permissions))
                }
                Button(
                    onClick = onConnectClick,
                    enabled = device.connectionStatus != com.example.devicesync.core.model.ConnectionStatus.CONNECTED &&
                        device.host != null &&
                        device.port != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.connect))
                }
                OutlinedButton(
                    onClick = onDisconnectClick,
                    enabled = device.connectionStatus == com.example.devicesync.core.model.ConnectionStatus.CONNECTED,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.disconnect))
                }
                OutlinedButton(
                    onClick = onDeleteClick,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.delete_device))
                }
            }
        }
    }

    if (uiState.showDeleteDialog) {
        DeleteDeviceDialog(
            onDismiss = onDismissDeleteDialog,
            onConfirm = onConfirmDelete,
        )
    }
}

@Composable
private fun DiagnosticsCard(uiState: DeviceDetailsUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.connection_diagnostics),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(stringResource(R.string.connection_state, uiState.connectionStateText.ifBlank { "-" }))
            Text(stringResource(R.string.last_pong, uiState.lastPongAtUtc ?: "-"))
            Text(stringResource(R.string.missed_pongs, uiState.missedPongs))
            Text(stringResource(R.string.reconnect_attempt, uiState.reconnectAttempt))
            Text(stringResource(R.string.pending_messages, uiState.pendingMessageCount))
        }
    }
}

@Composable
private fun DeviceSummaryCard(uiState: DeviceDetailsUiState) {
    val device = uiState.device ?: return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ConnectionStatusIndicator(device.connectionStatus)
                Text(
                    text = connectionStatusText(device.connectionStatus),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(stringResource(R.string.device_id, device.id))
            if (device.host != null && device.port != null) {
                Text(stringResource(R.string.host_port, device.host, device.port))
            }
            device.acceptedProtocolVersion?.let {
                Text(stringResource(R.string.accepted_protocol_version, it))
            }
            if (device.capabilities.isNotEmpty()) {
                Text(stringResource(R.string.capabilities, device.capabilities.joinToString()))
            }
            device.lastConnectedText?.let {
                Text(stringResource(R.string.last_connected, it))
            }
        }
    }
}

@Composable
private fun FutureFeaturesCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.future_features),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(stringResource(R.string.feature_text_transfer))
            Text(stringResource(R.string.feature_file_transfer))
            Text(stringResource(R.string.feature_clipboard_sync))
            Text(stringResource(R.string.feature_notifications))
        }
    }
}

@Composable
private fun DeleteDeviceDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_device_title)) },
        text = { Text(stringResource(R.string.delete_device_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun DeviceDetailsScreenPreview() {
    DeviceSyncTheme {
        DeviceDetailsScreen(
            uiState = DeviceDetailsUiState(device = SampleDevices.devices.first()),
            onBackClick = {},
            onSendFileClick = {},
            onUnavailableActionClick = {},
            onConnectClick = {},
            onDisconnectClick = {},
            onDeleteClick = {},
            onDismissDeleteDialog = {},
            onConfirmDelete = {},
        )
    }
}
