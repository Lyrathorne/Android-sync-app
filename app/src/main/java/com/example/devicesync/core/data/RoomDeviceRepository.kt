package com.example.devicesync.core.data

import com.example.devicesync.core.database.dao.DeviceDao
import com.example.devicesync.core.database.entity.DeviceEntity
import com.example.devicesync.core.model.ConnectionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Instant

class RoomDeviceRepository(
    private val deviceDao: DeviceDao,
) : DeviceRepository {
    private val sessionStatuses = MutableStateFlow<Map<String, ConnectionStatus>>(emptyMap())

    override fun observeDevices(): Flow<List<PairedDevice>> {
        return deviceDao.observeDevices().combine(sessionStatuses) { devices, statuses ->
            devices.map { it.toPairedDevice(statuses[it.deviceId] ?: ConnectionStatus.OFFLINE) }
        }
    }

    override suspend fun getDevice(deviceId: String): PairedDevice? {
        val status = sessionStatuses.value[deviceId] ?: ConnectionStatus.OFFLINE
        return deviceDao.getDevice(deviceId)?.toPairedDevice(status)
    }

    override suspend fun saveDevice(device: PairedDevice) {
        deviceDao.saveDevice(device.toEntity())
        updateConnectionStatus(device.id, device.connectionStatus)
    }

    override suspend fun updateConnectionStatus(deviceId: String, status: ConnectionStatus) {
        sessionStatuses.value = sessionStatuses.value + (deviceId to status)
    }

    override suspend fun updateLastConnectedAt(deviceId: String, timestamp: Instant) {
        deviceDao.updateLastConnectedAt(deviceId, timestamp)
    }

    override suspend fun removeDevice(deviceId: String) {
        deviceDao.removeDevice(deviceId)
        sessionStatuses.value = sessionStatuses.value - deviceId
    }
}

private fun DeviceEntity.toPairedDevice(status: ConnectionStatus): PairedDevice {
    return PairedDevice(
        id = deviceId,
        name = name,
        host = host,
        port = port,
        protocolVersion = protocolVersion,
        capabilities = capabilities,
        lastConnectedAt = lastConnectedAt,
        isAutoConnectEnabled = autoConnectEnabled,
        connectionStatus = status,
    )
}

private fun PairedDevice.toEntity(): DeviceEntity {
    return DeviceEntity(
        deviceId = id,
        name = name,
        host = host,
        port = port,
        protocolVersion = protocolVersion,
        capabilities = capabilities,
        lastConnectedAt = lastConnectedAt,
        autoConnectEnabled = isAutoConnectEnabled,
    )
}
