package com.example.devicesync.core.protocol

import com.example.devicesync.core.network.SupportedCapabilities
import com.example.devicesync.core.network.ConnectionException
import com.example.devicesync.core.network.MAX_JSON_PAYLOAD_SIZE
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolNegotiationTest {
    @Test
    fun negotiationSelectsHighestCommonVersionAndSupportsLegacyHello() {
        assertEquals(1, ProtocolVersionNegotiator.negotiate(1))
        assertEquals(1, ProtocolVersionNegotiator.negotiate(1, 1, 2))
        assertEquals(null, ProtocolVersionNegotiator.negotiate(2, 2, 3))
        assertEquals(null, ProtocolVersionNegotiator.negotiate(1, 2, 1))
    }

    @Test
    fun sharedVectorIgnoresFutureFieldsAndPreservesLimits() {
        val raw = checkNotNull(javaClass.classLoader?.getResource(
            "protocol/test-vectors/negotiation/connection-hello-v1.json"
        )).readText()
        val message = ProtocolSerializer.deserialize(raw)
        val payload = ProtocolSerializer.decodePayload<ConnectionHelloPayload>(message.payload)

        assertEquals("android-vector", message.originDeviceId)
        assertEquals(1, payload.protocolMin)
        assertEquals(2, payload.protocolMax)
        assertEquals(1_048_580, payload.maxFrameBytes)
        assertEquals(983_040, payload.maxPayloadBytes)
        assertEquals(1, ProtocolVersionNegotiator.negotiate(payload.protocolVersion, payload.protocolMin, payload.protocolMax))
    }

    @Test
    fun capabilityIntersectionDoesNotAdvertiseUnsupportedFeatures() {
        val result = CapabilityNegotiator.intersect(listOf(
            SupportedCapabilities.CLIPBOARD_V1,
            SupportedCapabilities.TRANSPORT_LAN_TLS_V1,
            SupportedCapabilities.MEDIA_CATALOG_V1,
        ))

        assertTrue(SupportedCapabilities.CLIPBOARD_V1 in result)
        assertTrue(SupportedCapabilities.TRANSPORT_LAN_TLS_V1 in result)
        assertTrue(SupportedCapabilities.MEDIA_CATALOG_V1 in result)
        CapabilityNegotiator.require(result, SupportedCapabilities.MEDIA_CATALOG_V1)
    }

    @Test(expected = ConnectionException.InvalidMessage::class)
    fun serializerRejectsPayloadAboveDocumentedLimit() {
        ProtocolSerializer.serialize(
            helloMessage().copy(payload = JsonPrimitive("x".repeat(MAX_JSON_PAYLOAD_SIZE + 1)))
        )
    }
}
