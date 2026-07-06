package com.example.devicesync.core.discovery

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

class DiscoveryAddressSelector {
    fun orderedUsableAddresses(addresses: List<String>): List<String> {
        return addresses
            .mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
            .filterNot { it.isAnyLocalAddress || it.isLoopbackAddress || it.isMulticastAddress }
            .sortedWith(compareBy<InetAddress> {
                when (it) {
                    is Inet4Address -> 0
                    is Inet6Address -> 1
                    else -> 2
                }
            }.thenBy {
                if (it is Inet4Address && it.isPrivateLanAddress()) 0 else 1
            }.thenBy { it.hostAddress })
            .mapNotNull { it.hostAddress }
    }

    fun select(addresses: List<String>): String {
        return orderedUsableAddresses(addresses).firstOrNull()
            ?: throw IllegalArgumentException("No usable address was resolved for this computer.")
    }

    private fun Inet4Address.isPrivateLanAddress(): Boolean {
        val bytes = address.map { it.toInt() and 0xff }
        return bytes[0] == 10 ||
            (bytes[0] == 172 && bytes[1] in 16..31) ||
            (bytes[0] == 192 && bytes[1] == 168)
    }
}
