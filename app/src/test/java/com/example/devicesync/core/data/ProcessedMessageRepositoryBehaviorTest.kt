package com.example.devicesync.core.data

import com.example.devicesync.core.protocol.helloMessage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ProcessedMessageRepositoryBehaviorTest {
    @Test
    fun processedMessages_useSenderAndMessageIdAsKey() = runTest {
        val repository = FakeProcessedMessageRepository()
        val message = helloMessage(messageId = "same-id", senderDeviceId = "sender-a")

        assertFalse(repository.isProcessed("sender-a", "same-id"))
        repository.markProcessed(message)

        assertTrue(repository.isProcessed("sender-a", "same-id"))
        assertFalse(repository.isProcessed("sender-b", "same-id"))
    }

    @Test
    fun deleteOlderThan_removesOldEntries() = runTest {
        val repository = FakeProcessedMessageRepository()
        val message = helloMessage(messageId = "old-id", senderDeviceId = "sender-a")
        repository.markProcessed(message, Instant.parse("2026-07-01T00:00:00Z"))

        repository.deleteOlderThan(Instant.parse("2026-07-02T00:00:00Z"))

        assertFalse(repository.isProcessed("sender-a", "old-id"))
    }
}

private class FakeProcessedMessageRepository : ProcessedMessageRepository {
    private val messages = mutableMapOf<Pair<String, String>, Instant>()

    override suspend fun isProcessed(senderDeviceId: String, messageId: String): Boolean {
        return messages.containsKey(senderDeviceId to messageId)
    }

    override suspend fun markProcessed(message: com.example.devicesync.core.protocol.ProtocolMessage, processedAt: Instant) {
        messages[message.senderDeviceId to message.messageId] = processedAt
    }

    override suspend fun deleteOlderThan(timestamp: Instant) {
        messages.entries.removeIf { it.value < timestamp }
    }
}
