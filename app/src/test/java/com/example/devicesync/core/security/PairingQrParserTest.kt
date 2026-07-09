package com.example.devicesync.core.security

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PairingQrParserTest {
    private val parser = PairingQrParser(now = { Instant.parse("2026-07-06T12:00:00Z") })
    private val publicKey = "windows-test-public-key-spki".encodeToByteArray()
    private val fingerprint = SecurityEncoding.fingerprint(publicKey)

    @Test
    fun parse_validQr() {
        val result = parser.parse(validQr())

        assertTrue(result.isSuccess)
    }

    @Test
    fun parse_validCompactQr() {
        val result = parser.parse(validCompactQr())

        assertTrue(result.isSuccess)
    }

    @Test
    fun parse_rejectsWrongFormat() {
        assertTrue(parser.parse(validQr(format = "other")).isFailure)
    }

    @Test
    fun parse_rejectsExpiredQr() {
        assertTrue(parser.parse(validQr(expiresAtUtc = "2026-07-06T11:59:00Z")).isFailure)
    }

    @Test
    fun parse_rejectsWrongSecretLength() {
        assertTrue(parser.parse(validQr(secret = Base64Url.encode(ByteArray(31)))).isFailure)
    }

    @Test
    fun parse_rejectsFingerprintMismatch() {
        assertTrue(parser.parse(validQr(fingerprint = Base64Url.encode(ByteArray(32)))).isFailure)
    }

    @Test
    fun parse_rejectsIncompatibleProtocol() {
        assertTrue(parser.parse(validQr(protocolMin = 2, protocolMax = 2)).isFailure)
    }

    private fun validQr(
        format: String = "devicesync-pairing",
        secret: String = Base64Url.encode(ByteArray(32) { it.toByte() }),
        expiresAtUtc: String = "2026-07-06T12:02:00Z",
        fingerprint: String = this.fingerprint,
        protocolMin: Int = 1,
        protocolMax: Int = 1,
    ): String {
        return """
            {
              "format":"$format",
              "version":1,
              "sessionId":"pair-test",
              "pairingSecret":"$secret",
              "expiresAtUtc":"$expiresAtUtc",
              "hostAddresses":["192.168.1.25"],
              "port":54321,
              "windowsDeviceId":"windows-test",
              "windowsDeviceName":"Gleb-PC",
              "windowsIdentityPublicKey":"${Base64Url.encode(publicKey)}",
              "windowsIdentityFingerprint":"$fingerprint",
              "protocolMin":$protocolMin,
              "protocolMax":$protocolMax
            }
        """.trimIndent()
    }

    private fun validCompactQr(): String {
        return """
            {
              "f":"devicesync-pairing",
              "v":1,
              "sid":"pair-test",
              "sec":"${Base64Url.encode(ByteArray(32) { it.toByte() })}",
              "exp":"2026-07-06T12:02:00Z",
              "h":["192.168.1.25"],
              "p":54321,
              "did":"windows-test",
              "dn":"Gleb-PC",
              "pk":"${Base64Url.encode(publicKey)}",
              "fp":"$fingerprint",
              "pmin":1,
              "pmax":1
            }
        """.trimIndent()
    }
}
