package com.example.devicesync.core.network

import com.example.devicesync.core.protocol.ProtocolMessage
import kotlinx.coroutines.withTimeout

interface DeviceConnection {
    suspend fun connect(host: String, port: Int)
    suspend fun onHandshakeComplete() = Unit
    suspend fun receiveHandshake(timeoutMs: Long): ProtocolMessage = withTimeout(timeoutMs) {
        receive()
    }
    suspend fun send(message: ProtocolMessage)
    suspend fun receive(): ProtocolMessage
    suspend fun disconnect()
}
