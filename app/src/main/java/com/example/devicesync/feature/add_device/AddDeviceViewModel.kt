package com.example.devicesync.feature.add_device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.devicesync.core.data.DeviceRepository
import com.example.devicesync.core.discovery.DeviceDiscoveryService
import com.example.devicesync.core.discovery.DiscoveredDevice
import com.example.devicesync.core.discovery.DiscoveryAddressSelector
import com.example.devicesync.core.discovery.DiscoveryState
import com.example.devicesync.core.model.InMemoryDeviceStore
import com.example.devicesync.core.network.ConnectionException
import com.example.devicesync.core.network.ConnectionManager
import com.example.devicesync.core.network.ConnectionState
import com.example.devicesync.core.network.NetworkLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AddDeviceViewModel(
    private val deviceStore: InMemoryDeviceStore = InMemoryDeviceStore(),
    private val connectionManager: ConnectionManager = ConnectionManager(),
    private val deviceRepository: DeviceRepository? = null,
    private val discoveryService: DeviceDiscoveryService? = null,
    private val addressSelector: DiscoveryAddressSelector = DiscoveryAddressSelector(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(AddDeviceUiState())
    val uiState: StateFlow<AddDeviceUiState> = _uiState.asStateFlow()

    init {
        connectionManager.state
            .onEach(::applyConnectionState)
            .launchIn(viewModelScope)
        discoveryService?.let { service ->
            combine(service.state, service.discoveredDevices, service.diagnostics) { state, devices, diagnostics ->
                Triple(state, devices, diagnostics)
            }.onEach { (state, devices, diagnostics) ->
                _uiState.update {
                    it.copy(
                        discoveryState = state,
                        discoveredDevices = devices,
                        discoveryDiagnostics = diagnostics,
                    )
                }
            }.launchIn(viewModelScope)
        }
    }

    fun startSearch() {
        viewModelScope.launch {
            discoveryService?.startDiscovery()
                ?: _uiState.update { it.copy(discoveryState = DiscoveryState.Failed("Discovery service is not available.")) }
        }
    }

    fun stopSearch() {
        viewModelScope.launch { discoveryService?.stopDiscovery() }
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

    fun connectDiscovered(device: DiscoveredDevice) {
        if (!device.isProtocolCompatible) {
            _uiState.update {
                it.copy(discoveryConnectionError = "Версия DeviceSync на компьютере несовместима. Обновите приложение.")
            }
            return
        }

        viewModelScope.launch {
            NetworkLogger.info("Connection requested for discovered device")
            val orderedAddresses = runCatching { addressSelector.orderedUsableAddresses(device.hostAddresses) }
                .getOrDefault(emptyList())
            if (orderedAddresses.isEmpty()) {
                _uiState.update { it.copy(discoveryConnectionError = "Не удалось выбрать сетевой адрес компьютера.") }
                return@launch
            }

            var lastError: Throwable? = null
            for (host in orderedAddresses) {
                try {
                    discoveryService?.stopDiscovery()
                    val connected = connectionManager.connect(host, device.port)
                    if (device.deviceId != null && device.deviceId != connected.deviceId) {
                        NetworkLogger.error("TXT device ID mismatch")
                        connectionManager.disconnect()
                        _uiState.update {
                            it.copy(discoveryConnectionError = "Идентификатор компьютера не совпал с ответом TCP handshake.")
                        }
                        return@launch
                    }

                    if (deviceRepository == null) {
                        deviceStore.addOrUpdateConnectedDevice(connected)
                    }
                    _uiState.update {
                        it.copy(
                            connectedDeviceId = connected.deviceId,
                            discoveryConnectionError = null,
                            manualConnectionStatus = ManualConnectionStatus.Connected(connected.deviceName),
                        )
                    }
                    return@launch
                } catch (error: Throwable) {
                    lastError = error
                }
            }

            _uiState.update {
                it.copy(discoveryConnectionError = lastError?.message ?: "Не удалось подключиться к найденному компьютеру.")
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
                is ConnectionState.AuthenticatingWindows -> current.copy(
                    manualConnectionStatus = ManualConnectionStatus.Handshaking(state.host, state.port),
                )
                is ConnectionState.ProvingAndroidIdentity -> current.copy(
                    manualConnectionStatus = ManualConnectionStatus.Handshaking(state.host, state.port),
                )
                is ConnectionState.Authenticated -> current.copy(
                    manualConnectionStatus = ManualConnectionStatus.Connected(state.deviceName),
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
                is ConnectionState.IdentityChanged -> current.copy(
                    manualConnectionStatus = ManualConnectionStatus.Failed("Ключ устройства изменился. Выполните привязку заново."),
                )
                ConnectionState.PairingRequired -> current.copy(
                    manualConnectionStatus = ManualConnectionStatus.Failed("Компьютер ещё не привязан. Отсканируйте QR-код на компьютере."),
                )
                ConnectionState.TrustRevoked -> current.copy(
                    manualConnectionStatus = ManualConnectionStatus.Failed("Привязка к компьютеру отозвана. Выполните привязку заново."),
                )
                is ConnectionState.AuthenticationFailed -> current.copy(
                    manualConnectionStatus = ManualConnectionStatus.Failed(state.message),
                )
                ConnectionState.Disconnected -> current
            }
        }
    }

    override fun onCleared() {
        viewModelScope.launch { discoveryService?.stopDiscovery() }
        super.onCleared()
    }

    class Factory(
        private val deviceRepository: DeviceRepository,
        private val connectionManager: ConnectionManager,
        private val discoveryService: DeviceDiscoveryService,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AddDeviceViewModel(
                connectionManager = connectionManager,
                deviceRepository = deviceRepository,
                discoveryService = discoveryService,
            ) as T
        }
    }
}
