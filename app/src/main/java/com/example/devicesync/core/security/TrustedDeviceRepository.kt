package com.example.devicesync.core.security

import kotlinx.coroutines.flow.Flow
import java.time.Instant

data class TrustedDevice(
    val deviceId: String,
    val deviceName: String,
    val identityPublicKey: String,
    val identityFingerprint: String,
    val futureTlsCertificateFingerprint: String?,
    val pairedAt: Instant,
    val lastVerifiedAt: Instant?,
    val revokedAt: Instant?,
)

interface TrustedDeviceRepository {
    fun observeTrustedDevices(): Flow<List<TrustedDevice>>
    suspend fun getTrustedDevice(deviceId: String): TrustedDevice?
    suspend fun saveTrustedDevice(device: TrustedDevice)
    suspend fun updateLastVerifiedAt(deviceId: String, timestamp: Instant)
    suspend fun revoke(deviceId: String, timestamp: Instant = Instant.now())
    suspend fun delete(deviceId: String)
}
