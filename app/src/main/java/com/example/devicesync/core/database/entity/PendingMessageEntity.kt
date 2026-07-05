package com.example.devicesync.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(tableName = "pending_messages")
data class PendingMessageEntity(
    @PrimaryKey
    @ColumnInfo(name = "message_id")
    val messageId: String,
    @ColumnInfo(name = "recipient_device_id")
    val recipientDeviceId: String,
    @ColumnInfo(name = "message_type")
    val messageType: String,
    @ColumnInfo(name = "serialized_message")
    val serializedMessage: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int,
    @ColumnInfo(name = "requires_acknowledgement")
    val requiresAcknowledgement: Boolean,
    val status: String,
    @ColumnInfo(name = "last_attempt_at")
    val lastAttemptAt: Instant?,
)
