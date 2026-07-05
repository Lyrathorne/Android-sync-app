package com.example.devicesync.feature.settings

data class SettingsUiState(
    val autoConnectTrustedComputers: Boolean = true,
    val restoreConnectionAfterDisconnect: Boolean = true,
    val showConnectionNotification: Boolean = true,
    val allowBackgroundWork: Boolean = false,
    val useDarkTheme: Boolean = false,
    val showAboutDialog: Boolean = false,
)
