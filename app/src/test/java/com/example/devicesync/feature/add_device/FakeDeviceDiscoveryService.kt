package com.example.devicesync.feature.add_device

import com.example.devicesync.core.discovery.DeviceDiscoveryService
import com.example.devicesync.core.discovery.DiscoveredDevice
import com.example.devicesync.core.discovery.DiscoveryDiagnostics
import com.example.devicesync.core.discovery.DiscoveryState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

class FakeDeviceDiscoveryService : DeviceDiscoveryService {
    private val _state = MutableStateFlow<DiscoveryState>(DiscoveryState.Idle)
    override val state: StateFlow<DiscoveryState> = _state
    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _devices
    private val _diagnostics = MutableStateFlow(DiscoveryDiagnostics())
    override val diagnostics: StateFlow<DiscoveryDiagnostics> = _diagnostics
    var startCount = 0
    var stopCount = 0

    override suspend fun startDiscovery() {
        if (_state.value == DiscoveryState.Searching) return
        startCount++
        _state.value = DiscoveryState.Searching
    }

    override suspend fun stopDiscovery() {
        stopCount++
        _state.value = DiscoveryState.Idle
    }

    fun emit(device: DiscoveredDevice) {
        _devices.value = (_devices.value.filterNot { it.dedupeKey == device.dedupeKey } + device)
    }

    fun fail(message: String) {
        _state.value = DiscoveryState.Failed(message)
    }
}

fun discoveredDevice(
    deviceId: String = "windows-123",
    address: String = "192.168.1.25",
    protocolMin: Int = 1,
    protocolMax: Int = 1,
) = DiscoveredDevice(
    serviceName = "Gleb-PC",
    deviceId = deviceId,
    deviceName = "Gleb-PC",
    hostAddresses = listOf(address),
    port = 54321,
    protocolMin = protocolMin,
    protocolMax = protocolMax,
    appVersion = "1.0",
    capabilities = listOf("heartbeat-v1"),
    pairingAvailable = false,
    lastSeenAt = Instant.now(),
)
