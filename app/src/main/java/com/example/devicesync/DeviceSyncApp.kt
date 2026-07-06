package com.example.devicesync

import androidx.compose.runtime.Composable
import com.example.devicesync.core.settings.ThemeMode
import com.example.devicesync.navigation.DeviceSyncNavHost
import com.example.devicesync.ui.theme.DeviceSyncTheme

@Composable
fun DeviceSyncApp(themeMode: ThemeMode = ThemeMode.SYSTEM) {
    DeviceSyncTheme(themeMode = themeMode) {
        DeviceSyncNavHost()
    }
}
