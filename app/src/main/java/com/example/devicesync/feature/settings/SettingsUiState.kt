package com.example.devicesync.feature.settings

data class SettingsUiState(
    val autoConnectTrustedComputers: Boolean = true,
    val restoreConnectionAfterDisconnect: Boolean = true,
    val allowBackgroundWork: Boolean = true,
    val useDarkTheme: Boolean = false,
    val showAboutDialog: Boolean = false,
)
