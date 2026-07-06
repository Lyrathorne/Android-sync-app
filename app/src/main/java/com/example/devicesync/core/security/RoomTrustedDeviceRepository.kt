package com.example.devicesync.core.security

import com.example.devicesync.core.database.dao.TrustedDeviceDao
import com.example.devicesync.core.database.entity.TrustedDeviceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

class RoomTrustedDeviceRepository(
    private val dao: TrustedDeviceDao,
) : TrustedDeviceRepository {
    override fun observeTrustedDevices(): Flow<List<TrustedDevice>> {
        return dao.observeTrustedDevices().map { devices -> devices.map { it.toModel() } }
    }

    override suspend fun getTrustedDevice(deviceId: String): TrustedDevice? {
        return dao.getTrustedDevice(deviceId)?.toModel()
    }

    override suspend fun saveTrustedDevice(device: TrustedDevice) {
        dao.saveTrustedDevice(device.toEntity())
    }

    override suspend fun updateLastVerifiedAt(deviceId: String, timestamp: Instant) {
        dao.updateLastVerifiedAt(deviceId, timestamp)
    }

    override suspend fun revoke(deviceId: String, timestamp: Instant) {
        dao.revoke(deviceId, timestamp)
    }

    override suspend fun delete(deviceId: String) {
        dao.delete(deviceId)
    }
}

private fun TrustedDeviceEntity.toModel() = TrustedDevice(
    deviceId = deviceId,
    deviceName = deviceName,
    identityPublicKey = identityPublicKey,
    identityFingerprint = identityFingerprint,
    futureTlsCertificateFingerprint = futureTlsCertificateFingerprint,
    pairedAt = pairedAt,
    lastVerifiedAt = lastVerifiedAt,
    revokedAt = revokedAt,
)

private fun TrustedDevice.toEntity() = TrustedDeviceEntity(
    deviceId = deviceId,
    deviceName = deviceName,
    identityPublicKey = identityPublicKey,
    identityFingerprint = identityFingerprint,
    futureTlsCertificateFingerprint = futureTlsCertificateFingerprint,
    pairedAt = pairedAt,
    lastVerifiedAt = lastVerifiedAt,
    revokedAt = revokedAt,
)
