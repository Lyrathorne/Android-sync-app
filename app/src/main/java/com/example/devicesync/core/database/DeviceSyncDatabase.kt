package com.example.devicesync.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.devicesync.core.database.dao.DeviceDao
import com.example.devicesync.core.database.dao.PendingMessageDao
import com.example.devicesync.core.database.dao.ProcessedMessageDao
import com.example.devicesync.core.database.dao.TrustedDeviceDao
import com.example.devicesync.core.database.entity.DeviceEntity
import com.example.devicesync.core.database.entity.PendingMessageEntity
import com.example.devicesync.core.database.entity.ProcessedMessageEntity
import com.example.devicesync.core.database.entity.TrustedDeviceEntity

@Database(
    entities = [
        DeviceEntity::class,
        ProcessedMessageEntity::class,
        PendingMessageEntity::class,
        TrustedDeviceEntity::class,
    ],
    version = 2,
)
@TypeConverters(DeviceSyncTypeConverters::class)
abstract class DeviceSyncDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun processedMessageDao(): ProcessedMessageDao
    abstract fun pendingMessageDao(): PendingMessageDao
    abstract fun trustedDeviceDao(): TrustedDeviceDao
}
