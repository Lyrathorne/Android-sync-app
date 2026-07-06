package com.example.devicesync.feature.device_details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.devicesync.core.data.DeviceRepository
import com.example.devicesync.core.model.ConnectionStatus
import com.example.devicesync.core.model.InMemoryDeviceStore
import com.example.devicesync.core.model.toDevice
import com.example.devicesync.core.network.ConnectionManager
import com.example.devicesync.core.network.ConnectionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DeviceDetailsViewModel(
    private val deviceStore: InMemoryDeviceStore = InMemoryDeviceStore(),
    private val connectionManager: ConnectionManager = ConnectionManager(),
    private val deviceRepository: DeviceRepository? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(DeviceDetailsUiState())
    val uiState: StateFlow<DeviceDetailsUiState> = _uiState.asStateFlow()

    private var deviceId: String? = null
    private var observeJob: Job? = null

    fun loadDevice(deviceId: String) {
        this.deviceId = deviceId
        observeJob?.cancel()
        val devicesFlow = deviceRepository?.observeDevices()
            ?.let { flow -> flow.combine(connectionManager.state) { devices, connectionState ->
                val paired = devices.firstOrNull { it.id == deviceId }
                val device = paired?.toDevice()
                if (device != null && connectionState is ConnectionState.Connected && connectionState.deviceId == device.id) {
                    device.copy(connectionStatus = ConnectionStatus.CONNECTED)
                } else {
                    device
                }
            } }
            ?: combine(deviceStore.devices, connectionManager.state) { devices, connectionState ->
                val device = devices.firstOrNull { it.id == deviceId }
                if (device != null && connectionState is ConnectionState.Connected && connectionState.deviceId == device.id) {
                    device.copy(connectionStatus = ConnectionStatus.CONNECTED)
                } else {
                    device
                }
            }

        observeJob = combine(devicesFlow, connectionManager.state) { device, connectionState ->
            device to connectionState
        }.onEach { (device, connectionState) ->
            _uiState.update { state ->
                state.copy(
                    device = device,
                    connectionStateText = connectionState.label(),
                    lastPongAtUtc = (connectionState as? ConnectionState.Connected)?.lastPongAtUtc,
                    missedPongs = (connectionState as? ConnectionState.Connected)?.missedPongs ?: 0,
                    reconnectAttempt = (connectionState as? ConnectionState.Reconnecting)?.attempt ?: 0,
                    pendingMessageCount = (connectionState as? ConnectionState.Connected)?.pendingMessageCount ?: 0,
                )
            }
        }
            .launchIn(viewModelScope)
    }

    fun requestDelete() {
        _uiState.update { it.copy(showDeleteDialog = true) }
    }

    fun dismissDeleteDialog() {
        _uiState.update { it.copy(showDeleteDialog = false) }
    }

    fun confirmDelete() {
        val currentDeviceId = deviceId
        if (currentDeviceId != null) {
            if (deviceRepository != null) {
                viewModelScope.launch {
                    connectionManager.disconnectDevice(currentDeviceId)
                    deviceRepository.removeDevice(currentDeviceId)
                }
            } else {
                deviceStore.removeDevice(currentDeviceId)
            }
        }
        _uiState.update { it.copy(showDeleteDialog = false, isDeleted = true) }
    }

    fun disconnect() {
        val currentDeviceId = deviceId ?: return
        viewModelScope.launch {
            connectionManager.disconnect()
            if (deviceRepository != null) {
                deviceRepository.updateConnectionStatus(currentDeviceId, ConnectionStatus.OFFLINE)
            } else {
                deviceStore.markDisconnected(currentDeviceId)
            }
        }
    }

    fun connect() {
        val device = _uiState.value.device ?: return
        val host = device.host
        val port = device.port
        if (host.isNullOrBlank() || port == null) {
            _uiState.update { it.copy(connectionStateText = "Invalid host or port") }
            return
        }
        viewModelScope.launch {
            runCatching { connectionManager.connect(host, port) }
                .onFailure { error ->
                    _uiState.update { it.copy(connectionStateText = error.message.orEmpty()) }
                }
        }
    }

    class Factory(
        private val deviceRepository: DeviceRepository,
        private val connectionManager: ConnectionManager,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DeviceDetailsViewModel(
                connectionManager = connectionManager,
                deviceRepository = deviceRepository,
            ) as T
        }
    }
}

private fun ConnectionState.label(): String {
    return when (this) {
        ConnectionState.Disconnected -> "Не в сети"
        is ConnectionState.Connecting -> "Подключение"
        is ConnectionState.Handshaking -> "Проверка устройства"
        is ConnectionState.Connected -> "Подключено"
        is ConnectionState.Reconnecting -> "Переподключение: попытка $attempt"
        ConnectionState.NetworkUnavailable -> "Нет сети"
        is ConnectionState.Failed -> message
    }
}
