package com.example.devicesync.core.security

import com.example.devicesync.core.network.DeviceConnection
import com.example.devicesync.core.protocol.PairingAcceptedPayload
import com.example.devicesync.core.protocol.PairingChallengePayload
import com.example.devicesync.core.protocol.PairingCompleteAckPayload
import com.example.devicesync.core.protocol.PairingRequestPayload
import com.example.devicesync.core.protocol.ProtocolMessage
import com.example.devicesync.core.protocol.ProtocolMessageType
import com.example.devicesync.core.protocol.ProtocolSerializer
import com.example.devicesync.core.settings.DeviceIdentityRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant

class PairingCoordinatorTest {
    @Test
    fun validAcceptedSignatureSavesTrustAndSendsCompleteAck() = runTest {
        val windowsKeys = ecKeyPair()
        val androidKeys = FakeIdentityKeyProvider(ecKeyPair())
        val trusted = FakeTrustedDeviceRepository()
        val connection = ScriptedPairingConnection(windowsKeys)
        val coordinator = DefaultPairingCoordinator(
            identityRepository = FakeIdentityRepository(),
            identityKeyProvider = androidKeys,
            trustedDeviceRepository = trusted,
            connectionFactory = { connection },
            now = { Instant.parse("2026-07-06T12:00:00Z") },
        )

        coordinator.startPairing(qrPayload(windowsKeys))
        coordinator.confirmVerificationCode()

        assertNotNull(trusted.saved)
        assertEquals("windows-test", trusted.saved?.deviceId)
        assertTrue(connection.sent.any { it.type == ProtocolMessageType.PAIRING_COMPLETE_ACK.value })
    }

    @Test
    fun invalidAcceptedSignatureDoesNotSaveTrustOrSendCompleteAck() = runTest {
        val windowsKeys = ecKeyPair()
        val androidKeys = FakeIdentityKeyProvider(ecKeyPair())
        val trusted = FakeTrustedDeviceRepository()
        val connection = ScriptedPairingConnection(windowsKeys, corruptAcceptedSignature = true)
        val coordinator = DefaultPairingCoordinator(
            identityRepository = FakeIdentityRepository(),
            identityKeyProvider = androidKeys,
            trustedDeviceRepository = trusted,
            connectionFactory = { connection },
            now = { Instant.parse("2026-07-06T12:00:00Z") },
        )

        coordinator.startPairing(qrPayload(windowsKeys))
        coordinator.confirmVerificationCode()

        assertNull(trusted.saved)
        assertTrue(connection.sent.none { it.type == ProtocolMessageType.PAIRING_COMPLETE_ACK.value })
        assertTrue(coordinator.state.value is PairingState.Failed)
    }

    private class ScriptedPairingConnection(
        private val windowsKeys: KeyPair,
        private val corruptAcceptedSignature: Boolean = false,
    ) : DeviceConnection {
        val sent = mutableListOf<ProtocolMessage>()
        private val windowsNonce = Base64Url.encode(ByteArray(32) { 7 })

        override suspend fun connect(host: String, port: Int) = Unit

        override suspend fun send(message: ProtocolMessage) {
            sent += message
        }

        override suspend fun receive(): ProtocolMessage {
            error("receiveHandshake is used in pairing tests")
        }

        override suspend fun receiveHandshake(timeoutMs: Long): ProtocolMessage {
            val last = sent.last()
            return when (last.type) {
                ProtocolMessageType.PAIRING_REQUEST.value -> challenge(last)
                ProtocolMessageType.PAIRING_CONFIRM.value -> accepted(last)
                else -> error("Unexpected message ${last.type}")
            }
        }

        override suspend fun disconnect() = Unit

        private fun challenge(requestMessage: ProtocolMessage): ProtocolMessage {
            val request = ProtocolSerializer.decodePayload<PairingRequestPayload>(requestMessage.payload)
            val transcript = TranscriptBuilder.pairingChallenge(
                sessionId = request.sessionId,
                windowsDeviceId = "windows-test",
                androidDeviceId = request.androidDeviceId,
                windowsFingerprint = SecurityEncoding.fingerprint(windowsKeys.public.encoded),
                androidFingerprint = request.androidIdentityFingerprint,
                androidNonce = request.androidNonce,
                windowsNonce = windowsNonce,
            )
            val proof = SecurityEncoding.hmacSha256(Base64Url.decode(PAIRING_SECRET), transcript)
            return ProtocolMessage(
                protocolVersion = 1,
                messageId = "challenge-1",
                type = ProtocolMessageType.PAIRING_CHALLENGE.value,
                senderDeviceId = "windows-test",
                recipientDeviceId = request.androidDeviceId,
                timestampUtc = "2026-07-06T12:00:01Z",
                correlationId = requestMessage.messageId,
                payload = ProtocolSerializer.payloadToJson(
                    PairingChallengePayload(
                        sessionId = request.sessionId,
                        windowsDeviceId = "windows-test",
                        windowsDeviceName = "Windows PC",
                        windowsIdentityPublicKey = Base64Url.encode(windowsKeys.public.encoded),
                        windowsIdentityFingerprint = SecurityEncoding.fingerprint(windowsKeys.public.encoded),
                        windowsNonce = windowsNonce,
                        androidNonce = request.androidNonce,
                        proof = Base64Url.encode(proof),
                    )
                ),
            )
        }

        private fun accepted(confirmMessage: ProtocolMessage): ProtocolMessage {
            val request = ProtocolSerializer.decodePayload<PairingRequestPayload>(
                sent.first { it.type == ProtocolMessageType.PAIRING_REQUEST.value }.payload
            )
            val code = SecurityEncoding.verificationCode(
                request.sessionId,
                "windows-test",
                request.androidDeviceId,
                SecurityEncoding.fingerprint(windowsKeys.public.encoded),
                request.androidIdentityFingerprint,
                request.androidNonce,
                windowsNonce,
            )
            val pairedAt = "2026-07-06T12:00:02Z"
            val permissions = listOf("basic_connection", "heartbeat")
            val transcript = TranscriptBuilder.pairingAccepted(
                sessionId = request.sessionId,
                windowsDeviceId = "windows-test",
                androidDeviceId = request.androidDeviceId,
                windowsFingerprint = SecurityEncoding.fingerprint(windowsKeys.public.encoded),
                androidFingerprint = request.androidIdentityFingerprint,
                androidNonce = request.androidNonce,
                windowsNonce = windowsNonce,
                verificationCode = code,
                pairedAtUtc = pairedAt,
                permissions = permissions,
            )
            val signature = if (corruptAcceptedSignature) ByteArray(64) { 3 } else sign(windowsKeys, transcript)
            return ProtocolMessage(
                protocolVersion = 1,
                messageId = "accepted-1",
                type = ProtocolMessageType.PAIRING_ACCEPTED.value,
                senderDeviceId = "windows-test",
                recipientDeviceId = request.androidDeviceId,
                timestampUtc = pairedAt,
                correlationId = confirmMessage.messageId,
                payload = ProtocolSerializer.payloadToJson(
                    PairingAcceptedPayload(
                        sessionId = request.sessionId,
                        windowsSignature = Base64Url.encode(signature),
                        pairedAtUtc = pairedAt,
                        permissions = permissions,
                    )
                ),
            )
        }
    }

