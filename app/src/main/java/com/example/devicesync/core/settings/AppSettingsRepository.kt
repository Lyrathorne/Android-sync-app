package com.example.devicesync.core.settings

import kotlinx.coroutines.flow.Flow

data class AppSettings(
    val autoConnectEnabled: Boolean = true,
    val restoreConnectionEnabled: Boolean = true,
    val lastSelectedDeviceId: String? = null,
)

interface AppSettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun setAutoConnectEnabled(enabled: Boolean)
    suspend fun setRestoreConnectionEnabled(enabled: Boolean)
    suspend fun setLastSelectedDeviceId(deviceId: String?)
}
