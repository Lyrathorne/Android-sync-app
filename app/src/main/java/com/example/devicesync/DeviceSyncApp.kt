package com.example.devicesync

import androidx.compose.runtime.Composable
import com.example.devicesync.core.settings.ThemeMode
import com.example.devicesync.navigation.DeviceSyncNavHost
import com.example.devicesync.ui.theme.DeviceSyncTheme
import kotlinx.coroutines.flow.StateFlow

@Composable
fun DeviceSyncApp(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    keyboardSettingsRequests: StateFlow<Long>? = null,
) {
    DeviceSyncTheme(themeMode = themeMode) {
        DeviceSyncNavHost(keyboardSettingsRequests = keyboardSettingsRequests)
    }
}
