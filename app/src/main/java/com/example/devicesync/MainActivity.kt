package com.example.devicesync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.devicesync.core.settings.AppSettings

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val container = (application as DeviceSyncApplication).container
            val settings = container.settingsRepository.settings.collectAsStateWithLifecycle(
                initialValue = AppSettings(),
            )
            DeviceSyncApp(themeMode = settings.value.themeMode)
        }
    }
}
