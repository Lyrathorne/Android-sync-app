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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.devicesync.BuildConfig
import com.example.devicesync.core.security.PairingCoordinator
import com.example.devicesync.core.security.PairingState
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

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
                is PairingState.SendingRequest -> ProgressText("Отправка pairing.request...")
                is PairingState.WaitingForChallenge -> ProgressText("Ожидание pairing.challenge...")
                is PairingState.VerifyingChallenge -> ProgressText("Проверка ответа компьютера...")
                is PairingState.WaitingForUserConfirmation -> VerificationContent(
                    state = state,
                    onConfirmClick = onConfirmClick,
                    onRejectClick = onRejectClick,
                )
                PairingState.WaitingForWindowsConfirmation -> ProgressText("Ожидание подтверждения на компьютере...")
                PairingState.SavingTrust -> ProgressText("Сохранение доверенного устройства...")
                is PairingState.Completed -> Text(
                    text = "Привязка завершена. Подключение будет выполнено отдельно.",
                    color = MaterialTheme.colorScheme.primary,
                )
                is PairingState.Failed -> Text(
                    text = state.userMessage,
                    color = MaterialTheme.colorScheme.error,
                )
                PairingState.Cancelled -> Text("Привязка отменена.")
                PairingState.Expired -> Text("QR-код устарел. Создайте новый код на компьютере.")
            }

            if (BuildConfig.DEBUG) {
                DebugPairingInfo(state)
            }
        }
    }
}

@Composable
private fun DebugPairingInfo(state: PairingState) {
    val scope = rememberCoroutineScope()
    var probeResult by remember { mutableStateOf<String?>(null) }
    val target = when (state) {
        is PairingState.Connecting -> state.target
        is PairingState.SendingRequest -> state.target
        is PairingState.WaitingForChallenge -> state.target
        is PairingState.VerifyingChallenge -> state.target
        else -> "-"
    }
    val targets = when (state) {
        is PairingState.Connecting -> state.targets
        is PairingState.SendingRequest -> state.targets
        is PairingState.WaitingForChallenge -> state.targets
        is PairingState.VerifyingChallenge -> state.targets
        else -> emptyList()
    }
    val tcp = when (state) {
        is PairingState.Connecting -> "connecting"
        is PairingState.SendingRequest,
        is PairingState.WaitingForChallenge,
        is PairingState.VerifyingChallenge,
        is PairingState.WaitingForUserConfirmation -> "connected"
        else -> "-"
    }
    val sent = when (state) {
        is PairingState.WaitingForChallenge,
        is PairingState.VerifyingChallenge,
        is PairingState.WaitingForUserConfirmation -> "pairing.request"
        else -> "none"
    }
    val received = when (state) {
        is PairingState.VerifyingChallenge,
        is PairingState.WaitingForUserConfirmation -> "pairing.challenge"
        else -> "none"
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("State: ${state::class.simpleName}")
        Text("Target addresses:")
        targets.forEach { Text("- $it") }
        Text("Target: $target")
        Text("TCP: $tcp")
        Text("Sent: $sent")
        Text("Received: $received")
        Text("Elapsed: see logcat timestamps")
        if (target != "-") {
            Button(
                onClick = {
                    scope.launch {
                        probeResult = probeTcp(target)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Проверить TCP")
            }
        }
        probeResult?.let { Text(it) }
    }
}

private suspend fun probeTcp(target: String): String = withContext(Dispatchers.IO) {
    val host = target.substringBeforeLast(':')
    val port = target.substringAfterLast(':').toIntOrNull()
        ?: return@withContext "TCP недоступен: invalid port"
    runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 5_000)
        }
    }.fold(
        onSuccess = { "TCP доступен" },
        onFailure = { error -> "TCP недоступен: ${error::class.simpleName}" },
    )
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
