package com.example.devicesync.core.network

import com.example.devicesync.core.protocol.ProtocolMessage
import kotlinx.serialization.json.JsonElement

interface SharingTransport {
    suspend fun sendSharingMessage(type: String, payload: JsonElement)
    fun addSharingListener(listener: SharingMessageListener)
    fun removeSharingListener(listener: SharingMessageListener)
}

interface SharingMessageListener {
    suspend fun onSharingMessage(message: ProtocolMessage)
}
