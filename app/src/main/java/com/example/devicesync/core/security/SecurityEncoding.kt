package com.example.devicesync.core.security

import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

object SecurityEncoding {
    fun fingerprint(publicKeySpkiDer: ByteArray): String {
        return Base64Url.encode(MessageDigest.getInstance("SHA-256").digest(publicKeySpkiDer))
    }

    fun fingerprintDisplay(fingerprintBase64Url: String): String {
        return Base64Url.decode(fingerprintBase64Url)
            .joinToString(":") { "%02X".format(it) }
    }

    fun hmacSha256(secret: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun fixedTimeEquals(left: ByteArray, right: ByteArray): Boolean {
        return MessageDigest.isEqual(left, right)
    }

    fun verificationCode(vararg fields: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(TranscriptBuilder.build(*fields))
        val value = ByteBuffer.wrap(digest, 0, 4).int and Int.MAX_VALUE
        return (value % 1_000_000).toString().padStart(6, '0')
    }
}

object TranscriptBuilder {
    fun pairingRequest(
        sessionId: String,
        windowsDeviceId: String,
        androidDeviceId: String,
        windowsFingerprint: String,
        androidFingerprint: String,
        androidNonce: String,
    ): ByteArray = build(
        "DeviceSyncPairingV1",
        sessionId,
        windowsDeviceId,
        androidDeviceId,
        windowsFingerprint,
        androidFingerprint,
        androidNonce,
    )

    fun pairingChallenge(
        sessionId: String,
        windowsDeviceId: String,
        androidDeviceId: String,
        windowsFingerprint: String,
        androidFingerprint: String,
        androidNonce: String,
        windowsNonce: String,
    ): ByteArray = build(
        "DeviceSyncPairingChallengeV1",
        sessionId,
        windowsDeviceId,
        androidDeviceId,
        windowsFingerprint,
        androidFingerprint,
        androidNonce,
        windowsNonce,
    )

    fun build(vararg fields: String): ByteArray {
        val parts = fields.map { it.encodeToByteArray() }
        val totalSize = parts.sumOf { it.size + Int.SIZE_BYTES }
        val buffer = ByteBuffer.allocate(totalSize)
        parts.forEach {
            buffer.putInt(it.size)
            buffer.put(it)
        }
        return buffer.array()
    }
}
