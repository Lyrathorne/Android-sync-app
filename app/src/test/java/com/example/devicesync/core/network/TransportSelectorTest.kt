package com.example.devicesync.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportSelectorTest {
    @Test
    fun `usb and lan are preferred over bluetooth`() {
        val selector = TransportSelector()
        val endpoints = listOf(
            TransportEndpoint(TransportKind.BLUETOOTH_RFCOMM, "AA:BB"),
            TransportEndpoint(TransportKind.LAN, "192.168.1.10", 54321),
            TransportEndpoint(TransportKind.USB_TETHERING, "192.168.42.1", 54321),
        )
        assertEquals(TransportKind.USB_TETHERING, selector.selectBest(endpoints)?.kind)
    }

    @Test
    fun `bluetooth profile is a constrained fallback`() {
        val profile = TransportProfile.forKind(TransportKind.BLUETOOTH_RFCOMM)
        assertTrue(profile.slow)
        assertEquals(2L * 1024 * 1024, profile.maximumFileBytes)
        assertTrue(SupportedCapabilities.MEDIA_CATALOG_V1 in profile.disabledCapabilities)
    }

    @Test
    fun `remembered endpoint parser recognises bluetooth and tethering`() {
        assertEquals(TransportKind.BLUETOOTH_RFCOMM, TransportEndpoint.parse("bt://AA:BB", 0).kind)
        assertEquals(TransportKind.USB_TETHERING, TransportEndpoint.parse("192.168.42.1", 54321).kind)
    }
}
