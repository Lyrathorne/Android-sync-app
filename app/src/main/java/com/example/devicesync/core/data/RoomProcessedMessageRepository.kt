package com.example.devicesync.core.data

import com.example.devicesync.core.database.dao.ProcessedMessageDao
import com.example.devicesync.core.database.entity.ProcessedMessageEntity
import com.example.devicesync.core.protocol.ProtocolMessage
import java.time.Instant

class RoomProcessedMessageRepository(
    private val processedMessageDao: ProcessedMessageDao,
) : ProcessedMessageRepository {
    override suspend fun isProcessed(senderDeviceId: String, messageId: String): Boolean {
        return processedMessageDao.countProcessed(senderDeviceId, messageId) > 0
    }

    override suspend fun markProcessed(message: ProtocolMessage, processedAt: Instant) {
        processedMessageDao.markProcessed(
            ProcessedMessageEntity(
                messageId = message.messageId,
                senderDeviceId = message.senderDeviceId,
                messageType = message.type,
                processedAt = processedAt,
            )
        )
    }

    override suspend fun deleteOlderThan(timestamp: Instant) {
        processedMessageDao.deleteOlderThan(timestamp)
    }
}
