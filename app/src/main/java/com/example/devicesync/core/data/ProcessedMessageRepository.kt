package com.example.devicesync.core.data

import com.example.devicesync.core.protocol.ProtocolMessage
import java.time.Instant

interface ProcessedMessageRepository {
    suspend fun isProcessed(senderDeviceId: String, messageId: String): Boolean
    suspend fun markProcessed(message: ProtocolMessage, processedAt: Instant = Instant.now())
    suspend fun deleteOlderThan(timestamp: Instant)
}
