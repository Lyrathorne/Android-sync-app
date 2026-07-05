package com.example.devicesync.core.protocol

import com.example.devicesync.core.network.PROTOCOL_VERSION
import java.time.Instant
import java.util.UUID

fun helloMessage(
    messageId: String = UUID.randomUUID().toString(),
    senderDeviceId: String = "android-device",
): ProtocolMessage {
    return ProtocolMessage(
        protocolVersion = PROTOCOL_VERSION,
        messageId = messageId,
        type = ProtocolMessageType.CONNECTION_HELLO.value,
        senderDeviceId = senderDeviceId,
        timestampUtc = Instant.parse("2026-07-05T18:45:00Z").toString(),
        requiresAcknowledgement = true,
        payload = ProtocolSerializer.payloadToJson(
            ConnectionHelloPayload(
                deviceName = "Infinix GT20 Pro",
                appVersion = "0.1.0",
                protocolVersion = PROTOCOL_VERSION,
                capabilities = listOf("text"),
            )
        ),
    )
}

fun helloAckMessage(
    correlationId: String = "hello-id",
    acceptedProtocolVersion: Int = PROTOCOL_VERSION,
    deviceName: String = "Gleb-PC",
): ProtocolMessage {
    return ProtocolMessage(
        protocolVersion = PROTOCOL_VERSION,
        messageId = "server-message-id",
        type = ProtocolMessageType.CONNECTION_HELLO_ACK.value,
        senderDeviceId = "windows-device-id",
        recipientDeviceId = "android-device",
        timestampUtc = Instant.parse("2026-07-05T18:45:01Z").toString(),
        correlationId = correlationId,
        payload = ProtocolSerializer.payloadToJson(
            ConnectionHelloAckPayload(
                deviceName = deviceName,
                deviceType = "windows",
                acceptedProtocolVersion = acceptedProtocolVersion,
                capabilities = listOf("text"),
            )
        ),
    )
}
