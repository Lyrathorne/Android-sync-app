package com.example.devicesync.core.data

import java.time.Instant

data class PendingMessage(
    val messageId: String,
    val recipientDeviceId: String,
    val messageType: String,
    val serializedMessage: String,
    val createdAt: Instant,
    val attemptCount: Int,
    val requiresAcknowledgement: Boolean,
    val status: PendingMessageStatus,
    val lastAttemptAt: Instant?,
)

enum class PendingMessageStatus {
    PENDING,
    WAITING_ACK,
    ACKNOWLEDGED,
    FAILED,
}

enum class MessagePriority {
    HIGH,
    NORMAL,
    LOW,
}
