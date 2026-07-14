package com.example.devicesync.feature.send_file

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.devicesync.core.background.TransferForegroundService
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SendFileRoute(
    onBackClick: () -> Unit,
    viewModel: SendFileViewModel,
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.selectFile(uri.toString())
        }
    }

    SendFileScreen(
        state = state.value,
        onBackClick = onBackClick,
        onChooseFile = { picker.launch(arrayOf("*/*")) },
        onSend = { TransferForegroundService.start(context); viewModel.send() },
        onCancel = viewModel::cancel,
    )
}

@Composable
fun SendFileScreen(
    state: SendFileUiState,
    onBackClick: () -> Unit,
    onChooseFile: () -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    Scaffold(
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBackClick) { Text("Back") }
                Text("Send file", style = MaterialTheme.typography.headlineSmall)
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Computer: ${state.targetName}", style = MaterialTheme.typography.titleMedium)
                    Text("File: ${state.fileName ?: "Not selected"}")
                    Text("Size: ${state.fileSizeBytes?.let(::formatBytes) ?: "-"}")
                    Text("MIME: ${state.mimeType ?: "-"}")
                }
            }
            OutlinedButton(onClick = onChooseFile, enabled = !state.canCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Choose file")
            }
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            Text("${formatBytes(state.sentBytes)} / ${formatBytes(state.totalBytes)}")
            Text("${formatBytes(state.bytesPerSecond)}/s")
            Text(state.status, style = MaterialTheme.typography.titleMedium)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = onSend, enabled = state.canSend, modifier = Modifier.fillMaxWidth()) {
                Text("Send")
            }
            OutlinedButton(onClick = onCancel, enabled = state.canCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
        }
    }
}

private fun formatBytes(value: Long): String {
    val units = listOf("B", "KiB", "MiB", "GiB")
    var amount = value.toDouble()
    var unit = 0
    while (amount >= 1024 && unit < units.lastIndex) {
        amount /= 1024
        unit++
    }
    return "%.2f %s".format(amount, units[unit])
}
