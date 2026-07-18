package com.example.devicesync.core.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.example.devicesync.core.network.NetworkLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

class AndroidNsdDiscoveryService(
    context: Context,
    private val parser: DiscoveryTxtParser = DiscoveryTxtParser(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : DeviceDiscoveryService {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val _state = MutableStateFlow<DiscoveryState>(DiscoveryState.Idle)
    override val state: StateFlow<DiscoveryState> = _state.asStateFlow()
    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()
    private val _diagnostics = MutableStateFlow(DiscoveryDiagnostics())
    override val diagnostics: StateFlow<DiscoveryDiagnostics> = _diagnostics.asStateFlow()
    private var listener: NsdManager.DiscoveryListener? = null

    override suspend fun startDiscovery() {
        if (_state.value == DiscoveryState.Searching || _state.value == DiscoveryState.Starting) return
        NetworkLogger.info("NSD discovery requested")
        _state.value = DiscoveryState.Starting
        _discoveredDevices.value = emptyList()
        _diagnostics.value = DiscoveryDiagnostics(startedAt = Instant.now())

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                NetworkLogger.info("NSD discovery started type=$serviceType")
                scope.launch {
                    _state.value = DiscoveryState.Searching
                    markCallback("discoveryStarted")
                }
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != DEVICESYNC_SERVICE_TYPE) return
                NetworkLogger.info("Service found name=${serviceInfo.serviceName}")
                scope.launch {
                    _diagnostics.update { it.copy(foundServices = it.foundServices + 1, lastCallback = "serviceFound") }
                    resolve(serviceInfo)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                NetworkLogger.info("Service lost name=${serviceInfo.serviceName}")
                scope.launch {
                    _discoveredDevices.update { devices ->
                        devices.filterNot { it.serviceName == serviceInfo.serviceName }
                    }
                    markCallback("serviceLost")
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                NetworkLogger.info("Discovery stopped")
                scope.launch {
                    _state.value = DiscoveryState.Idle
                    markCallback("discoveryStopped")
                }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                NetworkLogger.error("Discovery failed code=$errorCode")
                nsdManager.stopServiceDiscovery(this)
                scope.launch { fail("Не удалось начать поиск компьютеров.", errorCode, "startDiscoveryFailed") }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                NetworkLogger.error("Stop discovery failed code=$errorCode")
                scope.launch { fail("Не удалось остановить поиск.", errorCode, "stopDiscoveryFailed") }
            }
        }

        listener = discoveryListener
        runCatching {
            nsdManager.discoverServices(DEVICESYNC_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }.onFailure { error ->
            fail("NSD недоступен: ${error.message.orEmpty()}", null, "startException")
        }
    }

    override suspend fun stopDiscovery() {
        val active = listener ?: return
        if (_state.value == DiscoveryState.Stopping || _state.value == DiscoveryState.Idle) return
        _state.value = DiscoveryState.Stopping
        runCatching { nsdManager.stopServiceDiscovery(active) }
            .onFailure { fail("Не удалось остановить поиск: ${it.message.orEmpty()}", null, "stopException") }
        listener = null
    }

    private fun resolve(serviceInfo: NsdServiceInfo) {
        NetworkLogger.info("Resolving service name=${serviceInfo.serviceName}")
        @Suppress("DEPRECATION")
        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                NetworkLogger.error("Resolve failed code=$errorCode")
                scope.launch { fail("Не удалось прочитать данные найденного компьютера.", errorCode, "resolveFailed", keepSearching = true) }
            }

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                NetworkLogger.info("Service resolved name=${resolved.serviceName}")
                scope.launch {
                    val device = resolved.toDiscoveredDevice(parser) ?: return@launch
                    _discoveredDevices.update { devices ->
                        val others = devices.filterNot { it.dedupeKey == device.dedupeKey }
                        (others + device).sortedBy { it.deviceName }
                    }
                    _diagnostics.update {
                        it.copy(resolvedServices = it.resolvedServices + 1, lastCallback = "serviceResolved")
                    }
                }
            }
        })
    }

    private fun NsdServiceInfo.toDiscoveredDevice(parser: DiscoveryTxtParser): DiscoveredDevice? {
        if (serviceName.isBlank() || port !in 1..65535) return null
        val txt = parser.parse(attributes)
        if (txt.deviceType != null && txt.deviceType != "windows") return null
        val addresses = (txt.hostAddresses + txt.endpoints.map { it.address } + resolvedAddresses()).distinct()
        if (addresses.isEmpty()) return null
        return DiscoveredDevice(
            serviceName = serviceName,
            deviceId = txt.deviceId,
            deviceName = txt.deviceName ?: serviceName,
            hostAddresses = addresses,
            port = port,
            protocolMin = txt.protocolMin,
            protocolMax = txt.protocolMax,
            appVersion = txt.appVersion,
            capabilities = txt.capabilities,
            pairingAvailable = txt.pairingAvailable,
            lastSeenAt = Instant.now(),
        )
    }

    private fun NsdServiceInfo.resolvedAddresses(): List<String> {
        return if (Build.VERSION.SDK_INT >= 34) {
            hostAddresses.mapNotNull { it.hostAddress }
        } else {
            @Suppress("DEPRECATION")
            listOfNotNull(host?.hostAddress)
        }.distinct()
    }

    private fun markCallback(callback: String) {
        _diagnostics.update { it.copy(lastCallback = callback) }
    }

    private fun fail(message: String, code: Int?, callback: String, keepSearching: Boolean = false) {
        _diagnostics.update { it.copy(lastError = message, lastCallback = callback) }
        if (!keepSearching) _state.value = DiscoveryState.Failed(message, code)
    }
}
