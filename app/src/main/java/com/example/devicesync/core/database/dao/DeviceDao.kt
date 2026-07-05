package com.example.devicesync.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.devicesync.core.database.entity.DeviceEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY name")
    fun observeDevices(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices WHERE device_id = :deviceId")
    suspend fun getDevice(deviceId: String): DeviceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveDevice(device: DeviceEntity)

    @Query("UPDATE devices SET last_connected_at = :timestamp WHERE device_id = :deviceId")
    suspend fun updateLastConnectedAt(deviceId: String, timestamp: Instant)

    @Query("DELETE FROM devices WHERE device_id = :deviceId")
    suspend fun removeDevice(deviceId: String)
}
