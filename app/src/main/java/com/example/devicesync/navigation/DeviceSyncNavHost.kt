package com.example.devicesync.navigation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.annotation.StringRes
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.example.devicesync.DeviceSyncApplication
import com.example.devicesync.R
import com.example.devicesync.core.network.NetworkLogger
import com.example.devicesync.core.security.PairingQrParser
import com.example.devicesync.core.transfer.IncomingFileTransferState
import com.example.devicesync.feature.add_device.AddDeviceRoute
import com.example.devicesync.feature.add_device.AddDeviceViewModel
import com.example.devicesync.feature.add_device.PairingVerificationRoute
import com.example.devicesync.feature.add_device.ScanPairingQrScreen
import com.example.devicesync.feature.device_details.DeviceDetailsRoute
import com.example.devicesync.feature.device_details.DeviceDetailsViewModel
import com.example.devicesync.feature.devices.DevicesRoute
import com.example.devicesync.feature.devices.DevicesViewModel
import com.example.devicesync.feature.diagnostics.DiagnosticsScreen
import com.example.devicesync.feature.home.HomeRoute
import com.example.devicesync.feature.home.HomeViewModel
import com.example.devicesync.feature.hubs.FilesHubScreen
import com.example.devicesync.feature.notifications.NotificationSettingsScreen
import com.example.devicesync.feature.keyboard_settings.KeyboardOnboardingScreen
import com.example.devicesync.feature.keyboard_settings.OpenSourceLicensesScreen
import com.example.devicesync.feature.receive_file.ReceiveFileScreen
import com.example.devicesync.feature.send_file.SendFileRoute
import com.example.devicesync.feature.send_file.SendFileViewModel
import com.example.devicesync.feature.settings.SettingsRoute
import com.example.devicesync.feature.settings.SettingsViewModel
import com.example.devicesync.feature.sharing.SharingScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private data class TopLevelDestination(val destination: AppDestination, @StringRes val labelRes: Int)

private val topLevelDestinations = listOf(
    TopLevelDestination(AppDestination.Home, R.string.nav_home),
    TopLevelDestination(AppDestination.Files, R.string.nav_files),
    TopLevelDestination(AppDestination.Notifications, R.string.nav_notifications),
    TopLevelDestination(AppDestination.KeyboardSettings, R.string.nav_keyboard),
    TopLevelDestination(AppDestination.Settings, R.string.nav_settings),
)

