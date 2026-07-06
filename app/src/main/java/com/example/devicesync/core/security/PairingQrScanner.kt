package com.example.devicesync.core.security

sealed interface PairingQrScanResult {
    data class Scanned(val rawPayload: String) : PairingQrScanResult
    data object Cancelled : PairingQrScanResult
    data object PermissionDenied : PairingQrScanResult
    data object PermissionPermanentlyDenied : PairingQrScanResult
    data class Failed(val message: String) : PairingQrScanResult
}

interface PairingQrScanner {
    suspend fun scan(): PairingQrScanResult
}
