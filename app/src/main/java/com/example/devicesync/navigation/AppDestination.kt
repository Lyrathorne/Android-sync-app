package com.example.devicesync.navigation

sealed class AppDestination(val route: String) {
    data object Home : AppDestination("home")
    data object Files : AppDestination("files")
    data object Notifications : AppDestination("notifications")
    data object Diagnostics : AppDestination("diagnostics")
    data object ManualConnection : AppDestination("developer_manual_connection")
    data object Devices : AppDestination("devices")
    data object AddDevice : AppDestination("add_device")
    data object ScanPairingQr : AppDestination("scan_pairing_qr")
    data object PairingVerification : AppDestination("pairing_verification")
    data object Settings : AppDestination("settings")
    data object SendFile : AppDestination("send_file")
    data object ReceiveFile : AppDestination("receive_file")
    data object Sharing : AppDestination("sharing")
    data object KeyboardSettings : AppDestination("keyboard_settings")
    data object OpenSourceLicenses : AppDestination("open_source_licenses")
    data object DeviceDetails : AppDestination("device_details/{deviceId}") {
        const val deviceIdArg = "deviceId"

        fun createRoute(deviceId: String): String = "device_details/$deviceId"
    }
}
