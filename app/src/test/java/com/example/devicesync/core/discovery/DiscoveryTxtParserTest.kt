package com.example.devicesync.core.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoveryTxtParserTest {
    private val parser = DiscoveryTxtParser()

    @Test
    fun parse_validRecords() {
        val records = parser.parse(
            mapOf(
                "deviceId" to "windows-123".encodeToByteArray(),
                "deviceName" to "Gleb-PC".encodeToByteArray(),
                "deviceType" to "windows".encodeToByteArray(),
                "protocolMin" to "1".encodeToByteArray(),
                "protocolMax" to "1".encodeToByteArray(),
                "capabilities" to "heartbeat-v1,ack-v1".encodeToByteArray(),
                "pairingAvailable" to "false".encodeToByteArray(),
                "unknown" to "ignored".encodeToByteArray(),
            )
        )

        assertEquals("windows-123", records.deviceId)
        assertEquals("Gleb-PC", records.deviceName)
        assertEquals(1, records.protocolMin)
        assertEquals(1, records.protocolMax)
        assertEquals(listOf("heartbeat-v1", "ack-v1"), records.capabilities)
        assertEquals(false, records.pairingAvailable)
    }

    @Test
    fun parse_missingOptionalFields() {
        val records = parser.parse(emptyMap())

        assertNull(records.deviceId)
        assertEquals(emptyList<String>(), records.capabilities)
    }

    @Test
    fun parse_invalidProtocolIsIgnored() {
        val records = parser.parse(
            mapOf(
                "protocolMin" to "abc".encodeToByteArray(),
                "protocolMax" to "0".encodeToByteArray(),
            )
        )

        assertNull(records.protocolMin)
        assertNull(records.protocolMax)
    }

    @Test
    fun parse_protocolMinGreaterThanMaxDropsMax() {
        val records = parser.parse(
            mapOf(
                "protocolMin" to "2".encodeToByteArray(),
                "protocolMax" to "1".encodeToByteArray(),
            )
        )

        assertEquals(2, records.protocolMin)
        assertNull(records.protocolMax)
    }

    @Test
    fun parse_tooLongValueIsIgnored() {
        val records = parser.parse(mapOf("deviceName" to "x".repeat(300).encodeToByteArray()))

        assertNull(records.deviceName)
    }
}
