package com.example.devicesync.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.devicesync.core.database.entity.TrustedDeviceEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface TrustedDeviceDao {
    @Query("SELECT * FROM trusted_devices WHERE revoked_at IS NULL ORDER BY device_name")
    fun observeTrustedDevices(): Flow<List<TrustedDeviceEntity>>

    @Query("SELECT * FROM trusted_devices WHERE device_id = :deviceId")
    suspend fun getTrustedDevice(deviceId: String): TrustedDeviceEntity?

    @Upsert
    suspend fun saveTrustedDevice(device: TrustedDeviceEntity)

    @Query("UPDATE trusted_devices SET last_verified_at = :timestamp WHERE device_id = :deviceId")
    suspend fun updateLastVerifiedAt(deviceId: String, timestamp: Instant)

    @Query("UPDATE trusted_devices SET revoked_at = :timestamp WHERE device_id = :deviceId")
    suspend fun revoke(deviceId: String, timestamp: Instant)

    @Query("DELETE FROM trusted_devices WHERE device_id = :deviceId")
    suspend fun delete(deviceId: String)
}
