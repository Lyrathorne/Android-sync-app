package com.example.devicesync.core.protocol

import com.example.devicesync.core.network.ConnectionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolSerializerTest {
    @Test
    fun serializeConnectionHello_containsNetworkTypeValue() {
        val json = ProtocolSerializer.serialize(helloMessage(messageId = "hello-id"))

        assertTrue(json.contains("\"type\":\"connection.hello\""))
        assertTrue(json.contains("\"requiresAcknowledgement\":true"))
    }

    @Test
    fun deserializeConnectionHelloAck_readsPayload() {
        val raw = ProtocolSerializer.serialize(helloAckMessage(correlationId = "hello-id"))

        val message = ProtocolSerializer.deserialize(raw)
        val payload = ProtocolSerializer.decodePayload<ConnectionHelloAckPayload>(message.payload)

        assertEquals(ProtocolMessageType.CONNECTION_HELLO_ACK.value, message.type)
        assertEquals("hello-id", message.correlationId)
        assertEquals("Gleb-PC", payload.deviceName)
    }

    @Test
    fun deserialize_ignoresUnknownField() {
        val raw = ProtocolSerializer.serialize(helloAckMessage())
            .replace("\"payload\":", "\"futureField\":\"ok\",\"payload\":")

        val message = ProtocolSerializer.deserialize(raw)

        assertEquals(ProtocolMessageType.CONNECTION_HELLO_ACK.value, message.type)
    }

    @Test(expected = ConnectionException.InvalidMessage::class)
    fun deserialize_invalidJsonThrowsDomainError() {
        ProtocolSerializer.deserialize("{ invalid json")
    }

    @Test
    fun serializeAndDeserialize_keepsUnicode() {
        val raw = ProtocolSerializer.serialize(helloAckMessage(deviceName = "Рабочий-ПК"))

        val message = ProtocolSerializer.deserialize(raw)
        val payload = ProtocolSerializer.decodePayload<ConnectionHelloAckPayload>(message.payload)

        assertEquals("Рабочий-ПК", payload.deviceName)
    }
}
