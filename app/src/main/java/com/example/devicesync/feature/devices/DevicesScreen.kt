package com.example.devicesync.feature.devices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import com.example.devicesync.core.model.ConnectionStatus
import com.example.devicesync.core.model.Device
import com.example.devicesync.core.model.SampleDevices
import com.example.devicesync.core.ui.components.ConnectionStatusIndicator
import com.example.devicesync.core.ui.components.connectionStatusText
import com.example.devicesync.ui.theme.DeviceSyncTheme

@Composable
fun DevicesRoute(
    onAddDeviceClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSharingClick: () -> Unit,
    onDeviceClick: (String) -> Unit,
    viewModel: DevicesViewModel = viewModel(),
) {
    val uiState = viewModel.uiState.collectAsStateWithLifecycle()

    DevicesScreen(
        uiState = uiState.value,
        onAddDeviceClick = onAddDeviceClick,
        onSettingsClick = onSettingsClick,
        onSharingClick = onSharingClick,
        onDeviceClick = onDeviceClick,
        onRetryClick = viewModel::retryLoading,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    uiState: DevicesUiState,
    onAddDeviceClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSharingClick: () -> Unit,
    onDeviceClick: (String) -> Unit,
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                )
                TextButton(onClick = onSettingsClick) {
                    Text(stringResource(R.string.settings))
                }
                TextButton(onClick = onSharingClick) { Text("Share") }
            }
        },
    ) { padding ->
        DevicesContent(
            uiState = uiState,
            onAddDeviceClick = onAddDeviceClick,
            onDeviceClick = onDeviceClick,
            onRetryClick = onRetryClick,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
private fun DevicesContent(
    uiState: DevicesUiState,
    onAddDeviceClick: () -> Unit,
    onDeviceClick: (String) -> Unit,
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.devices_status_ready),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.secondary,
        )
        Button(
            onClick = onAddDeviceClick,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.add_device))
        }

        when {
            uiState.isLoading -> LoadingDevices()
            uiState.errorMessage != null -> ErrorDevices(
                message = uiState.errorMessage,
                onRetryClick = onRetryClick,
            )
            uiState.devices.isEmpty() -> EmptyDevices()
            else -> DeviceList(
                devices = uiState.devices,
                onDeviceClick = onDeviceClick,
            )
        }
    }
}

@Composable
private fun DeviceList(
    devices: List<Device>,
    onDeviceClick: (String) -> Unit,
) {
    Text(
        text = stringResource(R.string.paired_computers),
        style = MaterialTheme.typography.titleMedium,
    )
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(devices, key = { it.id }) { device ->
            DeviceCard(
                device = device,
                onClick = { onDeviceClick(device.id) },
            )
        }
    }
}

@Composable
private fun DeviceCard(
    device: Device,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConnectionStatusIndicator(device.connectionStatus)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = connectionStatusText(device.connectionStatus),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (device.connectionStatus == ConnectionStatus.OFFLINE && device.lastConnectedText != null) {
                    Text(
                        text = stringResource(R.string.last_connected, device.lastConnectedText),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                if (device.host != null && device.port != null && device.connectionStatus == ConnectionStatus.CONNECTED) {
                    Text(
                        text = stringResource(R.string.host_port, device.host, device.port),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingDevices() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator()
        Text(stringResource(R.string.loading_devices))
    }
}

@Composable
private fun EmptyDevices() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.empty_devices_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.empty_devices_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ErrorDevices(
    message: String,
    onRetryClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onRetryClick) {
                Text(stringResource(R.string.retry))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun DevicesScreenPreview() {
    DeviceSyncTheme {
        DevicesScreen(
            uiState = DevicesUiState(devices = SampleDevices.devices),
            onAddDeviceClick = {},
            onSettingsClick = {},
            onSharingClick = {},
            onDeviceClick = {},
            onRetryClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun EmptyDevicesScreenPreview() {
    DeviceSyncTheme {
        DevicesScreen(
            uiState = DevicesUiState(),
            onAddDeviceClick = {},
            onSettingsClick = {},
            onSharingClick = {},
            onDeviceClick = {},
            onRetryClick = {},
        )
    }
}
