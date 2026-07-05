package com.example.devicesync.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class HeartbeatPayloadSerializerTest {
    @Test
    fun pingPayload_roundTripsThroughJson() {
        val payload = PingPayload(sequence = 15, sentAtUtc = "2026-07-06T10:00:00Z")
        val json = ProtocolSerializer.payloadToJson(payload)

        val decoded = ProtocolSerializer.decodePayload<PingPayload>(json)

        assertEquals(payload, decoded)
    }

    @Test
    fun messageAckPayload_roundTripsThroughJson() {
        val payload = MessageAckPayload(status = "processed")
        val json = ProtocolSerializer.payloadToJson(payload)

        val decoded = ProtocolSerializer.decodePayload<MessageAckPayload>(json)

        assertEquals("processed", decoded.status)
    }
}
