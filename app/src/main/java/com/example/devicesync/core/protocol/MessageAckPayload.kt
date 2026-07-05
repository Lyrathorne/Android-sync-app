package com.example.devicesync.core.protocol

import kotlinx.serialization.Serializable

@Serializable
data class MessageAckPayload(
    val status: String,
    val errorCode: String? = null,
    val errorMessage: String? = null,
)