@Composable
fun DeviceSyncNavHost(keyboardSettingsRequests: StateFlow<Long>? = null) {
    val navController = rememberNavController()
    val application = LocalContext.current.applicationContext as DeviceSyncApplication
    val container = application.container
    val incomingState by container.incomingFileTransferManager.state.collectAsState()
    val pendingShareText by container.sharingManager.pendingShareText.collectAsState()
    val keyboardSettingsRequest by (keyboardSettingsRequests ?: remember { MutableStateFlow(0L) }).collectAsState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    LaunchedEffect(keyboardSettingsRequest) {
        if (keyboardSettingsRequest > 0 && currentRoute != AppDestination.KeyboardSettings.route) {
            navController.navigate(AppDestination.KeyboardSettings.route) { launchSingleTop = true }
        }
    }
    LaunchedEffect(incomingState) {
        if (incomingState is IncomingFileTransferState.Offered && currentRoute != AppDestination.ReceiveFile.route) {
            navController.navigate(AppDestination.ReceiveFile.route) { launchSingleTop = true }
        }
    }
    LaunchedEffect(pendingShareText) {
        if (pendingShareText != null && currentRoute != AppDestination.Sharing.route) {
            navController.navigate(AppDestination.Sharing.route) { launchSingleTop = true }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (topLevelDestinations.any { it.destination.route == currentRoute }) {
                NavigationBar {
                    topLevelDestinations.forEach { item ->
                        val label = stringResource(item.labelRes)
                        NavigationBarItem(
                            modifier = Modifier.semantics {
                                contentDescription = application.getString(R.string.a11y_open_section, label)
                            },
                            selected = currentRoute == item.destination.route,
                            onClick = {
                                navController.navigate(item.destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Text(label.take(1)) },
                            label = { Text(label, maxLines = 1) },
                            alwaysShowLabel = false,
                        )
                    }
                }
            }
        },
    ) { contentPadding ->
        NavHost(
            navController = navController,
            startDestination = AppDestination.Home.route,
            modifier = Modifier.padding(contentPadding),
        ) {
            composable(AppDestination.Home.route) {
                val homeViewModel: HomeViewModel = viewModel(
                    factory = HomeViewModel.Factory(
                        container.connectionManager.state,
                        container.sharingManager,
                        container.transferHistoryRepository,
                        container.settingsRepository,
                    ),
                )
                HomeRoute(
                    viewModel = homeViewModel,
                    backgroundIssue = rememberBackgroundIssue(),
                    onComputersClick = { navController.navigate(AppDestination.Devices.route) },
                    onSendFileClick = { navController.navigate(AppDestination.SendFile.route) },
                    onShareTextClick = { navController.navigate(AppDestination.Sharing.route) },
                    onClipboardClick = { navController.navigate(AppDestination.Sharing.route) },
                    onSettingsClick = { navController.navigate(AppDestination.Settings.route) },
                )
            }
            composable(AppDestination.Files.route) {
                val transfers by container.transferHistoryRepository.entries.collectAsState()
                FilesHubScreen(transfers, onSendFileClick = { navController.navigate(AppDestination.SendFile.route) })
            }
            composable(AppDestination.Notifications.route) {
                NotificationSettingsScreen(container.notificationPreferences)
            }
            composable(AppDestination.Devices.route) {
                val devicesViewModel: DevicesViewModel = viewModel(
                    factory = DevicesViewModel.Factory(container.deviceRepository, container.connectionManager),
                )
                DevicesRoute(
                    onAddDeviceClick = { navController.navigate(AppDestination.AddDevice.route) },
                    onSettingsClick = { navController.navigate(AppDestination.Settings.route) },
                    onSharingClick = { navController.navigate(AppDestination.Sharing.route) },
                    onDeviceClick = { navController.navigate(AppDestination.DeviceDetails.createRoute(it)) },
                    viewModel = devicesViewModel,
                )
            }
            composable(AppDestination.AddDevice.route) {
                val addDeviceViewModel: AddDeviceViewModel = viewModel(
                    factory = AddDeviceViewModel.Factory(container.deviceRepository, container.connectionManager, container.discoveryService, container.bluetoothFallbackManager),
                )
                AddDeviceRoute(
                    onBackClick = navController::popBackStack,
                    onScanQrClick = { navController.navigate(AppDestination.ScanPairingQr.route) },
                    onConnected = { deviceId ->
                        navController.navigate(AppDestination.DeviceDetails.createRoute(deviceId)) {
                            popUpTo(AppDestination.Devices.route)
                        }
                    },
                    viewModel = addDeviceViewModel,
                )
            }
            composable(AppDestination.ScanPairingQr.route) {
                val parser = remember { PairingQrParser() }
                val scope = rememberCoroutineScope()
                var errorText by remember { mutableStateOf<String?>(null) }
                ScanPairingQrScreen(
                    onQrScanned = { raw ->
                        NetworkLogger.info("QR scan detected")
                        errorText = null
                        val payload = parser.parse(raw).onFailure { errorText = it.message ?: application.getString(R.string.qr_not_recognized) }.getOrNull()
                        if (payload != null) {
                            scope.launch { container.pairingCoordinator.startPairing(payload) }
                            navController.navigate(AppDestination.PairingVerification.route)
                            true
                        } else false
                    },
                    onClose = navController::popBackStack,
                )
                errorText?.let { Text(it) }
            }
            composable(AppDestination.PairingVerification.route) {
                PairingVerificationRoute(container.pairingCoordinator, navController::popBackStack)
            }
            composable(AppDestination.Settings.route) {
                val settingsViewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory(container.settingsRepository))
                SettingsRoute(
                    onBackClick = navController::popBackStack,
                    onKeyboardClick = { navController.navigate(AppDestination.KeyboardSettings.route) },
                    onDiagnosticsClick = { navController.navigate(AppDestination.Diagnostics.route) },
                    viewModel = settingsViewModel,
                )
            }
            composable(AppDestination.Diagnostics.route) {
                val connection by container.connectionManager.state.collectAsState()
                val discovery by container.discoveryService.state.collectAsState()
                val diagnostics by container.discoveryService.diagnostics.collectAsState()
                val networkDiagnostics by container.networkMonitor.diagnostics.collectAsState()
                DiagnosticsScreen(
                    connection,
                    discovery,
                    diagnostics,
                    networkDiagnostics,
                    navController::popBackStack,
                    onManualConnectionClick = { navController.navigate(AppDestination.ManualConnection.route) },
                )
            }
            composable(AppDestination.ManualConnection.route) {
                val addDeviceViewModel: AddDeviceViewModel = viewModel(
                    factory = AddDeviceViewModel.Factory(container.deviceRepository, container.connectionManager, container.discoveryService, container.bluetoothFallbackManager),
                )
                AddDeviceRoute(
                    onBackClick = navController::popBackStack,
                    onScanQrClick = { navController.navigate(AppDestination.ScanPairingQr.route) },
                    onConnected = { navController.navigate(AppDestination.Devices.route) },
                    showManualConnection = true,
                    viewModel = addDeviceViewModel,
                )
            }
            composable(
                route = AppDestination.KeyboardSettings.route,
                deepLinks = listOf(navDeepLink { uriPattern = "devicesync://keyboard-settings" }),
            ) {
                KeyboardOnboardingScreen(navController::popBackStack) {
                    navController.navigate(AppDestination.OpenSourceLicenses.route)
                }
            }
            composable(AppDestination.OpenSourceLicenses.route) {
                OpenSourceLicensesScreen(navController::popBackStack)
            }
            composable(AppDestination.SendFile.route) {
                val sendFileViewModel: SendFileViewModel = viewModel(
                    factory = SendFileViewModel.Factory(container.outgoingTransferQueue, container.fileMetadataReader, container.connectionManager),
                )
                SendFileRoute(navController::popBackStack, sendFileViewModel)
            }
            composable(AppDestination.ReceiveFile.route) {
                ReceiveFileScreen(container.incomingFileTransferManager, navController::popBackStack)
            }
            composable(AppDestination.Sharing.route) {
                SharingScreen(
                    container.sharingManager,
                    container.folderSyncManager,
                    navController::popBackStack,
                )
            }
            composable(
                route = AppDestination.DeviceDetails.route,
                arguments = listOf(navArgument(AppDestination.DeviceDetails.deviceIdArg) { type = NavType.StringType }),
            ) { entry ->
                val deviceDetailsViewModel: DeviceDetailsViewModel = viewModel(
                    factory = DeviceDetailsViewModel.Factory(container.deviceRepository, container.connectionManager),
                )
                DeviceDetailsRoute(
                    deviceId = entry.arguments?.getString(AppDestination.DeviceDetails.deviceIdArg).orEmpty(),
                    onBackClick = navController::popBackStack,
                    onSendFileClick = { navController.navigate(AppDestination.SendFile.route) },
                    viewModel = deviceDetailsViewModel,
                )
            }
        }
    }
}

@Composable
private fun rememberBackgroundIssue(): Int? {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var issue by remember { mutableStateOf<Int?>(null) }
    fun refresh() {
        val powerManager = context.getSystemService(PowerManager::class.java)
        issue = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                R.string.background_notifications_missing
            !powerManager.isIgnoringBatteryOptimizations(context.packageName) ->
                R.string.background_battery_optimized
            else -> null
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycleOwner.lifecycle.addObserver(observer)
        refresh()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return issue
}
