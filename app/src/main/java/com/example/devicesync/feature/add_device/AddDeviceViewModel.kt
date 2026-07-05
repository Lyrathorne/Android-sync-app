package com.example.devicesync.feature.add_device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.devicesync.core.data.DeviceRepository
import com.example.devicesync.core.model.InMemoryDeviceStore
import com.example.devicesync.core.network.ConnectionException
import com.example.devicesync.core.network.ConnectionManager
import com.example.devicesync.core.network.ConnectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AddDeviceViewModel(
    private val deviceStore: InMemoryDeviceStore = InMemoryDeviceStore(),
    private val connectionManager: ConnectionManager = ConnectionManager(),
    private val deviceRepository: DeviceRepository? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AddDeviceUiState())
    val uiState: StateFlow<AddDeviceUiState> = _uiState.asStateFlow()

    init {
        connectionManager.state
            .onEach(::applyConnectionState)
            .launchIn(viewModelScope)
    }

    fun startSearch() {
        _uiState.update {
            it.copy(isSearching = true, foundDeviceName = null)
        }
        viewModelScope.launch {
            delay(900)
            _uiState.update {
                it.copy(isSearching = false, foundDeviceName = "Test-Windows-PC")
            }
        }
    }

    fun showManualForm() {
        _uiState.update { it.copy(isManualFormVisible = true) }
    }

    fun onIpChanged(value: String) {
        _uiState.update {
            it.copy(
                ipAddress = value,
                ipError = null,
                isManualInputValid = false,
                manualConnectionStatus = ManualConnectionStatus.Idle,
                connectedDeviceId = null,
            )
        }
    }

    fun onPortChanged(value: String) {
        _uiState.update {
            it.copy(
                port = value,
                portError = null,
                isManualInputValid = false,
                manualConnectionStatus = ManualConnectionStatus.Idle,
                connectedDeviceId = null,
            )
        }
    }

    fun validateManualInput() {
        validateCurrentInput()
    }

    fun connectManually() {
        val result = validateCurrentInput()
        if (!result.isValid) return

        val state = _uiState.value
        val host = state.ipAddress.trim()
        val port = state.port.toInt()
        viewModelScope.launch {
            try {
                val connected = connectionManager.connect(host, port)
                if (deviceRepository == null) {
                    deviceStore.addOrUpdateConnectedDevice(connected)
                }
                _uiState.update {
                    it.copy(
                        manualConnectionStatus = ManualConnectionStatus.Connected(connected.deviceName),
                        connectedDeviceId = connected.deviceId,
                    )
                }
            } catch (error: ConnectionException) {
                _uiState.update {
                    it.copy(manualConnectionStatus = ManualConnectionStatus.Failed(error.message.orEmpty()))
                }
            }
        }
    }

    private fun validateCurrentInput(): ManualConnectionValidationResult {
        val state = _uiState.value
        val result = ManualConnectionValidator.validate(state.ipAddress, state.port)
        _uiState.update {
            it.copy(
                ipError = result.ipError,
                portError = result.portError,
                isManualInputValid = result.isValid,
            )
        }
        return result
    }

    private fun applyConnectionState(state: ConnectionState) {
        _uiState.update { current ->
            when (state) {
                is ConnectionState.Connecting -> current.copy(
                    manualConnectionStatus = ManualConnectionStatus.Connecting(state.host, state.port),
                )
                is ConnectionState.Handshaking -> current.copy(
                    manualConnectionStatus = ManualConnectionStatus.Handshaking(state.host, state.port),
                )
                is ConnectionState.Connected -> current.copy(
                    manualConnectionStatus = ManualConnectionStatus.Connected(state.deviceName),
                )
                is ConnectionState.Failed -> current.copy(
                    manualConnectionStatus = ManualConnectionStatus.Failed(state.message),
                )
                is ConnectionState.Reconnecting -> current.copy(
                    manualConnectionStatus = ManualConnectionStatus.Failed(state.nextRetryMessage),
                )
                ConnectionState.NetworkUnavailable -> current.copy(
                    manualConnectionStatus = ManualConnectionStatus.Failed("Нет сети"),
                )
                ConnectionState.Disconnected -> current
            }
        }
    }

    class Factory(
        private val deviceRepository: DeviceRepository,
        private val connectionManager: ConnectionManager,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AddDeviceViewModel(
                connectionManager = connectionManager,
                deviceRepository = deviceRepository,
            ) as T
        }
    }
}
