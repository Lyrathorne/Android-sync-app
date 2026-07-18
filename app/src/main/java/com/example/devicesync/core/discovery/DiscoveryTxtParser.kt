package com.example.devicesync.core.discovery

class DiscoveryTxtParser(
    private val maxValueLength: Int = 255,
) {
    fun parse(attributes: Map<String, ByteArray>): DiscoveryTxtRecords {
        fun text(key: String): String? {
            return attributes[key]
                ?.decodeToString()
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= maxValueLength }
        }

        val protocolMin = text("protocolMin")?.toIntOrNull()
        val protocolMax = text("protocolMax")?.toIntOrNull()
        val normalizedMin = protocolMin?.takeIf { it > 0 }
        val normalizedMax = protocolMax?.takeIf { it > 0 && (normalizedMin == null || normalizedMin <= it) }

        return DiscoveryTxtRecords(
            deviceId = text("deviceId")?.takeIf { it.startsWith("windows-") },
            deviceName = text("deviceName"),
            deviceType = text("deviceType"),
            protocolMin = normalizedMin,
            protocolMax = normalizedMax,
            appVersion = text("appVersion"),
            capabilities = text("capabilities")
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty(),
            pairingAvailable = text("pairingAvailable")?.toBooleanStrictOrNull(),
            hostAddresses = text("addresses")
                ?.split(',')
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                .orEmpty(),
            endpoints = text("endpoints")
                ?.split(';')
                ?.mapNotNull { value ->
                    val parts = value.split('|')
                    if (parts.size != 3) null else {
                        val port = parts[2].toIntOrNull()
                        if (parts[1].isBlank() || port == null || port !in 1..65535) null
                        else DiscoveryEndpointRecord(parts[0], parts[1], port)
                    }
                }
                .orEmpty(),
        )
    }
}

data class DiscoveryTxtRecords(
    val deviceId: String?,
    val deviceName: String?,
    val deviceType: String?,
    val protocolMin: Int?,
    val protocolMax: Int?,
    val appVersion: String?,
    val capabilities: List<String>,
    val pairingAvailable: Boolean?,
    val hostAddresses: List<String>,
    val endpoints: List<DiscoveryEndpointRecord>,
)

data class DiscoveryEndpointRecord(
    val kind: String,
    val address: String,
    val port: Int,
)
