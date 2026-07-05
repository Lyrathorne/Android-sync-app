package com.example.devicesync.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey
    @ColumnInfo(name = "device_id")
    val deviceId: String,
    val name: String,
    val host: String,
    val port: Int,
    @ColumnInfo(name = "protocol_version")
    val protocolVersion: Int,
    val capabilities: List<String>,
    @ColumnInfo(name = "last_connected_at")
    val lastConnectedAt: Instant?,
    @ColumnInfo(name = "auto_connect_enabled")
    val autoConnectEnabled: Boolean,
)
