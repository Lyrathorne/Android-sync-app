package com.example.devicesync.core.protocol

import com.example.devicesync.core.network.ConnectionException
import com.example.devicesync.core.network.MAX_JSON_PAYLOAD_SIZE
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

object ProtocolSerializer {
    @OptIn(ExperimentalSerializationApi::class)
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = false
        prettyPrint = false
    }

    fun serialize(message: ProtocolMessage): String {
        return try {
            validatePayloadSize(message.payload)
            json.encodeToString(message)
        } catch (error: SerializationException) {
            throw ConnectionException.InvalidMessage(cause = error)
        }
    }

    fun deserialize(rawJson: String): ProtocolMessage {
        return try {
            json.decodeFromString<ProtocolMessage>(rawJson).also { validatePayloadSize(it.payload) }
        } catch (error: IllegalArgumentException) {
            throw ConnectionException.InvalidMessage(cause = error)
        } catch (error: SerializationException) {
            throw ConnectionException.InvalidMessage(cause = error)
        }
    }

    inline fun <reified T> payloadToJson(payload: T): JsonElement {
        return json.encodeToJsonElement(payload)
    }

    inline fun <reified T> decodePayload(payload: JsonElement): T {
        return try {
            json.decodeFromJsonElement(payload)
        } catch (error: IllegalArgumentException) {
            throw ConnectionException.InvalidMessage(cause = error)
        } catch (error: SerializationException) {
            throw ConnectionException.InvalidMessage(cause = error)
        }
    }

    fun validatePayloadSize(payload: JsonElement) {
        if (payload.toString().encodeToByteArray().size > MAX_JSON_PAYLOAD_SIZE) {
            throw ConnectionException.InvalidMessage(ProtocolErrorCodes.PAYLOAD_TOO_LARGE)
        }
    }
}
