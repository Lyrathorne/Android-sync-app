package com.example.devicesync.core.network

import com.example.devicesync.core.protocol.ProtocolMessage

interface DeviceConnection {
    suspend fun connect(host: String, port: Int)
    suspend fun send(message: ProtocolMessage)
    suspend fun receive(): ProtocolMessage
    suspend fun disconnect()
}