    private class FakeIdentityRepository : DeviceIdentityRepository {
        override suspend fun getOrCreateDeviceId(): String = "android-test"
        override suspend fun getDeviceName(): String = "Pixel"
        override suspend fun updateDeviceName(name: String) = Unit
    }

    private class FakeIdentityKeyProvider(private val keys: KeyPair) : DeviceIdentityKeyProvider {
        override suspend fun getOrCreatePublicKey(): ByteArray = keys.public.encoded
        override suspend fun getPublicKeyFingerprint(): String = SecurityEncoding.fingerprint(keys.public.encoded)
        override suspend fun sign(data: ByteArray): ByteArray = sign(keys, data)
        override suspend fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
            return runCatching {
                val parsed = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKey))
                val verifier = Signature.getInstance("SHA256withECDSA")
                verifier.initVerify(parsed)
                verifier.update(data)
                verifier.verify(signature)
            }.getOrDefault(false)
        }
    }

    private class FakeTrustedDeviceRepository : TrustedDeviceRepository {
        var saved: TrustedDevice? = null
        private val devices = MutableStateFlow<List<TrustedDevice>>(emptyList())
        override fun observeTrustedDevices(): Flow<List<TrustedDevice>> = devices
        override suspend fun getTrustedDevice(deviceId: String): TrustedDevice? = saved?.takeIf { it.deviceId == deviceId }
        override suspend fun saveTrustedDevice(device: TrustedDevice) {
            saved = device
            devices.value = listOf(device)
        }
        override suspend fun updateLastVerifiedAt(deviceId: String, timestamp: Instant) = Unit
        override suspend fun revoke(deviceId: String, timestamp: Instant) = Unit
        override suspend fun delete(deviceId: String) {
            saved = null
            devices.value = emptyList()
        }
    }

    private companion object {
        val PAIRING_SECRET: String = Base64Url.encode(ByteArray(32) { 9 })

        fun qrPayload(windowsKeys: KeyPair) = PairingQrPayload(
            format = "devicesync-pairing",
            version = 1,
            sessionId = "pair-test",
            pairingSecret = PAIRING_SECRET,
            expiresAtUtc = "2026-07-06T12:02:00Z",
            hostAddresses = listOf("127.0.0.1"),
            port = 54321,
            windowsDeviceId = "windows-test",
            windowsDeviceName = "Windows PC",
            windowsIdentityPublicKey = Base64Url.encode(windowsKeys.public.encoded),
            windowsIdentityFingerprint = SecurityEncoding.fingerprint(windowsKeys.public.encoded),
            protocolMin = 1,
            protocolMax = 1,
        )

        fun ecKeyPair(): KeyPair {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"))
            return generator.generateKeyPair()
        }

        fun sign(keys: KeyPair, data: ByteArray): ByteArray {
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initSign(keys.private)
            signature.update(data)
            return signature.sign()
        }
    }
}
