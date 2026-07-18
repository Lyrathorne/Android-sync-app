package com.example.devicesync.feature.diagnostics

import android.Manifest
import android.app.ActivityManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.devicesync.BuildConfig
import com.example.devicesync.core.discovery.DiscoveryDiagnostics
import com.example.devicesync.core.discovery.DiscoveryState
import com.example.devicesync.core.diagnostics.StructuredDiagnosticLog
import com.example.devicesync.core.diagnostics.SupportBundleBuilder
import com.example.devicesync.core.diagnostics.SupportBundleSummary
import com.example.devicesync.core.network.ConnectionState
import com.example.devicesync.core.network.NetworkEnvironmentDiagnostics
import com.example.devicesync.core.network.PROTOCOL_MAX_VERSION
import com.example.devicesync.core.network.PROTOCOL_MIN_VERSION
import com.example.devicesync.core.network.SupportedCapabilities
import com.example.devicesync.R

@Composable
fun DiagnosticsScreen(
    connectionState: ConnectionState,
    discoveryState: DiscoveryState,
    diagnostics: DiscoveryDiagnostics,
    networkDiagnostics: NetworkEnvironmentDiagnostics,
    onBackClick: () -> Unit,
    onManualConnectionClick: () -> Unit,
) {
    val context = LocalContext.current
    val bundleBuilder = remember { SupportBundleBuilder(context) }
    val supportSummary = remember(connectionState) {
        SupportBundleSummary(
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            protocolVersion = "$PROTOCOL_MIN_VERSION..$PROTOCOL_MAX_VERSION",
            capabilities = SupportedCapabilities.values,
            connectionState = connectionSummary(connectionState),
            transport = (connectionState as? ConnectionState.Connected)?.transportKind?.name ?: "none",
            lastDisconnectReason = lastDisconnectReason(),
            permissionSummary = permissionSummary(context),
            backgroundRestriction = backgroundRestriction(context),
        )
    }
    var preview by remember { mutableStateOf<String?>(null) }
    var exportStatus by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) {
            exportStatus = "Экспорт отменён."
        } else {
            exportStatus = runCatching {
                context.contentResolver.openOutputStream(uri, "w")!!.use {
                    it.write(bundleBuilder.build(supportSummary))
                }
                "Обезличенный support bundle сохранён."
            }.getOrElse { "Не удалось сохранить bundle: ${it.javaClass.simpleName}" }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBackClick) { Text(stringResource(R.string.back)) }
        Text(stringResource(R.string.developer_diagnostics), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.diagnostics_warning))
        DiagnosticCard(stringResource(R.string.connection_state_label), connectionState.toString())
        DiagnosticCard(stringResource(R.string.discovery_state_label), discoveryState.toString())
        DiagnosticCard(
            stringResource(R.string.discovery_counters_label),
            stringResource(
                R.string.discovery_counters,
                diagnostics.foundServices,
                diagnostics.resolvedServices,
                diagnostics.lastCallback ?: stringResource(R.string.none),
                diagnostics.lastError ?: stringResource(R.string.none),
            ),
        )
        DiagnosticCard("Network interfaces (privacy-safe)", networkDiagnostics.privacySafeSummary())
        DiagnosticCard("App / protocol", "${supportSummary.appVersion}\nProtocol ${supportSummary.protocolVersion}")
        DiagnosticCard("Capabilities", supportSummary.capabilities.sorted().joinToString())
        DiagnosticCard("Transport", supportSummary.transport)
        DiagnosticCard("Last disconnect", supportSummary.lastDisconnectReason)
        DiagnosticCard("Permissions", supportSummary.permissionSummary)
        DiagnosticCard("Background restrictions", supportSummary.backgroundRestriction)
        DiagnosticCard(
            "Recent privacy-safe events",
            StructuredDiagnosticLog.recentLines(12).joinToString("\n").ifBlank { "No events recorded." },
        )
        OutlinedButton(
            onClick = { preview = bundleBuilder.preview(supportSummary) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Preview support bundle") }
        exportStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(onClick = onManualConnectionClick) { Text(stringResource(R.string.manual_connection)) }
    }

    preview?.let { text ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("Support bundle preview") },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = {
                    preview = null
                    exportLauncher.launch("devicesync-support-${BuildConfig.VERSION_NAME}.zip")
                }) { Text("Export") }
            },
            dismissButton = {
                TextButton(onClick = { preview = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DiagnosticCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun connectionSummary(state: ConnectionState): String = when (state) {
    ConnectionState.Disconnected -> "disconnected"
    is ConnectionState.Connecting -> "connecting"
    is ConnectionState.Handshaking -> "handshaking"
    is ConnectionState.AuthenticatingWindows -> "authenticating_windows"
    is ConnectionState.ProvingAndroidIdentity -> "proving_android_identity"
    is ConnectionState.Authenticated -> "authenticated"
    is ConnectionState.Connected -> "connected protocol=${state.acceptedProtocolVersion}"
    is ConnectionState.Reconnecting -> "reconnecting attempt=${state.attempt}"
    ConnectionState.NetworkUnavailable -> "network_unavailable"
    is ConnectionState.Failed -> "failed"
    is ConnectionState.IdentityChanged -> "identity_changed"
    ConnectionState.PairingRequired -> "pairing_required"
    ConnectionState.TrustRevoked -> "trust_revoked"
    is ConnectionState.AuthenticationFailed -> "authentication_failed"
}

private fun lastDisconnectReason(): String =
    StructuredDiagnosticLog.persistedLines().asReversed()
        .firstOrNull { it.contains("SESSION_TERMINATING") || it.contains("DISCONNECT") }
        ?.take(300)
        ?: "not recorded"

private fun permissionSummary(context: android.content.Context): String {
    val permissions = buildList {
        if (Build.VERSION.SDK_INT >= 33) {
            add("notifications" to Manifest.permission.POST_NOTIFICATIONS)
            add("media_images" to Manifest.permission.READ_MEDIA_IMAGES)
            add("media_video" to Manifest.permission.READ_MEDIA_VIDEO)
            add("media_audio" to Manifest.permission.READ_MEDIA_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 31) add("bluetooth" to Manifest.permission.BLUETOOTH_CONNECT)
    }
    return permissions.joinToString { (name, permission) ->
        "$name=${if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) "granted" else "missing"}"
    }.ifBlank { "No runtime permissions required for this Android version." }
}

private fun backgroundRestriction(context: android.content.Context): String {
    val manager = context.getSystemService(ActivityManager::class.java)
    return if (Build.VERSION.SDK_INT >= 28 && manager.isBackgroundRestricted) {
        "restricted"
    } else {
        "not_restricted"
    }
}
