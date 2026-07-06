package com.example.devicesync.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(tableName = "trusted_devices")
data class TrustedDeviceEntity(
    @PrimaryKey
    @ColumnInfo(name = "device_id")
    val deviceId: String,
    @ColumnInfo(name = "device_name")
    val deviceName: String,
    @ColumnInfo(name = "identity_public_key")
    val identityPublicKey: String,
    @ColumnInfo(name = "identity_fingerprint")
    val identityFingerprint: String,
    @ColumnInfo(name = "future_tls_certificate_fingerprint")
    val futureTlsCertificateFingerprint: String?,
    @ColumnInfo(name = "paired_at")
    val pairedAt: Instant,
    @ColumnInfo(name = "last_verified_at")
    val lastVerifiedAt: Instant?,
    @ColumnInfo(name = "revoked_at")
    val revokedAt: Instant?,
)
