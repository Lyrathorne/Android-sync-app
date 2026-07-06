package com.example.devicesync.core.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class DiscoveryAddressSelectorTest {
    private val selector = DiscoveryAddressSelector()

    @Test
    fun select_prefersIpv4() {
        val selected = selector.select(listOf("fe80::1", "192.168.1.25"))

        assertEquals("192.168.1.25", selected)
    }

    @Test
    fun select_prefersPrivateLanIpv4() {
        val selected = selector.select(listOf("8.8.8.8", "192.168.1.25"))

        assertEquals("192.168.1.25", selected)
    }

    @Test
    fun select_usesIpv6WhenIpv4Missing() {
        val selected = selector.select(listOf("fd00::1234"))

        assertEquals("fd00:0:0:0:0:0:0:1234", selected)
    }

    @Test
    fun select_excludesLoopbackAndMulticast() {
        val ordered = selector.orderedUsableAddresses(listOf("127.0.0.1", "224.0.0.251", "192.168.1.25"))

        assertEquals(listOf("192.168.1.25"), ordered)
        assertFalse(ordered.contains("127.0.0.1"))
    }

    @Test
    fun select_emptyListFailsClearly() {
        assertThrows(IllegalArgumentException::class.java) {
            selector.select(emptyList())
        }
    }
}
