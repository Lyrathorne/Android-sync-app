package com.example.devicesync.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.devicesync.DeviceSyncApplication
import com.example.devicesync.feature.add_device.AddDeviceRoute
import com.example.devicesync.feature.add_device.AddDeviceViewModel
import com.example.devicesync.feature.device_details.DeviceDetailsRoute
import com.example.devicesync.feature.device_details.DeviceDetailsViewModel
import com.example.devicesync.feature.devices.DevicesRoute
import com.example.devicesync.feature.devices.DevicesViewModel
import com.example.devicesync.feature.settings.SettingsRoute
import com.example.devicesync.feature.settings.SettingsViewModel

@Composable
fun DeviceSyncNavHost() {
    val navController = rememberNavController()
    val application = LocalContext.current.applicationContext as DeviceSyncApplication
    val container = application.container

    NavHost(
        navController = navController,
        startDestination = AppDestination.Devices.route,
    ) {
        composable(AppDestination.Devices.route) {
            val devicesViewModel: DevicesViewModel = viewModel(
                factory = DevicesViewModel.Factory(container.deviceRepository),
            )
            DevicesRoute(
                onAddDeviceClick = { navController.navigate(AppDestination.AddDevice.route) },
                onSettingsClick = { navController.navigate(AppDestination.Settings.route) },
                onDeviceClick = { deviceId ->
                    navController.navigate(AppDestination.DeviceDetails.createRoute(deviceId))
                },
                viewModel = devicesViewModel,
            )
        }
        composable(AppDestination.AddDevice.route) {
            val addDeviceViewModel: AddDeviceViewModel = viewModel(
                factory = AddDeviceViewModel.Factory(
                    container.deviceRepository,
                    container.connectionManager,
                ),
            )
            AddDeviceRoute(
                onBackClick = navController::popBackStack,
                onConnected = { deviceId ->
                    navController.navigate(AppDestination.DeviceDetails.createRoute(deviceId)) {
                        popUpTo(AppDestination.Devices.route)
                    }
                },
                viewModel = addDeviceViewModel,
            )
        }
        composable(AppDestination.Settings.route) {
            val settingsViewModel: SettingsViewModel = viewModel(
                factory = SettingsViewModel.Factory(container.settingsRepository),
            )
            SettingsRoute(
                onBackClick = navController::popBackStack,
                viewModel = settingsViewModel,
            )
        }
        composable(
            route = AppDestination.DeviceDetails.route,
            arguments = listOf(
                navArgument(AppDestination.DeviceDetails.deviceIdArg) {
                    type = NavType.StringType
                }
            ),
        ) { backStackEntry ->
            val deviceDetailsViewModel: DeviceDetailsViewModel = viewModel(
                factory = DeviceDetailsViewModel.Factory(
                    container.deviceRepository,
                    container.connectionManager,
                ),
            )
            DeviceDetailsRoute(
                deviceId = backStackEntry.arguments
                    ?.getString(AppDestination.DeviceDetails.deviceIdArg)
                    .orEmpty(),
                onBackClick = navController::popBackStack,
                viewModel = deviceDetailsViewModel,
            )
        }
    }
}
