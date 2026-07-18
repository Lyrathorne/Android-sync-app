package com.example.devicesync.feature.hubs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.devicesync.core.transfer.TransferHistoryEntry
import com.example.devicesync.R
import com.example.devicesync.ui.designsystem.EmptyState
import com.example.devicesync.ui.theme.DeviceSyncTheme

@Composable
fun FilesHubScreen(
    transfers: List<TransferHistoryEntry>,
    onSendFileClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(stringResource(R.string.files_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(R.string.files_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onSendFileClick, modifier = Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.send_a_file)) }
        }
        if (transfers.isEmpty()) {
            item {
                EmptyState(
                    title = stringResource(R.string.files_empty_title),
                    message = stringResource(R.string.files_empty_body),
                    actionLabel = stringResource(R.string.send_a_file),
                    onAction = onSendFileClick,
                )
            }
        } else {
            items(transfers.size) { index ->
                val transfer = transfers[index]
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(transfer.fileName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(transferSummary(transfer))
                        transfer.detail?.takeIf(String::isNotBlank)?.let {
                            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NotificationsHubScreen(
    forwardingEnabled: Boolean,
    selectedAppCount: Int,
    onConfigureClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.notifications_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.notifications_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(if (forwardingEnabled) R.string.forwarding_on else R.string.forwarding_off), style = MaterialTheme.typography.titleLarge)
                Text(
                    if (forwardingEnabled) stringResource(R.string.selected_apps_count, selectedAppCount) else stringResource(R.string.forwarding_off_detail),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onConfigureClick) { Text(stringResource(R.string.configure_notifications)) }
            }
        }
        Text(stringResource(R.string.sensitive_notifications_note), style = MaterialTheme.typography.bodySmall)
    }
}

@Preview(showBackground = true)
@Composable
private fun FilesHubPreview() = DeviceSyncTheme {
    FilesHubScreen(listOf(TransferHistoryEntry("1", "incoming", "Document.pdf", "completed")), {})
}

@Preview(showBackground = true)
@Composable
private fun NotificationsHubPreview() = DeviceSyncTheme { NotificationsHubScreen(true, 3, {}) }

@Composable
private fun transferSummary(entry: TransferHistoryEntry): String {
    val direction = stringResource(if (entry.direction == "incoming") R.string.direction_incoming else R.string.direction_outgoing)
    val status = stringResource(
        when (entry.status) {
            "completed" -> R.string.transfer_status_completed
            "failed", "waiting_retry" -> R.string.transfer_status_failed
            "cancelled" -> R.string.transfer_status_cancelled
            "offered" -> R.string.transfer_status_offered
            else -> R.string.transfer_status_queued
        }
    )
    return stringResource(R.string.transfer_summary, direction, status)
}
