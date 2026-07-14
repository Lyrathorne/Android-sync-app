package com.example.devicesync.core.transfer

import com.example.devicesync.core.network.ConnectionState
import com.example.devicesync.core.network.FileTransferMessageListener
import com.example.devicesync.core.network.FileTransferTransport
import com.example.devicesync.core.network.SupportedCapabilities
import com.example.devicesync.core.protocol.FileAcceptPayload
import com.example.devicesync.core.protocol.FileChunkPayload
import com.example.devicesync.core.protocol.FileReceivedPayload
import com.example.devicesync.core.protocol.FileRejectPayload
import com.example.devicesync.core.protocol.ProtocolMessage
import com.example.devicesync.core.protocol.ProtocolMessageType
import com.example.devicesync.core.protocol.ProtocolSerializer
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileTransferManagerTest {
    @Test
    fun successfulTransfer_sendsOfferChunksCompleteAndWaitsForReceived() = runTest {
        val bytes = ByteArray(FileTransferManager.CHUNK_SIZE + 3) { (it % 251).toByte() }
        val source = FakeMetadataSource(bytes)
        val transport = FakeTransport()
        val manager = FileTransferManager(source, transport, this, nowMillis = { 1_000L })

        val job = manager.start("content://test/file")
        runCurrent()
        val offerMessage = transport.sent.single()
        assertEquals(ProtocolMessageType.FILE_OFFER.value, offerMessage.type)
        val transferId = offerMessage.payload.jsonObjectValue("transferId")

        transport.deliver(
            ProtocolMessageType.FILE_ACCEPT,
            ProtocolSerializer.payloadToJson(FileAcceptPayload(transferId)),
        )
        runCurrent()

        val chunks = transport.sent.filter { it.type == ProtocolMessageType.FILE_CHUNK.value }
        assertEquals(2, chunks.size)
        val first = ProtocolSerializer.decodePayload<FileChunkPayload>(chunks[0].payload)
        val last = ProtocolSerializer.decodePayload<FileChunkPayload>(chunks[1].payload)
        assertEquals(0, first.index)
        assertEquals(0L, first.offset)
        assertEquals(FileTransferManager.CHUNK_SIZE, java.util.Base64.getDecoder().decode(first.data).size)
        assertEquals(1, last.index)
        assertEquals(FileTransferManager.CHUNK_SIZE.toLong(), last.offset)
        assertEquals(3, java.util.Base64.getDecoder().decode(last.data).size)
        assertEquals(ProtocolMessageType.FILE_COMPLETE.value, transport.sent.last().type)
        assertTrue(manager.state.value is FileTransferState.WaitingForReceipt)

        transport.deliver(
            ProtocolMessageType.FILE_RECEIVED,
            ProtocolSerializer.payloadToJson(
                FileReceivedPayload(transferId, bytes.size.toLong(), offerMessage.payload.jsonObjectValue("sha256"), "file.bin")
            ),
        )
        job.join()

        assertEquals(FileTransferState.Completed("file.bin"), manager.state.value)
        assertEquals(2, source.openCount)
    }

    @Test
    fun rejectedOffer_stopsBeforeOpeningSecondStream() = runTest {
        val source = FakeMetadataSource(byteArrayOf(1, 2, 3))
        val transport = FakeTransport()
        val manager = FileTransferManager(source, transport, this)

        val job = manager.start("content://test/file")
        runCurrent()
        val transferId = transport.sent.single().payload.jsonObjectValue("transferId")
        transport.deliver(
            ProtocolMessageType.FILE_REJECT,
            ProtocolSerializer.payloadToJson(FileRejectPayload(transferId, "user_rejected", "No")),
        )
        job.join()

        assertEquals(FileTransferState.Rejected("user_rejected", "No"), manager.state.value)
        assertEquals(1, source.openCount)
    }

    @Test
    fun cancel_sendsCancelMessageAndStopsTransfer() = runTest {
        val transport = FakeTransport()
        val manager = FileTransferManager(FakeMetadataSource(byteArrayOf(1)), transport, this)
        val job = manager.start("content://test/file")
        runCurrent()

        manager.cancel()
        runCurrent()

        assertTrue(transport.sent.any { it.type == ProtocolMessageType.FILE_CANCEL.value })
        assertTrue(manager.state.value is FileTransferState.Cancelled)
        assertTrue(job.isCancelled)
    }

    @Test
    fun disconnect_failsWaitingTransfer() = runTest {
        val transport = FakeTransport()
        val manager = FileTransferManager(FakeMetadataSource(byteArrayOf(1)), transport, this)
        val job = manager.start("content://test/file")
        runCurrent()

        transport.disconnect()
        runCurrent()

        assertEquals(FileTransferState.Failed("disconnected", "The connection was lost."), manager.state.value)
        assertTrue(job.isCancelled)
    }

    @Test
    fun providerCannotOpenSecondTime_reportsFailure() = runTest {
        val source = FakeMetadataSource(byteArrayOf(1, 2, 3), failSecondOpen = true)
        val transport = FakeTransport()
        val manager = FileTransferManager(source, transport, this)
        val job = manager.start("content://test/file")
        runCurrent()
        val transferId = transport.sent.single().payload.jsonObjectValue("transferId")

        transport.deliver(
            ProtocolMessageType.FILE_ACCEPT,
            ProtocolSerializer.payloadToJson(FileAcceptPayload(transferId)),
        )
        job.join()

        val state = manager.state.value as FileTransferState.Failed
        assertEquals("provider_error", state.code)
        assertEquals(2, source.openCount)
    }

    @Test
    fun metadataProviderError_isReported() = runTest {
        val transport = FakeTransport()
        val source = object : FileMetadataSource {
            override fun read(uri: String): FileMetadata = throw FileMetadataException("missing_size", "No size")
            override fun open(uri: String): InputStream = error("not used")
        }
        val manager = FileTransferManager(source, transport, this)

        manager.start("content://test/file").join()

        val state = manager.state.value as FileTransferState.Failed
        assertEquals("provider_error", state.code)
        assertTrue(transport.sent.isEmpty())
    }

    private class FakeMetadataSource(
        private val bytes: ByteArray,
        private val failSecondOpen: Boolean = false,
    ) : FileMetadataSource {
        var openCount = 0
            private set

        override fun read(uri: String) = FileMetadata("file.bin", bytes.size.toLong(), "application/octet-stream")

        override fun open(uri: String): InputStream {
            openCount++
            if (failSecondOpen && openCount == 2) throw FileNotFoundException("Cannot reopen")
            return ByteArrayInputStream(bytes)
        }
    }

    private class FakeTransport : FileTransferTransport {
        override val state = MutableStateFlow<ConnectionState>(
            ConnectionState.Connected(
                deviceId = "windows-test",
                deviceName = "Windows test",
                host = "127.0.0.1",
                port = 54321,
                acceptedProtocolVersion = 1,
                capabilities = listOf(SupportedCapabilities.FILE_TRANSFER_V1),
            )
        )
        val sent = mutableListOf<SentMessage>()
        private var listener: FileTransferMessageListener? = null

        override suspend fun sendFileTransferMessage(type: String, payload: kotlinx.serialization.json.JsonElement) {
            sent += SentMessage(type, payload)
        }

        override fun setFileTransferListener(listener: FileTransferMessageListener?) {
            this.listener = listener
        }

        suspend fun deliver(type: ProtocolMessageType, payload: JsonElement) {
            listener!!.onFileTransferMessage(
                ProtocolMessage(
                    protocolVersion = 1,
                    messageId = "response-${sent.size}",
                    type = type.value,
                    senderDeviceId = "windows-test",
                    recipientDeviceId = "android-test",
                    timestampUtc = "2026-07-14T10:00:00Z",
                    payload = payload,
                )
            )
        }

        fun disconnect() = listener!!.onFileTransferDisconnected()
    }

    private data class SentMessage(
        val type: String,
        val payload: kotlinx.serialization.json.JsonElement,
    )

    private fun JsonElement.jsonObjectValue(name: String): String =
        jsonObject.getValue(name).jsonPrimitive.content
}
