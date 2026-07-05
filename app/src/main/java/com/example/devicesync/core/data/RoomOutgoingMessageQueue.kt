package com.example.devicesync.core.data

import com.example.devicesync.core.database.dao.PendingMessageDao
import com.example.devicesync.core.database.entity.PendingMessageEntity
import com.example.devicesync.core.protocol.ProtocolMessage
import com.example.devicesync.core.protocol.ProtocolSerializer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

class RoomOutgoingMessageQueue(
    private val pendingMessageDao: PendingMessageDao,
) : OutgoingMessageQueue {
    override suspend fun enqueue(message: ProtocolMessage) {
        pendingMessageDao.saveMessage(
            PendingMessageEntity(
                messageId = message.messageId,
                recipientDeviceId = message.recipientDeviceId.orEmpty(),
                messageType = message.type,
                serializedMessage = ProtocolSerializer.serialize(message),
                createdAt = Instant.now(),
                attemptCount = 0,
                requiresAcknowledgement = message.requiresAcknowledgement,
                status = PendingMessageStatus.PENDING.name,
                lastAttemptAt = null,
            )
        )
    }

    override fun observePendingMessages(): Flow<List<PendingMessage>> {
        return pendingMessageDao.observePendingMessages().map { messages ->
            messages.map { it.toPendingMessage() }
        }
    }

    override suspend fun pendingForDevice(deviceId: String): List<PendingMessage> {
        return pendingMessageDao.pendingForDevice(deviceId).map { it.toPendingMessage() }
    }

    override suspend fun markSent(messageId: String) {
        pendingMessageDao.markSent(messageId, PendingMessageStatus.WAITING_ACK.name, Instant.now())
    }

    override suspend fun markAcknowledged(messageId: String) {
        pendingMessageDao.updateStatus(messageId, PendingMessageStatus.ACKNOWLEDGED.name)
    }

    override suspend fun markFailed(messageId: String, reason: String) {
        pendingMessageDao.updateStatus(messageId, PendingMessageStatus.FAILED.name)
    }
}

private fun PendingMessageEntity.toPendingMessage(): PendingMessage {
    return PendingMessage(
        messageId = messageId,
        recipientDeviceId = recipientDeviceId,
        messageType = messageType,
        serializedMessage = serializedMessage,
        createdAt = createdAt,
        attemptCount = attemptCount,
        requiresAcknowledgement = requiresAcknowledgement,
        status = PendingMessageStatus.valueOf(status),
        lastAttemptAt = lastAttemptAt,
    )
}
