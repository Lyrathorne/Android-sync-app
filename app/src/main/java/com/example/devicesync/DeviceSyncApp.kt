package com.example.devicesync

import androidx.compose.runtime.Composable
import com.example.devicesync.navigation.DeviceSyncNavHost
import com.example.devicesync.ui.theme.DeviceSyncTheme

@Composable
fun DeviceSyncApp() {
    DeviceSyncTheme {
        DeviceSyncNavHost()
    }
}
