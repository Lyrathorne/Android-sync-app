package com.example.devicesync.feature.add_device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.devicesync.R
import com.example.devicesync.core.discovery.DiscoveredDevice
import com.example.devicesync.core.discovery.DiscoveryState
import com.example.devicesync.ui.theme.DeviceSyncTheme
import kotlinx.coroutines.launch

@Composable
fun AddDeviceRoute(
    onBackClick: () -> Unit,
    onConnected: (String) -> Unit,
    viewModel: AddDeviceViewModel = viewModel(),
) {
    val uiState = viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(uiState.value.connectedDeviceId) {
        uiState.value.connectedDeviceId?.let(onConnected)
    }

    AddDeviceScreen(
        uiState = uiState.value,
        onBackClick = onBackClick,
        onStartSearchClick = viewModel::startSearch,
        onStopSearchClick = viewModel::stopSearch,
        onConnectDiscoveredClick = viewModel::connectDiscovered,
        onQrClick = {},
        onManualClick = viewModel::showManualForm,
        onIpChanged = viewModel::onIpChanged,
        onPortChanged = viewModel::onPortChanged,
        onConnectManualClick = viewModel::connectManually,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDeviceScreen(
    uiState: AddDeviceUiState,
    onBackClick: () -> Unit,
    onStartSearchClick: () -> Unit,
    onStopSearchClick: () -> Unit,
    onConnectDiscoveredClick: (DiscoveredDevice) -> Unit,
    onQrClick: () -> Unit,
    onManualClick: () -> Unit,
    onIpChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit,
    onConnectManualClick: () -> Unit,
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
                    text = stringResource(R.string.add_device_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.same_network_hint),
                style = MaterialTheme.typography.bodyLarge,
            )
            AutoSearchCard(
                uiState = uiState,
                onStartSearchClick = onStartSearchClick,
                onStopSearchClick = onStopSearchClick,
                onConnectDiscoveredClick = onConnectDiscoveredClick,
            )
            OutlinedButton(
                onClick = {
                    onQrClick()
                    scope.launch { snackbarHostState.showSnackbar(featureLaterText) }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.scan_qr))
            }
            OutlinedButton(
                onClick = onManualClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.enter_ip_manually))
            }
            if (uiState.isManualFormVisible) {
                ManualConnectionForm(
                    uiState = uiState,
                    onIpChanged = onIpChanged,
                    onPortChanged = onPortChanged,
                    onConnectClick = onConnectManualClick,
                )
            }
        }
    }
}

@Composable
private fun AutoSearchCard(
    uiState: AddDeviceUiState,
    onStartSearchClick: () -> Unit,
    onStopSearchClick: () -> Unit,
    onConnectDiscoveredClick: (DiscoveredDevice) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.auto_search_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.auto_search_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            when (uiState.discoveryState) {
                DiscoveryState.Idle -> Button(
                    onClick = onStartSearchClick,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.start_search))
                }
                DiscoveryState.Starting,
                DiscoveryState.Searching -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.searching))
                    }
                    OutlinedButton(onClick = onStopSearchClick, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.stop_search))
                    }
                }
                DiscoveryState.Stopping -> Text(stringResource(R.string.stopping_search))
                is DiscoveryState.PermissionRequired -> Text(
                    text = stringResource(R.string.discovery_permission_required),
                    color = MaterialTheme.colorScheme.error,
                )
                is DiscoveryState.Failed -> {
                    Text(
                        text = uiState.discoveryState.userMessage,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = onStartSearchClick, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }
            if (uiState.discoveredDevices.isEmpty() && uiState.discoveryState == DiscoveryState.Searching) {
                Text(stringResource(R.string.no_computers_yet))
            }
            uiState.discoveredDevices.forEach { device ->
                DiscoveredDeviceCard(device, onConnectDiscoveredClick)
            }
            uiState.discoveryConnectionError?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error)
            }
            DiscoveryDiagnosticsText(uiState)
        }
    }
}

@Composable
private fun DiscoveredDeviceCard(
    device: DiscoveredDevice,
    onConnectClick: (DiscoveredDevice) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(device.deviceName, style = MaterialTheme.typography.titleMedium)
            Text("DeviceSync для Windows")
            Text("${device.hostAddresses.firstOrNull().orEmpty()}:${device.port}")
            Text("Протокол: ${device.protocolMin ?: "?"}-${device.protocolMax ?: "?"}")
            if (!device.isProtocolCompatible) {
                Text(
                    text = stringResource(R.string.incompatible_protocol),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = { onConnectClick(device) },
                enabled = device.isProtocolCompatible,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.connect))
            }
        }
    }
}

@Composable
private fun DiscoveryDiagnosticsText(uiState: AddDeviceUiState) {
    val diagnostics = uiState.discoveryDiagnostics
    Text(
        text = "Discovery state: ${uiState.discoveryState::class.simpleName}\n" +
            "Services found: ${diagnostics.foundServices}\n" +
            "Services resolved: ${diagnostics.resolvedServices}\n" +
            "Last callback: ${diagnostics.lastCallback ?: "-"}\n" +
            "Last error: ${diagnostics.lastError ?: "-"}\n" +
            "Service type: ${diagnostics.activeServiceType}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ManualConnectionForm(
    uiState: AddDeviceUiState,
    onIpChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit,
    onConnectClick: () -> Unit,
) {
    val isConnecting = uiState.manualConnectionStatus is ManualConnectionStatus.Connecting ||
        uiState.manualConnectionStatus is ManualConnectionStatus.Handshaking

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = uiState.ipAddress,
                onValueChange = onIpChanged,
                label = { Text(stringResource(R.string.ip_address)) },
                isError = uiState.ipError != null,
                supportingText = { uiState.ipError?.let { Text(errorText(it)) } },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = uiState.port,
                onValueChange = onPortChanged,
                label = { Text(stringResource(R.string.port)) },
                isError = uiState.portError != null,
                supportingText = { uiState.portError?.let { Text(errorText(it)) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Button(
                onClick = onConnectClick,
                enabled = !isConnecting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.connect))
            }
            ManualConnectionStatusText(uiState.manualConnectionStatus)
        }
    }
}

@Composable
private fun ManualConnectionStatusText(status: ManualConnectionStatus) {
    when (status) {
        ManualConnectionStatus.Idle -> Unit
        is ManualConnectionStatus.Connecting -> Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator()
            Text(stringResource(R.string.connecting_to_host, status.host, status.port))
        }
        is ManualConnectionStatus.Handshaking -> Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator()
            Text(stringResource(R.string.handshaking_device))
        }
        is ManualConnectionStatus.Connected -> Text(
            text = stringResource(R.string.connected_to_device, status.deviceName),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
        )
        is ManualConnectionStatus.Failed -> Text(
            text = status.message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun errorText(error: ManualConnectionError): String {
    return stringResource(
        when (error) {
            ManualConnectionError.EMPTY_IP -> R.string.ip_empty_error
            ManualConnectionError.PORT_NOT_NUMBER -> R.string.port_number_error
            ManualConnectionError.PORT_OUT_OF_RANGE -> R.string.port_range_error
        }
    )
}

@Preview(showBackground = true)
@Composable
private fun AddDeviceScreenPreview() {
    DeviceSyncTheme {
        AddDeviceScreen(
            uiState = AddDeviceUiState(isManualFormVisible = true),
            onBackClick = {},
            onStartSearchClick = {},
            onStopSearchClick = {},
            onConnectDiscoveredClick = {},
            onQrClick = {},
            onManualClick = {},
            onIpChanged = {},
            onPortChanged = {},
            onConnectManualClick = {},
        )
    }
}
