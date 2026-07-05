package com.example.devicesync.feature.devices

import com.example.devicesync.core.model.Device

data class DevicesUiState(
    val isLoading: Boolean = false,
    val devices: List<Device> = emptyList(),
    val errorMessage: String? = null,
)
