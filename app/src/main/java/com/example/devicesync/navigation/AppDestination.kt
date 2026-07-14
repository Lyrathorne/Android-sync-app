package com.example.devicesync.navigation

sealed class AppDestination(val route: String) {
    data object Devices : AppDestination("devices")
    data object AddDevice : AppDestination("add_device")
    data object ScanPairingQr : AppDestination("scan_pairing_qr")
    data object PairingVerification : AppDestination("pairing_verification")
    data object Settings : AppDestination("settings")
    data object SendFile : AppDestination("send_file")
    data object ReceiveFile : AppDestination("receive_file")
    data object Sharing : AppDestination("sharing")
    data object DeviceDetails : AppDestination("device_details/{deviceId}") {
        const val deviceIdArg = "deviceId"

        fun createRoute(deviceId: String): String = "device_details/$deviceId"
    }
}
