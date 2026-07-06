package com.example.devicesync.feature.add_device

import com.example.devicesync.core.discovery.DiscoveredDevice
import com.example.devicesync.core.discovery.DiscoveryDiagnostics
import com.example.devicesync.core.discovery.DiscoveryState

data class AddDeviceUiState(
    val discoveryState: DiscoveryState = DiscoveryState.Idle,
    val discoveredDevices: List<DiscoveredDevice> = emptyList(),
    val discoveryDiagnostics: DiscoveryDiagnostics = DiscoveryDiagnostics(),
    val isManualFormVisible: Boolean = false,
    val ipAddress: String = "",
    val port: String = "",
    val ipError: ManualConnectionError? = null,
    val portError: ManualConnectionError? = null,
    val isManualInputValid: Boolean = false,
    val manualConnectionStatus: ManualConnectionStatus = ManualConnectionStatus.Idle,
    val connectedDeviceId: String? = null,
    val discoveryConnectionError: String? = null,
)

enum class ManualConnectionError {
    EMPTY_IP,
    PORT_NOT_NUMBER,
    PORT_OUT_OF_RANGE,
}

sealed interface ManualConnectionStatus {
    data object Idle : ManualConnectionStatus
    data class Connecting(val host: String, val port: Int) : ManualConnectionStatus
    data class Handshaking(val host: String, val port: Int) : ManualConnectionStatus
    data class Connected(val deviceName: String) : ManualConnectionStatus
    data class Failed(val message: String) : ManualConnectionStatus
}
