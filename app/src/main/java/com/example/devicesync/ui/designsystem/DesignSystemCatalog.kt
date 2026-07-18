package com.example.devicesync.ui.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.devicesync.core.settings.ThemeMode
import com.example.devicesync.ui.theme.DeviceSyncTheme
import com.example.devicesync.ui.theme.DeviceSyncThemeTokens

@Composable
fun DesignSystemCatalog(modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(DeviceSyncThemeTokens.spacing.md),
            verticalArrangement = Arrangement.spacedBy(DeviceSyncThemeTokens.spacing.lg),
        ) {
            Column {
                Text("DeviceSync design system", style = MaterialTheme.typography.headlineSmall)
                Text("Development catalog", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Column(verticalArrangement = Arrangement.spacedBy(DeviceSyncThemeTokens.spacing.sm)) {
                Text("Status", style = MaterialTheme.typography.titleLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DeviceSyncThemeTokens.spacing.md)) {
                    StatusLabel(DeviceSyncStatus.Connected)
                    StatusLabel(DeviceSyncStatus.Syncing)
                    StatusLabel(DeviceSyncStatus.Attention)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DeviceSyncThemeTokens.spacing.md)) {
                    StatusLabel(DeviceSyncStatus.Offline)
                    StatusLabel(DeviceSyncStatus.Error)
                }
            }

            DeviceCard("Pixel 9", DeviceSyncStatus.Connected, "Clipboard and files are ready", Modifier.fillMaxWidth())
            FileTransferCard("holiday-photo-with-a-long-name.jpg", .68f, "6.8 MB of 10 MB", Modifier.fillMaxWidth())
            ClipboardCard(
                "A multiline clipboard preview wraps and remains useful with increased font scale.",
                "From phone · 2 min ago",
                Modifier.fillMaxWidth(),
            )
            NotificationCard(
                "Messages", "New message", "Notification content stays readable and does not overlap actions.",
                Modifier.fillMaxWidth(),
            )
            LoadingSkeleton(Modifier.fillMaxWidth())
            EmptyState("No devices yet", "Pair a trusted phone to begin.", "Pair device", {}, Modifier.fillMaxWidth())
            ErrorState("The connection was interrupted. Your files are safe.", {}, Modifier.fillMaxWidth())
        }
    }
}

@Preview(name = "Light", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun CatalogLightPreview() = DeviceSyncTheme { DesignSystemCatalog() }

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun CatalogDarkPreview() = DeviceSyncTheme(themeMode = ThemeMode.DARK) { DesignSystemCatalog() }

@Preview(name = "High contrast 200% text", showBackground = true, widthDp = 420, heightDp = 900, fontScale = 2f)
@Composable
private fun CatalogHighContrastPreview() = DeviceSyncTheme(highContrast = true) { DesignSystemCatalog() }
