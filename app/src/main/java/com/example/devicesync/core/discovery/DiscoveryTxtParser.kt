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
)
