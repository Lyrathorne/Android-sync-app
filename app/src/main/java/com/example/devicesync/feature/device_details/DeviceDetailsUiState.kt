package com.example.devicesync.feature.device_details

import com.example.devicesync.core.model.Device

data class DeviceDetailsUiState(
    val device: Device? = null,
    val showDeleteDialog: Boolean = false,
    val isDeleted: Boolean = false,
    val connectionStateText: String = "",
    val lastPongAtUtc: String? = null,
    val missedPongs: Int = 0,
    val reconnectAttempt: Int = 0,
    val pendingMessageCount: Int = 0,
)
