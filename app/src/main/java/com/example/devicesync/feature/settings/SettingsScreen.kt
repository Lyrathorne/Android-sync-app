package com.example.devicesync.feature.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.devicesync.R
import com.example.devicesync.BuildConfig
import com.example.devicesync.ui.theme.DeviceSyncTheme
import com.example.devicesync.core.background.ConnectionForegroundService
import com.example.devicesync.core.background.ConnectionRecoveryScheduler
import com.example.devicesync.DeviceSyncApplication

@Composable
fun SettingsRoute(
    onBackClick: () -> Unit,
    onKeyboardClick: () -> Unit,
    onDiagnosticsClick: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val uiState = viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    val container = (context.applicationContext as DeviceSyncApplication).container
    var documentsAccessEnabled by rememberSaveable {
        mutableStateOf(container.catalogAccessStore.isDocumentsAccessEnabled())
    }
    var allFilesAccessGranted by rememberSaveable {
        mutableStateOf(Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager())
    }
    val allFilesAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        allFilesAccessGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
        if (allFilesAccessGranted) {
            container.catalogAccessStore.setCatalogEnabled(true)
            container.catalogAccessStore.setDocumentsAccessEnabled(true)
            documentsAccessEnabled = true
        }
    }
    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants -> if (grants.values.any { it }) container.catalogAccessStore.setCatalogEnabled(true) }
    val catalogTreeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                container.catalogAccessStore.grantTree(uri)
                documentsAccessEnabled = true
            }
        }
    }
    var versionTapCount by rememberSaveable { mutableIntStateOf(0) }
    SettingsScreen(
        uiState = uiState.value,
        onBackClick = onBackClick,
        onAutoConnectChanged = viewModel::setAutoConnectTrustedComputers,
        onRestoreConnectionChanged = viewModel::setRestoreConnectionAfterDisconnect,
        onBackgroundWorkChanged = { enabled ->
            viewModel.setAllowBackgroundWork(enabled)
            ConnectionRecoveryScheduler.configure(context, enabled)
            if (enabled) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                ConnectionForegroundService.start(context, explicitlyEnabled = true)
            } else ConnectionForegroundService.stop(context)
        },
        onBackgroundSystemSettingsClick = {
            val powerManager = context.getSystemService(PowerManager::class.java)
            val action = if (powerManager.isIgnoringBatteryOptimizations(context.packageName))
                Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
            else
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            context.startActivity(Intent(action).apply {
                if (action == Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) {
                    data = Uri.parse("package:${context.packageName}")
                }
            })
        },
        onAppSystemSettingsClick = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            })
        },
        onDarkThemeChanged = viewModel::setUseDarkTheme,
        onGrantMediaAccessClick = {
            val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                buildList {
                    add(Manifest.permission.READ_MEDIA_IMAGES)
                    add(Manifest.permission.READ_MEDIA_VIDEO)
                    add(Manifest.permission.READ_MEDIA_AUDIO)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                    }
                }.toTypedArray()
            } else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            mediaPermissionLauncher.launch(permissions)
        },
        onChooseCatalogFolderClick = { catalogTreeLauncher.launch(null) },
        allFilesAccessGranted = allFilesAccessGranted,
        onRequestAllFilesAccessClick = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val appIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                val fallbackIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                allFilesAccessLauncher.launch(
                    if (appIntent.resolveActivity(context.packageManager) != null) appIntent else fallbackIntent,
                )
            }
        },
        documentsAccessEnabled = documentsAccessEnabled,
        onDocumentsAccessChanged = { enabled ->
            if (enabled && container.catalogAccessStore.selectedTreeUris().isEmpty()) {
                catalogTreeLauncher.launch(null)
            } else {
                container.catalogAccessStore.setDocumentsAccessEnabled(enabled)
                documentsAccessEnabled = enabled
                if (!enabled) container.mediaCatalogManager.revokeAllAccess()
            }
        },
        onRevokeCatalogAccessClick = {
            container.catalogAccessStore.selectedTreeUris().forEach { value ->
                runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(value), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
            container.catalogAccessStore.revokeAll()
            documentsAccessEnabled = false
            container.mediaCatalogManager.revokeAllAccess()
        },
        onKeyboardClick = onKeyboardClick,
        showDiagnostics = versionTapCount >= 7,
        onVersionClick = { versionTapCount = (versionTapCount + 1).coerceAtMost(7) },
        onDiagnosticsClick = onDiagnosticsClick,
        onAboutClick = viewModel::showAboutDialog,
        onDismissAboutDialog = viewModel::dismissAboutDialog,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onBackClick: () -> Unit,
    onAutoConnectChanged: (Boolean) -> Unit,
    onRestoreConnectionChanged: (Boolean) -> Unit,
    onBackgroundWorkChanged: (Boolean) -> Unit,
    onBackgroundSystemSettingsClick: () -> Unit,
    onAppSystemSettingsClick: () -> Unit,
    onDarkThemeChanged: (Boolean) -> Unit,
    onGrantMediaAccessClick: () -> Unit,
    onChooseCatalogFolderClick: () -> Unit,
    allFilesAccessGranted: Boolean,
    onRequestAllFilesAccessClick: () -> Unit,
    documentsAccessEnabled: Boolean,
    onDocumentsAccessChanged: (Boolean) -> Unit,
    onRevokeCatalogAccessClick: () -> Unit,
    onKeyboardClick: () -> Unit,
    showDiagnostics: Boolean,
    onVersionClick: () -> Unit,
    onDiagnosticsClick: () -> Unit,
    onAboutClick: () -> Unit,
    onDismissAboutDialog: () -> Unit,
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
                    Text(stringResource(R.string.back))
                }
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SettingSwitchRow(
                text = stringResource(R.string.auto_connect),
                checked = uiState.autoConnectTrustedComputers,
                onCheckedChange = onAutoConnectChanged,
            )
            Text(
                text = stringResource(R.string.phone_catalog_access_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            SettingSwitchRow(
                text = stringResource(R.string.documents_pc_access),
                checked = documentsAccessEnabled,
                onCheckedChange = onDocumentsAccessChanged,
            )
            Text(
                text = stringResource(R.string.documents_pc_access_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onRequestAllFilesAccessClick, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(
                        if (allFilesAccessGranted) R.string.all_files_access_granted
                        else R.string.grant_all_files_access,
                    ),
                )
            }
            TextButton(onClick = onGrantMediaAccessClick, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.grant_media_access))
            }
            TextButton(onClick = onChooseCatalogFolderClick, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.choose_catalog_folder))
            }
            TextButton(onClick = onRevokeCatalogAccessClick, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.revoke_catalog_access))
            }
            TextButton(
                onClick = onKeyboardClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.keyboard_title))
            }
            SettingSwitchRow(
                text = stringResource(R.string.restore_connection),
                checked = uiState.restoreConnectionAfterDisconnect,
                onCheckedChange = onRestoreConnectionChanged,
            )
            SettingSwitchRow(
                text = stringResource(R.string.background_work),
                checked = uiState.allowBackgroundWork,
                onCheckedChange = onBackgroundWorkChanged,
            )
            TextButton(
                onClick = onBackgroundSystemSettingsClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.allow_unrestricted_battery))
            }
            Text(
                stringResource(R.string.manufacturer_background_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.force_stop_background_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = onAppSystemSettingsClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.open_system_settings))
            }
            SettingSwitchRow(
                text = stringResource(R.string.dark_theme),
                checked = uiState.useDarkTheme,
                onCheckedChange = onDarkThemeChanged,
            )
            TextButton(
                onClick = onAboutClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.about_app))
            }
            TextButton(
                onClick = onVersionClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.version_label, BuildConfig.VERSION_NAME))
            }
            if (showDiagnostics) {
                TextButton(
                    onClick = onDiagnosticsClick,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.developer_diagnostics))
                }
            }
        }
    }

    if (uiState.showAboutDialog) {
        AlertDialog(
            onDismissRequest = onDismissAboutDialog,
            title = { Text(stringResource(R.string.about_app)) },
            text = { Text(stringResource(R.string.about_app_body)) },
            confirmButton = {
                TextButton(onClick = onDismissAboutDialog) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun SettingSwitchRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { }
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    DeviceSyncTheme {
        SettingsScreen(
            uiState = SettingsUiState(),
            onBackClick = {},
            onAutoConnectChanged = {},
            onRestoreConnectionChanged = {},
            onBackgroundWorkChanged = {},
            onBackgroundSystemSettingsClick = {},
            onAppSystemSettingsClick = {},
            onDarkThemeChanged = {},
        onGrantMediaAccessClick = {},
        onChooseCatalogFolderClick = {},
        allFilesAccessGranted = false,
        onRequestAllFilesAccessClick = {},
        documentsAccessEnabled = false,
            onDocumentsAccessChanged = {},
            onRevokeCatalogAccessClick = {},
            onKeyboardClick = {},
            showDiagnostics = false,
            onVersionClick = {},
            onDiagnosticsClick = {},
            onAboutClick = {},
            onDismissAboutDialog = {},
        )
    }
}
