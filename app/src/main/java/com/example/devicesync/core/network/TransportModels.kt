package com.example.devicesync.core.network

enum class TransportKind {
    LAN,
    HOTSPOT,
    USB_TETHERING,
    BLUETOOTH_RFCOMM,
}

data class TransportEndpoint(
    val kind: TransportKind,
    val address: String,
    val port: Int = 0,
    val interfaceId: String? = null,
    val remembered: Boolean = false,
) {
    val displayAddress: String
        get() = if (kind == TransportKind.BLUETOOTH_RFCOMM) address else "$address:$port"

    companion object {
        fun parse(host: String, port: Int): TransportEndpoint =
            if (host.startsWith("bt://", ignoreCase = true)) {
                TransportEndpoint(TransportKind.BLUETOOTH_RFCOMM, host.removePrefix("bt://"), remembered = true)
            } else {
                TransportEndpoint(classifyNetworkAddress(host), host, port, remembered = true)
            }

        private fun classifyNetworkAddress(host: String): TransportKind = when {
            host.startsWith("192.168.42.") || host.startsWith("192.168.137.") ->
                TransportKind.USB_TETHERING
            host.startsWith("192.168.43.") || host.startsWith("192.168.49.") ->
                TransportKind.HOTSPOT
            else -> TransportKind.LAN
        }
    }
}

data class TransportMetrics(
    val kind: TransportKind,
    val available: Boolean,
    val connectLatencyMs: Long? = null,
    val roundTripMs: Long? = null,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val lastErrorCode: String? = null,
)

data class TransportProfile(
    val kind: TransportKind,
    val priority: Int,
    val maximumFrameBytes: Int,
    val maximumFileBytes: Long,
    val slow: Boolean,
    val disabledCapabilities: Set<String>,
) {
    companion object {
        fun forKind(kind: TransportKind): TransportProfile = when (kind) {
            TransportKind.USB_TETHERING -> TransportProfile(kind, 100, 1024 * 1024, 100L * 1024 * 1024, false, emptySet())
            TransportKind.LAN -> TransportProfile(kind, 90, 1024 * 1024, 100L * 1024 * 1024, false, emptySet())
            TransportKind.HOTSPOT -> TransportProfile(kind, 80, 1024 * 1024, 100L * 1024 * 1024, false, emptySet())
            TransportKind.BLUETOOTH_RFCOMM -> TransportProfile(
                kind,
                10,
                48 * 1024,
                2L * 1024 * 1024,
                true,
                setOf(
                    SupportedCapabilities.MEDIA_CATALOG_V1,
                    SupportedCapabilities.THUMBNAILS_V1,
                    SupportedCapabilities.FOLDER_SYNC_V1,
                    SupportedCapabilities.FILE_TRANSFER_V2,
                ),
            )
        }
    }
}

class TransportSelector {
    fun selectBest(
        endpoints: List<TransportEndpoint>,
        metrics: Map<TransportKind, TransportMetrics> = emptyMap(),
    ): TransportEndpoint? = endpoints
        .filter { metrics[it.kind]?.available != false }
        .sortedWith(
            compareByDescending<TransportEndpoint> { TransportProfile.forKind(it.kind).priority }
                .thenBy { metrics[it.kind]?.connectLatencyMs ?: Long.MAX_VALUE }
                .thenByDescending { it.remembered },
        )
        .firstOrNull()
}
