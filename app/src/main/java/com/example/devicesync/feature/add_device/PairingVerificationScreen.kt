package com.example.devicesync.feature.add_device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.devicesync.core.security.PairingCoordinator
import com.example.devicesync.core.security.PairingState
import kotlinx.coroutines.launch

@Composable
fun PairingVerificationRoute(
    coordinator: PairingCoordinator,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by coordinator.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    PairingVerificationScreen(
        state = state,
        onConfirmClick = { scope.launch { coordinator.confirmVerificationCode() } },
        onRejectClick = { scope.launch { coordinator.rejectVerificationCode() } },
        onBackClick = onBackClick,
        modifier = modifier,
    )
}

@Composable
fun PairingVerificationScreen(
    state: PairingState,
    onConfirmClick: () -> Unit,
    onRejectClick: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBackClick) {
                    Text("Назад")
                }
                Text("Проверка привязки", style = MaterialTheme.typography.headlineSmall)
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (state) {
                PairingState.Idle -> Text("Отсканируйте QR-код на компьютере.")
                is PairingState.Connecting -> ProgressText("Подключение к ${state.windowsDeviceName}...")
                PairingState.SendingRequest -> ProgressText("Отправка запроса привязки...")
                PairingState.WaitingForChallenge -> ProgressText("Ожидание проверки компьютера...")
                PairingState.VerifyingChallenge -> ProgressText("Проверка кода безопасности...")
                is PairingState.WaitingForUserConfirmation -> VerificationContent(
                    state = state,
                    onConfirmClick = onConfirmClick,
                    onRejectClick = onRejectClick,
                )
                PairingState.WaitingForWindowsConfirmation -> ProgressText("Ожидание подтверждения на компьютере...")
                PairingState.SavingTrust -> ProgressText("Сохранение доверенного устройства...")
                is PairingState.Completed -> Text(
                    text = "Привязка завершена: ${state.trustedDeviceId.shortId()}",
                    color = MaterialTheme.colorScheme.primary,
                )
                is PairingState.Failed -> Text(
                    text = state.userMessage,
                    color = MaterialTheme.colorScheme.error,
                )
                PairingState.Cancelled -> Text("Привязка отменена.")
                PairingState.Expired -> Text("QR-код устарел. Создайте новый код на компьютере.")
            }
        }
    }
}

@Composable
private fun ProgressText(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator()
        Text(text)
    }
}

@Composable
private fun VerificationContent(
    state: PairingState.WaitingForUserConfirmation,
    onConfirmClick: () -> Unit,
    onRejectClick: () -> Unit,
) {
    Text(state.windowsDeviceName, style = MaterialTheme.typography.titleLarge)
    Text("Device ID: ${state.windowsDeviceId.shortId()}")
    Text("Fingerprint: ${state.windowsFingerprint.shortFingerprint()}")
    Text(
        text = state.verificationCode.formatVerificationCode(),
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.Bold,
    )
    Text("Сравните этот код с кодом в DeviceSync на компьютере.")
    Button(onClick = onConfirmClick, modifier = Modifier.fillMaxWidth()) {
        Text("Совпадает")
    }
    OutlinedButton(onClick = onRejectClick, modifier = Modifier.fillMaxWidth()) {
        Text("Не совпадает")
    }
}

private fun String.formatVerificationCode(): String {
    return padStart(6, '0').chunked(3).joinToString(" ")
}

private fun String.shortId(): String = if (length <= 12) this else "${take(8)}...${takeLast(4)}"

private fun String.shortFingerprint(): String = if (length <= 16) this else "${take(10)}...${takeLast(6)}"
