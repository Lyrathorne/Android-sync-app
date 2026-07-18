package com.example.devicesync.core.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ProtocolMessage(
    val protocolVersion: Int,
    val messageId: String,
    val type: String,
    val senderDeviceId: String,
    val recipientDeviceId: String? = null,
    val timestampUtc: String,
    val correlationId: String? = null,
    val originDeviceId: String? = null,
    val requiresAcknowledgement: Boolean = false,
    val payload: JsonElement,
)
