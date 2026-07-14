package com.example.devicesync.core.network

import com.example.devicesync.core.protocol.ProtocolMessage
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement

interface FileTransferTransport {
    val state: StateFlow<ConnectionState>

    suspend fun sendFileTransferMessage(type: String, payload: JsonElement)

    fun setFileTransferListener(listener: FileTransferMessageListener?)
    fun addFileTransferListener(listener: FileTransferMessageListener) = setFileTransferListener(listener)
    fun removeFileTransferListener(listener: FileTransferMessageListener) = Unit
}

interface FileTransferMessageListener {
    suspend fun onFileTransferMessage(message: ProtocolMessage)
    fun onFileTransferDisconnected()
}
