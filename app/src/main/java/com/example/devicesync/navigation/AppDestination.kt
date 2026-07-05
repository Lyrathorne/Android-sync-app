package com.example.devicesync.navigation

sealed class AppDestination(val route: String) {
    data object Devices : AppDestination("devices")
    data object AddDevice : AppDestination("add_device")
    data object Settings : AppDestination("settings")
    data object DeviceDetails : AppDestination("device_details/{deviceId}") {
        const val deviceIdArg = "deviceId"

        fun createRoute(deviceId: String): String = "device_details/$deviceId"
    }
}
