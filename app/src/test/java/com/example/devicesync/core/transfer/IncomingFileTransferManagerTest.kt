package com.example.devicesync.core.transfer

import com.example.devicesync.core.network.*
import com.example.devicesync.core.protocol.*
import com.example.devicesync.core.security.Base64Url
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.security.MessageDigest

class IncomingFileTransferManagerTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun acceptedFileIsStreamedVerifiedAndAcknowledged() = runTest {
        val transport = IncomingFakeTransport()
        val destination = MemoryDestination()
        val manager = IncomingFileTransferManager(transport, destination, this)
        val bytes = "hello from windows".encodeToByteArray()
        val transferId = "transfer-1"

        transport.deliver(message(ProtocolMessageType.FILE_OFFER, FileOfferPayload(
            transferId, "hello.txt", bytes.size.toLong(), "text/plain",
            Base64Url.encode(MessageDigest.getInstance("SHA-256").digest(bytes)), 65536,
        )))
        manager.accept("content://test/hello")?.join()
        advanceUntilIdle()
        transport.deliver(message(ProtocolMessageType.FILE_CHUNK, FileChunkPayload(
            transferId, 0, 0, java.util.Base64.getEncoder().encodeToString(bytes),
        )))
        transport.deliver(message(ProtocolMessageType.FILE_COMPLETE, FileCompletePayload(
            transferId, 1, bytes.size.toLong(),
        )))

        assertArrayEquals(bytes, destination.output.toByteArray())
        assertTrue(manager.state.value is IncomingFileTransferState.Completed)
        assertTrue(transport.sent.any { it.first == ProtocolMessageType.FILE_ACCEPT.value })
        assertTrue(transport.sent.any { it.first == ProtocolMessageType.FILE_RECEIVED.value })
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun v2DisconnectPersistsCheckpointAndResumes() = runTest {
        val transport = IncomingFakeTransport(v2 = true)
        val destination = MemoryDestination()
        val checkpoints = MemoryCheckpointStore()
        val first = IncomingFileTransferManager(transport, destination, this, checkpoints)
        val bytes = ByteArray(65536 + 5) { (it % 251).toByte() }
        val transferId = "550e8400-e29b-41d4-a716-446655440000"
        val fileHash = Base64Url.encode(MessageDigest.getInstance("SHA-256").digest(bytes))
        transport.deliver(message(ProtocolMessageType.FILE_OFFER, FileOfferPayload(
            transferId, "resume.bin", bytes.size.toLong(), "application/octet-stream", fileHash, 65536,
        )))
        first.accept("content://test/resume")?.join()
        advanceUntilIdle()
        val firstBytes = bytes.copyOfRange(0, 65536)
        transport.deliver(message(ProtocolMessageType.FILE_CHUNK, FileChunkPayload(
            transferId, 0, 0, java.util.Base64.getEncoder().encodeToString(firstBytes),
            Base64Url.encode(MessageDigest.getInstance("SHA-256").digest(firstBytes)),
        )))
        first.onFileTransferDisconnected()
        advanceUntilIdle()
        assertNotNull(checkpoints.load(transferId))
        first.close()

        val second = IncomingFileTransferManager(transport, destination, this, checkpoints)
        transport.deliver(message(ProtocolMessageType.FILE_RESUME_REQUEST, FileResumeRequestPayload(
            transferId, "resume.bin", bytes.size.toLong(), fileHash, 65536,
        )))
        val remaining = bytes.copyOfRange(65536, bytes.size)
        transport.deliver(message(ProtocolMessageType.FILE_CHUNK, FileChunkPayload(
            transferId, 1, 65536, java.util.Base64.getEncoder().encodeToString(remaining),
            Base64Url.encode(MessageDigest.getInstance("SHA-256").digest(remaining)),
        )))
        transport.deliver(message(ProtocolMessageType.FILE_COMPLETE, FileCompletePayload(transferId, 2, bytes.size.toLong())))

        assertArrayEquals(bytes, destination.output.toByteArray())
        assertTrue(second.state.value is IncomingFileTransferState.Completed)
        assertNull(checkpoints.load(transferId))
        assertTrue(transport.sent.any { it.first == ProtocolMessageType.FILE_RESUME_ACCEPTED.value })
        assertTrue(transport.sent.any { it.first == ProtocolMessageType.FILE_CHUNK_RECEIVED.value })
    }

    private inline fun <reified T> message(type: ProtocolMessageType, payload: T) = ProtocolMessage(
        protocolVersion = 1,
        messageId = "m-${type.value}",
        type = type.value,
        senderDeviceId = "windows-test",
        timestampUtc = "2026-07-14T00:00:00Z",
        payload = ProtocolSerializer.payloadToJson(payload),
    )

    private class MemoryDestination : IncomingFileDestination {
        val output = ByteArrayOutputStream()
        override fun open(uri: String) = output
        override fun openResume(uri: String, offset: Long) = output
        override fun openRead(uri: String) = ByteArrayInputStream(output.toByteArray())
        override fun delete(uri: String) { output.reset() }
    }

    private class IncomingFakeTransport(v2: Boolean = false) : FileTransferTransport {
        override val state = MutableStateFlow<ConnectionState>(if (v2) ConnectionState.Connected(
            "windows-test", "Windows", "127.0.0.1", 54321, 1,
            listOf(com.example.devicesync.core.network.SupportedCapabilities.FILE_TRANSFER_V1,
                com.example.devicesync.core.network.SupportedCapabilities.FILE_TRANSFER_V2),
        ) else ConnectionState.Authenticated("windows-test", "Windows"))
        val sent = mutableListOf<Pair<String, JsonElement>>()
        private val listeners = mutableSetOf<FileTransferMessageListener>()
        override suspend fun sendFileTransferMessage(type: String, payload: JsonElement) { sent += type to payload }
        override fun setFileTransferListener(listener: FileTransferMessageListener?) { listeners.clear(); if (listener != null) listeners += listener }
        override fun addFileTransferListener(listener: FileTransferMessageListener) { listeners += listener }
        override fun removeFileTransferListener(listener: FileTransferMessageListener) { listeners -= listener }
        suspend fun deliver(message: ProtocolMessage) { listeners.forEach { it.onFileTransferMessage(message) } }
    }

    private class MemoryCheckpointStore : IncomingTransferCheckpointStore {
        private val values = mutableMapOf<String, IncomingTransferCheckpoint>()
        override fun save(checkpoint: IncomingTransferCheckpoint) { values[checkpoint.transferId] = checkpoint }
        override fun load(transferId: String) = values[transferId]
        override fun delete(transferId: String) { values.remove(transferId) }
        override fun expired(cutoffMillis: Long) = values.values.filter { it.lastActivityAtMillis < cutoffMillis }
    }
}
