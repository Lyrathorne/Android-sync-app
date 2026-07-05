package com.example.devicesync.feature.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.devicesync.core.data.DeviceRepository
import com.example.devicesync.core.model.InMemoryDeviceStore
import com.example.devicesync.core.model.toDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DevicesViewModel(
    private val deviceStore: InMemoryDeviceStore = InMemoryDeviceStore(),
    private val deviceRepository: DeviceRepository? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(DevicesUiState(isLoading = true))
    val uiState: StateFlow<DevicesUiState> = _uiState.asStateFlow()

    init {
        if (deviceRepository != null) {
            deviceRepository.observeDevices()
                .onEach { devices -> _uiState.value = DevicesUiState(devices = devices.map { it.toDevice() }) }
                .launchIn(viewModelScope)
        } else {
            deviceStore.devices
                .onEach { devices -> _uiState.value = DevicesUiState(devices = devices) }
                .launchIn(viewModelScope)
        }
    }

    fun retryLoading() {
        _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = null)
    }

    fun removeDevice(deviceId: String) {
        if (deviceRepository != null) {
            viewModelScope.launch { deviceRepository.removeDevice(deviceId) }
        } else {
            deviceStore.removeDevice(deviceId)
        }
    }

    class Factory(
        private val deviceRepository: DeviceRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DevicesViewModel(deviceRepository = deviceRepository) as T
        }
    }
}
