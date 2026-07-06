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
            }.thenBy { it.hostAddress })
            .mapNotNull { it.hostAddress }
    }

    fun select(addresses: List<String>): String {
        return orderedUsableAddresses(addresses).firstOrNull()
            ?: throw IllegalArgumentException("No usable address was resolved for this computer.")
    }
}
