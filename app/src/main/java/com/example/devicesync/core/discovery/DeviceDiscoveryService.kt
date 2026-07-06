package com.example.devicesync.core.discovery

import kotlinx.coroutines.flow.StateFlow

interface DeviceDiscoveryService {
    val state: StateFlow<DiscoveryState>
    val discoveredDevices: StateFlow<List<DiscoveredDevice>>
    val diagnostics: StateFlow<DiscoveryDiagnostics>

    suspend fun startDiscovery()
    suspend fun stopDiscovery()
}
