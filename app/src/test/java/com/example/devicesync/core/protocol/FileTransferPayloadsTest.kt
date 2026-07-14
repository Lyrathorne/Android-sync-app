package com.example.devicesync.core.protocol

import com.example.devicesync.core.network.ConnectionException
import com.example.devicesync.core.network.SupportedCapabilities
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTransferPayloadsTest {
    private val transferId = "550e8400-e29b-41d4-a716-446655440000"
    private val sha256 = "ZOyIygCyaOW6GjVnihtTFtIS9PNmskdyMlNKiuyjfzw"

    @Test
    fun messageTypes_matchSharedContract() {
        assertEquals("file.offer", ProtocolMessageType.FILE_OFFER.value)
        assertEquals("file.accept", ProtocolMessageType.FILE_ACCEPT.value)
        assertEquals("file.reject", ProtocolMessageType.FILE_REJECT.value)
        assertEquals("file.chunk", ProtocolMessageType.FILE_CHUNK.value)
        assertEquals("file.complete", ProtocolMessageType.FILE_COMPLETE.value)
        assertEquals("file.received", ProtocolMessageType.FILE_RECEIVED.value)
        assertEquals("file.cancel", ProtocolMessageType.FILE_CANCEL.value)
        assertEquals("file.error", ProtocolMessageType.FILE_ERROR.value)
        assertEquals("file.chunk.received", ProtocolMessageType.FILE_CHUNK_RECEIVED.value)
        assertEquals("file.resume.request", ProtocolMessageType.FILE_RESUME_REQUEST.value)
        assertEquals("file.resume.accepted", ProtocolMessageType.FILE_RESUME_ACCEPTED.value)
    }

    @Test
    fun v2PayloadModels_roundTrip() {
        assertEquals(
            FileChunkReceivedPayload(transferId, 4, 262144),
            roundTrip(FileChunkReceivedPayload(transferId, 4, 262144)),
        )
        assertEquals(
            FileResumeRequestPayload(transferId, "photo.jpg", 1258291, sha256, 65536),
            roundTrip(FileResumeRequestPayload(transferId, "photo.jpg", 1258291, sha256, 65536)),
        )
        assertEquals(
            FileResumeAcceptedPayload(transferId, 4, 262144),
            roundTrip(FileResumeAcceptedPayload(transferId, 4, 262144)),
        )
    }

    @Test
    fun supportedCapabilities_containsFileTransferV1() {
        assertEquals("file-transfer-v1", SupportedCapabilities.FILE_TRANSFER_V1)
        assertTrue(SupportedCapabilities.values.contains(SupportedCapabilities.FILE_TRANSFER_V1))
    }

    @Test
    fun fileOffer_usesCamelCaseAndPreservesLong() {
        val sizeBytes = 3_000_000_000L
        val payload = offer(sizeBytes)

        val json = ProtocolSerializer.payloadToJson(payload)
        val fields = json.jsonObject
        val decoded = ProtocolSerializer.decodePayload<FileOfferPayload>(json)

        assertTrue(fields.containsKey("transferId"))
        assertTrue(fields.containsKey("sizeBytes"))
        assertFalse(fields.containsKey("TransferId"))
        assertEquals(sizeBytes, fields.getValue("sizeBytes").jsonPrimitive.content.toLong())
        assertEquals(payload, decoded)
    }

    @Test
    fun fileChunk_preservesLongOffset() {
        val payload = FileChunkPayload(
            transferId = transferId,
            index = 45_776,
            offset = 3_000_000_000L,
            data = "SGVsbG8gd29ybGQ=",
        )

        assertEquals(payload, roundTrip(payload))
    }

    @Test
    fun allPayloadModels_roundTrip() {
        val payloads = listOf(
            FileAcceptPayload(transferId),
            FileRejectPayload(transferId, "user_rejected", "Declined."),
            FileCompletePayload(transferId, 1, 11L),
            FileReceivedPayload(transferId, 11L, sha256, "hello.txt"),
            FileCancelPayload(transferId, "user_cancelled"),
            FileErrorPayload(transferId, "checksum_mismatch", "Hash mismatch."),
        )

        payloads.forEach { payload ->
            val json = when (payload) {
                is FileAcceptPayload -> ProtocolSerializer.payloadToJson(payload)
                is FileRejectPayload -> ProtocolSerializer.payloadToJson(payload)
                is FileCompletePayload -> ProtocolSerializer.payloadToJson(payload)
                is FileReceivedPayload -> ProtocolSerializer.payloadToJson(payload)
                is FileCancelPayload -> ProtocolSerializer.payloadToJson(payload)
                is FileErrorPayload -> ProtocolSerializer.payloadToJson(payload)
                else -> error("Unexpected payload type")
            }
            assertTrue(json.jsonObject.containsKey("transferId"))
        }

        assertEquals(FileAcceptPayload(transferId), roundTrip(FileAcceptPayload(transferId)))
        assertEquals(
            FileRejectPayload(transferId, "user_rejected", "Declined."),
            roundTrip(FileRejectPayload(transferId, "user_rejected", "Declined.")),
        )
        assertEquals(
            FileCompletePayload(transferId, 1, 11L),
            roundTrip(FileCompletePayload(transferId, 1, 11L)),
        )
        assertEquals(
            FileReceivedPayload(transferId, 11L, sha256, "hello.txt"),
            roundTrip(FileReceivedPayload(transferId, 11L, sha256, "hello.txt")),
        )
        assertEquals(
            FileCancelPayload(transferId, "user_cancelled"),
            roundTrip(FileCancelPayload(transferId, "user_cancelled")),
        )
        assertEquals(
            FileErrorPayload(transferId, "checksum_mismatch", "Hash mismatch."),
            roundTrip(FileErrorPayload(transferId, "checksum_mismatch", "Hash mismatch.")),
        )
    }

    @Test
    fun fileOffer_ignoresUnknownFields() {
        val json = ProtocolSerializer.json.parseToJsonElement(
            """
            {
              "transferId": "$transferId",
              "fileName": "hello.txt",
              "sizeBytes": 11,
              "mimeType": "text/plain",
              "sha256": "$sha256",
              "chunkSize": 65536,
              "futureField": true
            }
            """.trimIndent()
        )

        val decoded = ProtocolSerializer.decodePayload<FileOfferPayload>(json)

        assertEquals("hello.txt", decoded.fileName)
    }

    @Test(expected = ConnectionException.InvalidMessage::class)
    fun fileOffer_rejectsMissingRequiredField() {
        val json = ProtocolSerializer.json.parseToJsonElement(
            """
            {
              "transferId": "$transferId",
              "fileName": "hello.txt",
              "sizeBytes": 11,
              "mimeType": "text/plain",
              "chunkSize": 65536
            }
            """.trimIndent()
        )

        ProtocolSerializer.decodePayload<FileOfferPayload>(json)
    }

    private fun offer(sizeBytes: Long) = FileOfferPayload(
        transferId = transferId,
        fileName = "hello.txt",
        sizeBytes = sizeBytes,
        mimeType = "text/plain",
        sha256 = sha256,
        chunkSize = 65_536,
    )

    private inline fun <reified T> roundTrip(payload: T): T {
        val json = ProtocolSerializer.payloadToJson(payload)
        return ProtocolSerializer.decodePayload(json)
    }
}
