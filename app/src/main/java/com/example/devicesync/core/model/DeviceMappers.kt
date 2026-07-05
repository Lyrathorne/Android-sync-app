package com.example.devicesync.core.model

import com.example.devicesync.core.data.PairedDevice

fun PairedDevice.toDevice(): Device {
    return Device(
        id = id,
        name = name,
        connectionStatus = connectionStatus,
        lastConnectedText = lastConnectedAt?.toString(),
        host = host,
        port = port,
        acceptedProtocolVersion = protocolVersion,
        capabilities = capabilities,
    )
}
