package com.example.devicesync.feature.sharing

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.devicesync.core.sharing.SharingManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.devicesync.core.foldersync.FolderSyncManager
import com.example.devicesync.core.foldersync.FolderConflictResolutions
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.example.devicesync.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharingScreen(
    manager: SharingManager,
    folderSyncManager: FolderSyncManager,
    onBackClick: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val history by manager.history.collectAsState()
    val pending by manager.pendingShareText.collectAsState()
    val folderPlan by folderSyncManager.lastPlan.collectAsState()
    val folderStatus by folderSyncManager.status.collectAsState()
    var conflictResolutions by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var text by remember { mutableStateOf("") }
    var clipboardEnabled by remember { mutableStateOf(manager.clipboardEnabled) }
    val clipboardDeviceId = manager.currentClipboardDeviceId()
    var clipboardDeviceEnabled by remember(clipboardDeviceId) { mutableStateOf(manager.isClipboardAllowedForCurrentDevice()) }
    var error by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf<String?>(null) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            scope.launch { runCatching { folderSyncManager.start(uri.toString()) }.onFailure { error = it.message } }
        }
    }
    LaunchedEffect(pending) {
        if (pending != null) { text = pending.orEmpty(); manager.consumeAndroidShare() }
    }
    LaunchedEffect(folderPlan?.syncId) { conflictResolutions = emptyMap() }
    Scaffold(topBar = { TopAppBar(title = { Text("Share text") }, navigationIcon = {
        TextButton(onClick = onBackClick) { Text("Back") }
    }) }) { padding ->
        Column(Modifier.padding(padding).padding(20.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row { Switch(clipboardEnabled, onCheckedChange = { clipboardEnabled = it; manager.clipboardEnabled = it }); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.clipboard_global_opt_in)) }
            Row {
                Switch(
                    checked = clipboardDeviceEnabled,
                    enabled = clipboardDeviceId != null,
                    onCheckedChange = {
                        if (manager.setClipboardAllowedForCurrentDevice(it)) clipboardDeviceEnabled = it
                    },
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.clipboard_device_permission))
            }
            if (clipboardDeviceId == null) Text(stringResource(R.string.clipboard_no_device), color = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.clipboard_android_limit))
            Text(stringResource(R.string.clipboard_private_note), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { folderPicker.launch(null) }) { Text("Select folder to compare") }
            folderPlan?.let { plan ->
                Text("Folder plan: ${plan.operations.count { it.action == "upload" }} upload, " +
                    "${plan.operations.count { it.action == "download" }} download, " +
                    "${plan.operations.count { it.action == "conflict" }} conflicts. Approval required.")
                plan.operations.filter { it.action == "conflict" }.forEach { conflict ->
                    Text(conflict.relativePath)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(
                            FolderConflictResolutions.KEEP_WINDOWS to "Windows",
                            FolderConflictResolutions.KEEP_ANDROID to "Android",
                            FolderConflictResolutions.KEEP_BOTH to "Both",
                        ).forEach { (value, label) ->
                            FilterChip(
                                selected = conflictResolutions[conflict.relativePath] == value,
                                onClick = { conflictResolutions = conflictResolutions + (conflict.relativePath to value) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
                Button(onClick = { scope.launch {
                    runCatching { folderSyncManager.approve(plan.syncId, conflictResolutions) }.onFailure { error = it.message }
                } }) { Text("Approve folder plan") }
            }
            Text(folderStatus)
            OutlinedTextField(text, { text = it }, label = { Text("Text or https:// link") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch {
                    error = null
                    success = null
                    runCatching { manager.sendText(text) }
                        .onSuccess { text = ""; success = "Text sent to the connected computer." }
                        .onFailure { error = it.message }
                } }) { Text("Send") }
                OutlinedButton(onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    val value = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    scope.launch {
                        error = null
                        success = null
                        runCatching { manager.sendClipboardNow(value) }
                            .onSuccess { success = "Clipboard text sent to the connected computer." }
                            .onFailure { error = it.message }
                    }
                }) { Text("Send clipboard") }
            }
            success?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("Recent items", style = MaterialTheme.typography.titleMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(history, key = { it.itemId }) { item ->
                    OutlinedButton(onClick = {
                        if (item.kind == "url") context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.text)))
                        else context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("DeviceSync", item.text))
                    }, modifier = Modifier.fillMaxWidth()) { Text(item.text, maxLines = 2) }
                }
            }
        }
    }
}
