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
    val proof: String,
)

@Serializable
data class PairingConfirmPayload(
    val sessionId: String,
    val deviceId: String,
    val signature: String,
)

@Serializable
data class AuthChallengePayload(
    val serverNonce: String,
    val serverSignature: String,
    val serverIdentityFingerprint: String,
)

@Serializable
data class AuthResponsePayload(
    val clientNonce: String,
    val clientSignature: String,
    val clientIdentityFingerprint: String,
)
