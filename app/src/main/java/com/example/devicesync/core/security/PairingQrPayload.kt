package com.example.devicesync.core.security

import com.example.devicesync.core.discovery.DEVICESYNC_PROTOCOL_VERSION
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
            val payload = json.decodeFromString<PairingQrPayload>(raw)
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
            require(DEVICESYNC_PROTOCOL_VERSION in payload.protocolMin..payload.protocolMax) {
                "DeviceSync protocol versions are incompatible."
            }
            require(Instant.parse(payload.expiresAtUtc).isAfter(now())) { "QR code has expired." }
            payload
        }
    }
}
