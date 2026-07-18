package com.example.devicesync.core.discovery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class HybridDeviceDiscoveryService(
    private val primary: DeviceDiscoveryService,
    private val fallback: DeviceDiscoveryService,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : DeviceDiscoveryService {
    private val _state = MutableStateFlow<DiscoveryState>(DiscoveryState.Idle)
    override val state: StateFlow<DiscoveryState> = _state.asStateFlow()
    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()
    private val _diagnostics = MutableStateFlow(DiscoveryDiagnostics(activeServiceType = "NSD + UDP beacon"))
    override val diagnostics: StateFlow<DiscoveryDiagnostics> = _diagnostics.asStateFlow()

    init {
        scope.launch {
            combine(primary.state, fallback.state) { first, second ->
                when {
                    first == DiscoveryState.Searching || second == DiscoveryState.Searching -> DiscoveryState.Searching
                    first == DiscoveryState.Starting || second == DiscoveryState.Starting -> DiscoveryState.Starting
                    first is DiscoveryState.Failed && second is DiscoveryState.Failed ->
                        DiscoveryState.Failed("Automatic discovery is unavailable.", null)
                    else -> first
                }
            }.collect { _state.value = it }
        }
        scope.launch {
            combine(primary.discoveredDevices, fallback.discoveredDevices) { first, second ->
                (first + second)
                    .groupBy { it.dedupeKey }
                    .map { (_, values) ->
                        values.reduce { left, right ->
                            left.copy(
                                hostAddresses = (left.hostAddresses + right.hostAddresses).distinct(),
                                lastSeenAt = maxOf(left.lastSeenAt, right.lastSeenAt),
                            )
                        }
                    }
                    .sortedBy { it.deviceName }
            }.collect { _devices.value = it }
        }
        scope.launch {
            combine(primary.diagnostics, fallback.diagnostics) { first, second ->
                DiscoveryDiagnostics(
                    foundServices = first.foundServices + second.foundServices,
                    resolvedServices = first.resolvedServices + second.resolvedServices,
                    lastError = first.lastError ?: second.lastError,
                    lastCallback = listOfNotNull(first.lastCallback, second.lastCallback).joinToString(" / ").ifBlank { null },
                    activeServiceType = "NSD + UDP beacon",
                    startedAt = listOfNotNull(first.startedAt, second.startedAt).minOrNull(),
                )
            }.collect { _diagnostics.value = it }
        }
    }

    override suspend fun startDiscovery() {
        primary.startDiscovery()
        fallback.startDiscovery()
    }

    override suspend fun stopDiscovery() {
        primary.stopDiscovery()
        fallback.stopDiscovery()
    }
}
