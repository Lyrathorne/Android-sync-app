package com.example.devicesync.core.data

import com.example.devicesync.core.protocol.ProtocolMessage
import kotlinx.coroutines.flow.Flow

interface OutgoingMessageQueue {
    suspend fun enqueue(message: ProtocolMessage)
    fun observePendingMessages(): Flow<List<PendingMessage>>
    suspend fun pendingForDevice(deviceId: String): List<PendingMessage>
    suspend fun markSent(messageId: String)
    suspend fun markAcknowledged(messageId: String)
    suspend fun markFailed(messageId: String, reason: String)
    suspend fun deleteForDevice(deviceId: String)
}
