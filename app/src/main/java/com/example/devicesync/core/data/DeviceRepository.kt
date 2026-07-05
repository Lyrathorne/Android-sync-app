package com.example.devicesync.core.data

import com.example.devicesync.core.model.ConnectionStatus
import kotlinx.coroutines.flow.Flow
import java.time.Instant

interface DeviceRepository {
    fun observeDevices(): Flow<List<PairedDevice>>
    suspend fun getDevice(deviceId: String): PairedDevice?
    suspend fun saveDevice(device: PairedDevice)
    suspend fun updateConnectionStatus(deviceId: String, status: ConnectionStatus)
    suspend fun updateLastConnectedAt(deviceId: String, timestamp: Instant)
    suspend fun removeDevice(deviceId: String)
}
