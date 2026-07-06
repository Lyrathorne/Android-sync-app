package com.example.devicesync.core.discovery

import java.time.Instant

data class DiscoveredDevice(
    val serviceName: String,
    val deviceId: String?,
    val deviceName: String,
    val hostAddresses: List<String>,
    val port: Int,
    val protocolMin: Int?,
    val protocolMax: Int?,
    val appVersion: String?,
    val capabilities: List<String>,
    val pairingAvailable: Boolean?,
    val lastSeenAt: Instant,
) {
    val isProtocolCompatible: Boolean
        get() {
            val min = protocolMin ?: DEVICESYNC_PROTOCOL_VERSION
            val max = protocolMax ?: DEVICESYNC_PROTOCOL_VERSION
            return DEVICESYNC_PROTOCOL_VERSION in min..max
        }

    val dedupeKey: String
        get() = deviceId?.takeIf { it.isNotBlank() }
            ?: "${serviceName}:${hostAddresses.sorted().joinToString(",")}:$port"
}
