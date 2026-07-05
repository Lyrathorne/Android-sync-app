package com.example.devicesync.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import java.time.Instant

@Entity(
    tableName = "processed_messages",
    primaryKeys = ["sender_device_id", "message_id"],
)
data class ProcessedMessageEntity(
    @ColumnInfo(name = "message_id")
    val messageId: String,
    @ColumnInfo(name = "sender_device_id")
    val senderDeviceId: String,
    @ColumnInfo(name = "message_type")
    val messageType: String,
    @ColumnInfo(name = "processed_at")
    val processedAt: Instant,
)
