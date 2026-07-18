package com.example.devicesync.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.devicesync.core.transfer.TransferHistoryEntry
import com.example.devicesync.R
import com.example.devicesync.ui.designsystem.DeviceSyncCard
import com.example.devicesync.ui.designsystem.DeviceSyncStatus
import com.example.devicesync.ui.designsystem.StatusLabel
import com.example.devicesync.ui.theme.DeviceSyncTheme

@Composable
fun HomeRoute(
    viewModel: HomeViewModel,
    @androidx.annotation.StringRes backgroundIssue: Int?,
    onComputersClick: () -> Unit,
    onSendFileClick: () -> Unit,
    onShareTextClick: () -> Unit,
    onClipboardClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    HomeScreen(
        uiState = viewModel.uiState.collectAsStateWithLifecycle().value,
        backgroundIssue = backgroundIssue,
        onComputersClick = onComputersClick,
        onSendFileClick = onSendFileClick,
        onShareTextClick = onShareTextClick,
        onClipboardClick = onClipboardClick,
        onSettingsClick = onSettingsClick,
    )
}

@Composable
fun HomeScreen(
    uiState: HomeUiState,
    @androidx.annotation.StringRes backgroundIssue: Int?,
    onComputersClick: () -> Unit,
    onSendFileClick: () -> Unit,
    onShareTextClick: () -> Unit,
    onClipboardClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(stringResource(R.string.app_name), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(R.string.home_tagline), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            DeviceSyncCard(
                modifier = Modifier.fillMaxWidth().testTag("home_connection_card"),
                contentDescription = "Computer connection",
            ) {
                Text(uiState.connection.computerName ?: stringResource(R.string.computer), style = MaterialTheme.typography.titleLarge)
                StatusLabel(uiState.connection.status)
                Text(stringResource(uiState.connection.titleRes), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(uiState.connection.detailRes), color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onComputersClick) {
                    Text(stringResource(if (uiState.connection.status == DeviceSyncStatus.Connected) R.string.manage_computers else R.string.choose_computer))
                }
            }
        }
        item {
            Text(stringResource(R.string.quick_actions), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            QuickActions(onSendFileClick, onShareTextClick, onClipboardClick, onComputersClick)
        }
        if (!uiState.backgroundWorkEnabled || backgroundIssue != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("home_background_issue"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.background_attention_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(backgroundIssue ?: R.string.background_disabled))
                        Button(onClick = onSettingsClick) { Text(stringResource(R.string.review_settings)) }
                    }
                }
            }
        }
        item { Text(stringResource(R.string.recent_transfers), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge) }
        if (uiState.transfers.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.no_transfers),
                    modifier = Modifier.testTag("home_empty"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(minOf(uiState.transfers.size, 4)) { index -> TransferHistoryRow(uiState.transfers[index]) }
        }
        if (uiState.sharedItems.isNotEmpty()) {
            item { Text(stringResource(R.string.recent_text), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge) }
            items(minOf(uiState.sharedItems.size, 3)) { index ->
                val item = uiState.sharedItems[index]
                Card(Modifier.fillMaxWidth()) {
                    Text(item.text, Modifier.padding(16.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun TransferHistoryRow(entry: TransferHistoryEntry) {
    Card(Modifier.fillMaxWidth().testTag("home_transfer_history")) {
        Column(Modifier.padding(16.dp)) {
            Text(entry.fileName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(transferSummary(entry), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeConnectedPreview() = DeviceSyncTheme {
    HomeScreen(
        HomeUiState(
            connection = HomeConnectionUi(DeviceSyncStatus.Connected, R.string.status_connected_title, R.string.status_connected_detail, "My laptop"),
            transfers = listOf(TransferHistoryEntry("1", "outgoing", "Photo.jpg", "completed")),
        ), null, {}, {}, {}, {}, {},
    )
}

@Preview(showBackground = true)
@Composable
private fun HomeOfflinePreview() = DeviceSyncTheme {
    HomeScreen(HomeUiState(), R.string.background_battery_optimized, {}, {}, {}, {}, {})
}

@Preview(showBackground = true)
@Composable
private fun HomeConnectingPreview() = DeviceSyncTheme {
    HomeScreen(
        HomeUiState(connection = HomeConnectionUi(DeviceSyncStatus.Syncing, R.string.status_connecting_title, R.string.status_connecting_detail)),
        null, {}, {}, {}, {}, {},
    )
}

@Preview(showBackground = true)
@Composable
private fun HomeErrorPreview() = DeviceSyncTheme {
    HomeScreen(
        HomeUiState(connection = HomeConnectionUi(DeviceSyncStatus.Error, R.string.status_problem_title, R.string.status_problem_detail)),
        null, {}, {}, {}, {}, {},
    )
}

@Composable
private fun QuickActions(
    onSendFileClick: () -> Unit,
    onShareTextClick: () -> Unit,
    onClipboardClick: () -> Unit,
    onComputersClick: () -> Unit,
) {
    BoxWithConstraints {
        val stackButtons = maxWidth < 420.dp || LocalDensity.current.fontScale >= 1.3f
        if (stackButtons) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSendFileClick, Modifier.fillMaxWidth().testTag("quick_send_file")) { Text(stringResource(R.string.send_file)) }
                OutlinedButton(onClick = onShareTextClick, Modifier.fillMaxWidth().testTag("quick_send_text")) { Text(stringResource(R.string.send_text)) }
                OutlinedButton(onClick = onClipboardClick, Modifier.fillMaxWidth().testTag("quick_clipboard")) { Text(stringResource(R.string.clipboard)) }
                OutlinedButton(onClick = onComputersClick, Modifier.fillMaxWidth().testTag("quick_computers")) { Text(stringResource(R.string.computers)) }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSendFileClick, Modifier.weight(1f).testTag("quick_send_file")) { Text(stringResource(R.string.send_file)) }
                    OutlinedButton(onClick = onShareTextClick, Modifier.weight(1f).testTag("quick_send_text")) { Text(stringResource(R.string.send_text)) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onClipboardClick, Modifier.weight(1f).testTag("quick_clipboard")) { Text(stringResource(R.string.clipboard)) }
                    OutlinedButton(onClick = onComputersClick, Modifier.weight(1f).testTag("quick_computers")) { Text(stringResource(R.string.computers)) }
                }
            }
        }
    }
}

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
