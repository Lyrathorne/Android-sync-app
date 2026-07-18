package com.example.devicesync.ui.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import com.example.devicesync.R
import com.example.devicesync.ui.theme.DeviceSyncThemeTokens

enum class DeviceSyncStatus(@StringRes val labelRes: Int) {
    Connected(R.string.connection_connected),
    Syncing(R.string.status_syncing),
    Offline(R.string.connection_offline),
    Error(R.string.connection_error),
    Attention(R.string.status_attention),
}

@Composable
private fun DeviceSyncStatus.color(): Color = when (this) {
    DeviceSyncStatus.Connected -> DeviceSyncThemeTokens.status.connected
    DeviceSyncStatus.Syncing -> DeviceSyncThemeTokens.status.syncing
    DeviceSyncStatus.Offline -> DeviceSyncThemeTokens.status.offline
    DeviceSyncStatus.Error -> DeviceSyncThemeTokens.status.error
    DeviceSyncStatus.Attention -> DeviceSyncThemeTokens.status.attention
}

@Composable
fun StatusLabel(status: DeviceSyncStatus, modifier: Modifier = Modifier) {
    val label = stringResource(status.labelRes)
    val statusDescription = stringResource(R.string.a11y_status, label)
    Row(
        modifier = modifier.semantics { contentDescription = statusDescription },
        horizontalArrangement = Arrangement.spacedBy(DeviceSyncThemeTokens.spacing.xs),
    ) {
        Box(
            Modifier
                .padding(top = 5.dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(status.color())
        )
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun DeviceSyncCard(
    modifier: Modifier = Modifier,
    contentDescription: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier
            .semantics { this.contentDescription = contentDescription }
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(DeviceSyncThemeTokens.spacing.md), content = content)
    }
}

@Composable
fun DeviceCard(name: String, status: DeviceSyncStatus, detail: String, modifier: Modifier = Modifier) {
    DeviceSyncCard(modifier, stringResource(R.string.a11y_device, name, stringResource(status.labelRes))) {
        Text(name, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(DeviceSyncThemeTokens.spacing.xs))
        StatusLabel(status)
        Spacer(Modifier.height(DeviceSyncThemeTokens.spacing.sm))
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun FileTransferCard(
    fileName: String,
    progress: Float,
    progressText: String,
    modifier: Modifier = Modifier,
) {
    val safeProgress = progress.coerceIn(0f, 1f)
    DeviceSyncCard(modifier, stringResource(R.string.a11y_file_progress, fileName, (safeProgress * 100).toInt())) {
        Text(fileName, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
        Text(progressText, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(DeviceSyncThemeTokens.spacing.md))
        LinearProgressIndicator(
            progress = { safeProgress },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(MaterialTheme.shapes.extraSmall),
        )
    }
}

@Composable
fun ClipboardCard(preview: String, metadata: String, modifier: Modifier = Modifier) {
    DeviceSyncCard(modifier, stringResource(R.string.a11y_clipboard_item, metadata)) {
        Text(stringResource(R.string.clipboard_item_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(DeviceSyncThemeTokens.spacing.xs))
        Text(preview, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(DeviceSyncThemeTokens.spacing.xs))
        Text(metadata, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun NotificationCard(appName: String, title: String, body: String, modifier: Modifier = Modifier) {
    DeviceSyncCard(modifier, stringResource(R.string.a11y_notification, appName, title)) {
        Text(appName, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(body, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun LoadingSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.semantics { contentDescription = "Loading" },
        verticalArrangement = Arrangement.spacedBy(DeviceSyncThemeTokens.spacing.xs),
    ) {
        Box(Modifier.fillMaxWidth(.72f).height(18.dp).clip(MaterialTheme.shapes.extraSmall).background(MaterialTheme.colorScheme.surfaceVariant))
        Box(Modifier.fillMaxWidth(.46f).height(14.dp).clip(MaterialTheme.shapes.extraSmall).background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

@Composable
fun EmptyState(title: String, message: String, actionLabel: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(DeviceSyncThemeTokens.spacing.sm)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onAction, modifier = Modifier.height(48.dp)) { Text(actionLabel) }
    }
}

@Composable
fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(DeviceSyncThemeTokens.spacing.sm)) {
        Text(stringResource(R.string.generic_error_title), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleLarge)
        Text(message, style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(onClick = onRetry, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.retry)) }
    }
}
