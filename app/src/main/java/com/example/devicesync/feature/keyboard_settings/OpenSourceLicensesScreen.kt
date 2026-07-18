package com.example.devicesync.feature.keyboard_settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun OpenSourceLicensesScreen(onBackClick: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = onBackClick) { Text("Назад") }
            Text("Open-source licenses", style = MaterialTheme.typography.headlineMedium)
            Text("DeviceSync Keyboard engine", style = MaterialTheme.typography.titleMedium)
            Text("Собственная реализация DeviceSync. Исходный код сторонних клавиатур в текущую сборку не скопирован.")
            LicenseItem("AndroidX / Jetpack", "Apache License 2.0", "https://source.android.com/docs/setup/about/licenses")
            LicenseItem("Kotlin and kotlinx.coroutines/serialization", "Apache License 2.0", "https://github.com/JetBrains/kotlin")
            LicenseItem(
                "DeviceSync RU/EN slang mini-dictionaries",
                "Project-authored dataset, CC0-1.0",
                "third_party/devicesync-slang/NOTICE.md",
            )
            LicenseItem(
                "Bouncy Castle Java 1.84",
                "Bouncy Castle Licence; TLS and cryptography used by the secure Bluetooth fallback",
                "https://www.bouncycastle.org/licence.html",
            )
            LicenseItem(
                "FrequencyWords RU/EN dictionaries",
                "Creative Commons Attribution-ShareAlike 4.0 International; unmodified data, upstream 525f9b560de45753a5ea01069454e72e9aa541c6",
                "https://github.com/hermitdave/FrequencyWords",
            )
            Text("Полный инженерный аудит кандидатов находится в docs/KEYBOARD_OPEN_SOURCE_EVALUATION.md.")
        }
    }
}

@Composable
private fun LicenseItem(name: String, license: String, source: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(name, style = MaterialTheme.typography.titleMedium)
        Text(license)
        Text(source, color = MaterialTheme.colorScheme.primary)
    }
}
