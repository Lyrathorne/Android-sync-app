package com.example.devicesync.feature.receive_file

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.devicesync.core.transfer.IncomingFileTransferManager
import com.example.devicesync.core.transfer.IncomingFileTransferState
import com.example.devicesync.core.background.TransferForegroundService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveFileScreen(
    manager: IncomingFileTransferManager,
    onBackClick: () -> Unit,
) {
    val context = LocalContext.current
    val state by manager.state.collectAsState()
    val transfer = when (val current = state) {
        is IncomingFileTransferState.Offered -> current.transfer
        is IncomingFileTransferState.Receiving -> current.transfer
        is IncomingFileTransferState.Verifying -> current.transfer
        is IncomingFileTransferState.Completed -> current.transfer
        is IncomingFileTransferState.Failed -> current.transfer
        else -> null
    }
    var shouldAccept by remember { mutableStateOf(false) }
    val closeScreen = {
        if (state is IncomingFileTransferState.Offered) manager.reject()
        onBackClick()
    }
    BackHandler(onBack = closeScreen)
    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(transfer?.mimeType ?: "application/octet-stream")
    ) { uri ->
        shouldAccept = false
        if (uri != null) { TransferForegroundService.start(context); manager.accept(uri.toString()) }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Incoming file") },
            navigationIcon = { TextButton(onClick = closeScreen) { Text("Back") } },
        )
    }) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(20.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (transfer == null) {
                Text("No active incoming transfer")
            } else {
                Text(transfer.fileName, style = MaterialTheme.typography.titleLarge)
                Text("From: ${transfer.senderDeviceId}")
                Text("Type: ${transfer.mimeType}")
                Text("Size: ${transfer.sizeBytes} bytes")
                val progress = if (transfer.sizeBytes == 0L) 1f else transfer.receivedBytes.toFloat() / transfer.sizeBytes
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text("${transfer.receivedBytes} / ${transfer.sizeBytes} bytes")
                Text("Status: ${state::class.simpleName}")
                when (state) {
                    is IncomingFileTransferState.Offered -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { shouldAccept = true }) { Text("Accept and choose location") }
                        OutlinedButton(onClick = manager::reject) { Text("Reject") }
                    }
                    is IncomingFileTransferState.Receiving -> OutlinedButton(onClick = manager::cancel) { Text("Cancel") }
                    is IncomingFileTransferState.Failed -> Text(
                        (state as IncomingFileTransferState.Failed).message,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Unit
                }
            }
        }
    }

    LaunchedEffect(shouldAccept, transfer?.fileName) {
        if (shouldAccept && transfer != null) createDocument.launch(transfer.fileName)
    }
}
