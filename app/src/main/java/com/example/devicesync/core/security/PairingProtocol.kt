package com.example.devicesync.core.security

import java.security.SecureRandom

data class PairingRequestDraft(
    val androidNonce: String,
    val androidFingerprint: String,
    val proof: String,
)

class PairingProtocol(
    private val random: SecureRandom = SecureRandom(),
) {
    fun createRequestProof(
        payload: PairingQrPayload,
        androidDeviceId: String,
        androidPublicKey: ByteArray,
    ): PairingRequestDraft {
        val androidNonceBytes = ByteArray(32)
        random.nextBytes(androidNonceBytes)
        val androidNonce = Base64Url.encode(androidNonceBytes)
        val androidFingerprint = SecurityEncoding.fingerprint(androidPublicKey)
        val transcript = TranscriptBuilder.pairingRequest(
            sessionId = payload.sessionId,
            windowsDeviceId = payload.windowsDeviceId,
            androidDeviceId = androidDeviceId,
            windowsFingerprint = payload.windowsIdentityFingerprint,
            androidFingerprint = androidFingerprint,
            androidNonce = androidNonce,
        )
        val proof = SecurityEncoding.hmacSha256(Base64Url.decode(payload.pairingSecret), transcript)
        return PairingRequestDraft(androidNonce, androidFingerprint, Base64Url.encode(proof))
    }
}
