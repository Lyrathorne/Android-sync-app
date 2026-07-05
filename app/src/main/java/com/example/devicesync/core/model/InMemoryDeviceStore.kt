package com.example.devicesync.core.model

import com.example.devicesync.core.network.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class InMemoryDeviceStore(
    initialDevices: List<Device> = SampleDevices.devices,
) {
    private val _devices = MutableStateFlow(initialDevices)
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    fun addOrUpdateConnectedDevice(connected: ConnectionState.Connected) {
        val device = Device(
            id = connected.deviceId,
            name = connected.deviceName,
            connectionStatus = ConnectionStatus.CONNECTED,
            host = connected.host,
            port = connected.port,
            acceptedProtocolVersion = connected.acceptedProtocolVersion,
            capabilities = connected.capabilities,
        )
        _devices.update { currentDevices ->
            val withoutDevice = currentDevices.filterNot { it.id == device.id }
            withoutDevice + device
        }
    }

    fun markDisconnected(deviceId: String) {
        _devices.update { currentDevices ->
            currentDevices.map { device ->
                if (device.id == deviceId) {
                    device.copy(
                        connectionStatus = ConnectionStatus.OFFLINE,
                        lastConnectedText = "только что",
                    )
                } else {
                    device
                }
            }
        }
    }

    fun removeDevice(deviceId: String) {
        _devices.update { currentDevices ->
            currentDevices.filterNot { it.id == deviceId }
        }
    }

    fun findDevice(deviceId: String): Device? {
        return _devices.value.firstOrNull { it.id == deviceId }
    }
}
