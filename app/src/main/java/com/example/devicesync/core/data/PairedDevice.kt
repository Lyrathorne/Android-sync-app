package com.example.devicesync.core.data

import com.example.devicesync.core.model.ConnectionStatus
import java.time.Instant

data class PairedDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val protocolVersion: Int,
    val capabilities: List<String>,
    val lastConnectedAt: Instant?,
    val isAutoConnectEnabled: Boolean,
    val connectionStatus: ConnectionStatus,
)
