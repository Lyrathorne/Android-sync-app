package com.example.devicesync.core.security

import com.example.devicesync.core.discovery.DEVICESYNC_PROTOCOL_VERSION
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import java.time.Instant

@Serializable
data class PairingQrPayload(
    val format: String,
    val version: Int,
    val sessionId: String,
    val pairingSecret: String,
    val expiresAtUtc: String,
    val hostAddresses: List<String>,
    val port: Int,
    val windowsDeviceId: String,
    val windowsDeviceName: String,
    val windowsIdentityPublicKey: String,
    val windowsIdentityFingerprint: String,
    val tlsServerSpkiFingerprint: String,
    val protocolMin: Int,
    val protocolMax: Int,
)

class PairingQrParser(
    private val now: () -> Instant = { Instant.now() },
    private val maxPayloadSize: Int = 4096,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): Result<PairingQrPayload> {
        return runCatching {
            require(raw.toByteArray().size <= maxPayloadSize) { "QR payload is too large." }
            val payload = decodePayload(raw)
            require(payload.format == "devicesync-pairing") { "QR belongs to another application." }
            require(payload.version == 1) { "Unsupported QR version." }
            require(payload.sessionId.isNotBlank()) { "Pairing session is missing." }
            require(Base64Url.decode(payload.pairingSecret).size == 32) { "Pairing secret is invalid." }
            require(payload.port in 1..65535) { "Port is invalid." }
            require(payload.hostAddresses.isNotEmpty()) { "QR has no host address." }
            require(payload.windowsDeviceId.isNotBlank()) { "Windows Device ID is missing." }
            require(payload.windowsDeviceName.length in 1..80) { "Windows device name is invalid." }
            val publicKey = Base64Url.decode(payload.windowsIdentityPublicKey)
            require(SecurityEncoding.fingerprint(publicKey) == payload.windowsIdentityFingerprint) {
                "Windows key fingerprint does not match."
            }
            require(Base64Url.decode(payload.tlsServerSpkiFingerprint).size == 32) {
                "TLS server fingerprint is invalid."
            }
            require(DEVICESYNC_PROTOCOL_VERSION in payload.protocolMin..payload.protocolMax) {
                "DeviceSync protocol versions are incompatible."
            }
            require(Instant.parse(payload.expiresAtUtc).isAfter(now())) { "QR code has expired." }
            payload
        }
    }

    private fun decodePayload(raw: String): PairingQrPayload {
        val root = json.parseToJsonElement(raw).jsonObject
        if (!root.containsKey("sid")) {
            return json.decodeFromString<PairingQrPayload>(raw)
        }

        return PairingQrPayload(
            format = root.getValue("f").jsonPrimitive.content,
            version = root.getValue("v").jsonPrimitive.int,
            sessionId = root.getValue("sid").jsonPrimitive.content,
            pairingSecret = root.getValue("sec").jsonPrimitive.content,
            expiresAtUtc = root.getValue("exp").jsonPrimitive.content,
            hostAddresses = root.getValue("h").jsonArray.map { it.jsonPrimitive.content },
            port = root.getValue("p").jsonPrimitive.int,
            windowsDeviceId = root.getValue("did").jsonPrimitive.content,
            windowsDeviceName = root.getValue("dn").jsonPrimitive.content,
            windowsIdentityPublicKey = root.getValue("pk").jsonPrimitive.content,
            windowsIdentityFingerprint = root.getValue("fp").jsonPrimitive.content,
            tlsServerSpkiFingerprint = root.getValue("tlsfp").jsonPrimitive.content,
            protocolMin = root.getValue("pmin").jsonPrimitive.int,
            protocolMax = root.getValue("pmax").jsonPrimitive.int,
        )
    }
}
