package com.example.devicesync.core.protocol

import kotlinx.serialization.Serializable

@Serializable
data class PairingRequestPayload(
    val sessionId: String,
    val androidDeviceId: String,
    val androidDeviceName: String,
    val androidAppVersion: String,
    val androidIdentityPublicKey: String,
    val androidIdentityFingerprint: String,
    val androidNonce: String,
    val proof: String,
)

@Serializable
data class PairingChallengePayload(
    val sessionId: String,
    val windowsDeviceId: String,
    val windowsDeviceName: String,
    val windowsIdentityPublicKey: String,
    val windowsIdentityFingerprint: String,
    val windowsNonce: String,
    val androidNonce: String,
    val proof: String,
)

@Serializable
data class PairingConfirmPayload(
    val sessionId: String,
    val confirmed: Boolean = true,
    val androidSignature: String,
)

@Serializable
data class PairingAcceptedPayload(
    val sessionId: String,
    val windowsSignature: String,
    val pairedAtUtc: String,
    val permissions: List<String>,
)

@Serializable
data class PairingCompleteAckPayload(
    val sessionId: String,
    val status: String,
)

@Serializable
data class AuthChallengePayload(
    val serverNonce: String,
    val windowsIdentityFingerprint: String,
    val serverSignature: String,
    val helloMessageId: String,
    val acceptedProtocolVersion: Int = 1,
    val capabilities: List<String> = emptyList(),
)

@Serializable
data class AuthResponsePayload(
    val helloMessageId: String,
    val clientSignature: String,
)

@Serializable
data class AuthAcceptedPayload(
    val status: String,
    val acceptedProtocolVersion: Int = 1,
    val capabilities: List<String> = emptyList(),
)
