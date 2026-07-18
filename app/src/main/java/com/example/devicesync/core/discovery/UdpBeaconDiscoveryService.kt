package com.example.devicesync.core.discovery

import com.example.devicesync.core.network.NetworkLogger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class UdpBeaconDiscoveryService(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : DeviceDiscoveryService {
    private val _state = MutableStateFlow<DiscoveryState>(DiscoveryState.Idle)
    override val state: StateFlow<DiscoveryState> = _state.asStateFlow()
    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()
    private val _diagnostics = MutableStateFlow(DiscoveryDiagnostics(activeServiceType = "UDP beacon :54322"))
    override val diagnostics: StateFlow<DiscoveryDiagnostics> = _diagnostics.asStateFlow()
    private var socket: DatagramSocket? = null
    private var job: Job? = null

    override suspend fun startDiscovery() {
        if (job?.isActive == true) return
        _state.value = DiscoveryState.Starting
        _devices.value = emptyList()
        _diagnostics.value = DiscoveryDiagnostics(activeServiceType = "UDP beacon :54322", startedAt = Instant.now())
        job = scope.launch {
            try {
                val active = DatagramSocket(54322).apply {
                    broadcast = true
                    reuseAddress = true
                    soTimeout = 2_000
                }
                socket = active
                _state.value = DiscoveryState.Searching
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        active.receive(packet)
                        val device = parseBeacon(packet.data.decodeToString(0, packet.length), packet.address.hostAddress)
                            ?: continue
                        _devices.value = (_devices.value.filterNot { it.dedupeKey == device.dedupeKey } + device)
                            .sortedBy { it.deviceName }
                        _diagnostics.value = _diagnostics.value.copy(
                            foundServices = _diagnostics.value.foundServices + 1,
                            resolvedServices = _diagnostics.value.resolvedServices + 1,
                            lastCallback = "udpBeacon",
                        )
                    } catch (_: java.net.SocketTimeoutException) {
                        val cutoff = Instant.now().minusSeconds(12)
                        _devices.value = _devices.value.filter { it.lastSeenAt.isAfter(cutoff) }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: SocketException) {
                if (job?.isCancelled != true) fail(error)
            } catch (error: Throwable) {
                fail(error)
            } finally {
                socket?.close()
                socket = null
            }
        }
    }

    override suspend fun stopDiscovery() {
        socket?.close()
        job?.cancel()
        job = null
        _state.value = DiscoveryState.Idle
    }

    private fun fail(error: Throwable) {
        NetworkLogger.error("UDP beacon discovery failed", error)
        _diagnostics.value = _diagnostics.value.copy(lastError = error.message, lastCallback = "udpError")
        _state.value = DiscoveryState.Failed("LAN beacon discovery is unavailable.", null)
    }

    private fun parseBeacon(raw: String, sourceAddress: String?): DiscoveredDevice? = runCatching {
        val root = Json.parseToJsonElement(raw).jsonObject
        if (root.text("marker") != "DeviceSyncLanBeaconV1") return null
        val txt = root["TxtRecords"]?.jsonObject ?: root["txtRecords"]?.jsonObject ?: JsonObject(emptyMap())
        val port = root.text("Port")?.toIntOrNull() ?: root.text("port")?.toIntOrNull() ?: return null
        val advertised = root.text("AdvertisedAddress") ?: root.text("advertisedAddress")
        val txtAddresses = txt.text("addresses").orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
        val endpointAddresses = txt.text("endpoints").orEmpty().split(';').mapNotNull { endpoint ->
            endpoint.split('|').takeIf { it.size == 3 }?.get(1)?.takeIf(String::isNotBlank)
        }
        val addresses = (txtAddresses + endpointAddresses + listOfNotNull(advertised, sourceAddress)).distinct()
        if (addresses.isEmpty()) return null
        val capabilities = txt.text("capabilities").orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
        DiscoveredDevice(
            serviceName = root.text("InstanceName") ?: root.text("instanceName") ?: "DeviceSync",
            deviceId = txt.text("deviceId"),
            deviceName = txt.text("deviceName") ?: "Windows computer",
            hostAddresses = addresses,
            port = port,
            protocolMin = txt.text("protocolMin")?.toIntOrNull(),
            protocolMax = txt.text("protocolMax")?.toIntOrNull(),
            appVersion = txt.text("appVersion"),
            capabilities = capabilities,
            pairingAvailable = txt.text("pairingAvailable")?.toBooleanStrictOrNull(),
            lastSeenAt = Instant.now(),
        )
    }.getOrNull()

    private fun JsonObject.text(key: String): String? =
        get(key)?.jsonPrimitive?.content
}
