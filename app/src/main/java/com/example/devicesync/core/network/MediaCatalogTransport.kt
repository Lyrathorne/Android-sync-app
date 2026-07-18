package com.example.devicesync.core.network

import com.example.devicesync.core.protocol.ProtocolMessage
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement

interface MediaCatalogTransport {
    val state: StateFlow<ConnectionState>
    suspend fun sendCatalogMessage(type: String, payload: JsonElement)
    fun addMediaCatalogListener(listener: MediaCatalogMessageListener)
    fun removeMediaCatalogListener(listener: MediaCatalogMessageListener)
}

interface MediaCatalogMessageListener {
    suspend fun onMediaCatalogMessage(message: ProtocolMessage)
    fun onMediaCatalogDisconnected()
}
