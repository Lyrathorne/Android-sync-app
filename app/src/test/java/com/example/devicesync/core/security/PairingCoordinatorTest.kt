package com.example.devicesync.core.security

import com.example.devicesync.core.network.DeviceConnection
import com.example.devicesync.core.network.ConnectionException
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

        assertEquals(ProtocolMessageType.PAIRING_REQUEST.value, connection.sent.first().type)
        assertEquals(listOf(10_000L, 60_000L), connection.receivedTimeouts)
        assertNotNull(trusted.saved)
        assertEquals("windows-test", trusted.saved?.deviceId)
        assertTrue(connection.sent.any { it.type == ProtocolMessageType.PAIRING_COMPLETE_ACK.value })
        val completed = coordinator.state.value as PairingState.Completed
        assertEquals(listOf("192.168.1.45"), completed.hostAddresses)
        assertEquals(54321, completed.port)
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

    @Test
    fun challengeTimeoutMovesToFailed() = runTest {
        val windowsKeys = ecKeyPair()
        val connection = FailingReceiveConnection(ConnectionException.Timeout())
        val coordinator = coordinator(windowsKeys, connectionFactory = { connection })

        coordinator.startPairing(qrPayload(windowsKeys))

        val state = coordinator.state.value as PairingState.Failed
        assertEquals("PAIRING_CHALLENGE_TIMEOUT", state.technicalCode)
        assertEquals(ProtocolMessageType.PAIRING_REQUEST.value, connection.sent.first().type)
    }

    @Test
    fun acceptedTimeoutMovesToFailed() = runTest {
        val windowsKeys = ecKeyPair()
        val connection = ConfirmationFailingConnection(windowsKeys)
        val coordinator = coordinator(windowsKeys, connectionFactory = { connection })

        coordinator.startPairing(qrPayload(windowsKeys))
        coordinator.confirmVerificationCode()

        val state = coordinator.state.value as PairingState.Failed
        assertEquals("PAIRING_ACCEPTED_TIMEOUT", state.technicalCode)
        assertEquals(listOf(10_000L, 60_000L), connection.receivedTimeouts)
    }

    @Test
    fun pairingRejectedMovesToFailed() = runTest {
        val windowsKeys = ecKeyPair()
        val connection = FixedResponseConnection(ProtocolMessageType.PAIRING_REJECTED.value)
        val coordinator = coordinator(windowsKeys, connectionFactory = { connection })

        coordinator.startPairing(qrPayload(windowsKeys))

        val state = coordinator.state.value as PairingState.Failed
        assertEquals("PAIRING_PROTOCOL_ERROR", state.technicalCode)
    }

    @Test
    fun eofMovesToFailed() = runTest {
        val windowsKeys = ecKeyPair()
        val connection = FailingReceiveConnection(ConnectionException.ConnectionClosed())
        val coordinator = coordinator(windowsKeys, connectionFactory = { connection })

        coordinator.startPairing(qrPayload(windowsKeys))

        val state = coordinator.state.value as PairingState.Failed
        assertEquals("PAIRING_CONNECTION_CLOSED", state.technicalCode)
    }

    @Test
    fun firstAddressTimeoutThenSecondAddressSucceeds() = runTest {
        val windowsKeys = ecKeyPair()
        val first = ConnectFailConnection(ConnectionException.TcpConnectTimeout("192.168.1.44", 54321))
        val second = ScriptedPairingConnection(windowsKeys)
        val connections = ArrayDeque<DeviceConnection>(listOf(first, second))
        val coordinator = coordinator(windowsKeys, connectionFactory = { connections.removeFirst() })

        coordinator.startPairing(qrPayload(windowsKeys, hostAddresses = listOf("192.168.1.44", "192.168.1.45")))

        assertTrue(first.connectAttempted)
        assertTrue(second.connectAttempted)
        assertTrue(coordinator.state.value is PairingState.WaitingForUserConfirmation)
    }

    @Test
    fun allAddressesFailMovesToFailedWithoutSendingRequest() = runTest {
        val windowsKeys = ecKeyPair()
        val first = ConnectFailConnection(ConnectionException.TcpConnectTimeout("192.168.1.44", 54321))
        val second = ConnectFailConnection(ConnectionException.ConnectionRefused())
        val connections = ArrayDeque<DeviceConnection>(listOf(first, second))
        val coordinator = coordinator(windowsKeys, connectionFactory = { connections.removeFirst() })

        coordinator.startPairing(qrPayload(windowsKeys, hostAddresses = listOf("192.168.1.44", "192.168.1.45")))

        val state = coordinator.state.value as PairingState.Failed
        assertEquals("TCP_CONNECTION_REFUSED", state.technicalCode)
        assertEquals(0, first.sentCount + second.sentCount)
    }

    @Test
    fun secondAddressSuccessStopsFurtherAttempts() = runTest {
        val windowsKeys = ecKeyPair()
        val first = ConnectFailConnection(ConnectionException.TcpConnectTimeout("192.168.1.44", 54321))
        val second = ScriptedPairingConnection(windowsKeys)
        val third = ScriptedPairingConnection(windowsKeys)
        val connections = ArrayDeque<DeviceConnection>(listOf(first, second, third))
        val coordinator = coordinator(windowsKeys, connectionFactory = { connections.removeFirst() })

        coordinator.startPairing(qrPayload(windowsKeys, hostAddresses = listOf("192.168.1.44", "192.168.1.45", "192.168.1.46")))

        assertTrue(second.connectAttempted)
        assertTrue(!third.connectAttempted)
    }

    @Test
    fun stateIsWaitingForChallengeAfterRequestIsSent() = runTest {
        val windowsKeys = ecKeyPair()
        lateinit var coordinator: DefaultPairingCoordinator
        val connection = InspectingReceiveConnection(windowsKeys) {
            assertTrue(coordinator.state.value is PairingState.WaitingForChallenge)
        }
        coordinator = coordinator(windowsKeys, connectionFactory = { connection })

        coordinator.startPairing(qrPayload(windowsKeys))

        assertTrue(coordinator.state.value is PairingState.WaitingForUserConfirmation)
    }

    private open class ScriptedPairingConnection(
        private val windowsKeys: KeyPair,
        private val corruptAcceptedSignature: Boolean = false,
    ) : DeviceConnection {
        val sent = mutableListOf<ProtocolMessage>()
        val receivedTimeouts = mutableListOf<Long>()
        var connectAttempted = false
        private val windowsNonce = Base64Url.encode(ByteArray(32) { 7 })

        override suspend fun connect(host: String, port: Int) {
            connectAttempted = true
        }

        override suspend fun send(message: ProtocolMessage) {
            sent += message
        }

        override suspend fun receive(): ProtocolMessage {
            error("receiveHandshake is used in pairing tests")
        }

        override open suspend fun receiveHandshake(timeoutMs: Long): ProtocolMessage {
            receivedTimeouts += timeoutMs
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

    private class ConfirmationFailingConnection(
        windowsKeys: KeyPair,
    ) : ScriptedPairingConnection(windowsKeys) {
        override suspend fun receiveHandshake(timeoutMs: Long): ProtocolMessage {
            if (sent.lastOrNull()?.type == ProtocolMessageType.PAIRING_CONFIRM.value) {
                receivedTimeouts += timeoutMs
                throw ConnectionException.Timeout()
            }
            return super.receiveHandshake(timeoutMs)
        }
    }

    private class FailingReceiveConnection(
        private val failure: Throwable,
    ) : DeviceConnection {
        val sent = mutableListOf<ProtocolMessage>()
        override suspend fun connect(host: String, port: Int) = Unit
        override suspend fun send(message: ProtocolMessage) {
            sent += message
        }
        override suspend fun receive(): ProtocolMessage = throw failure
        override suspend fun receiveHandshake(timeoutMs: Long): ProtocolMessage = throw failure
        override suspend fun disconnect() = Unit
    }

    private class FixedResponseConnection(
        private val responseType: String,
    ) : DeviceConnection {
        override suspend fun connect(host: String, port: Int) = Unit
        override suspend fun send(message: ProtocolMessage) = Unit
        override suspend fun receive(): ProtocolMessage = response()
        override suspend fun receiveHandshake(timeoutMs: Long): ProtocolMessage = response()
        override suspend fun disconnect() = Unit

        private fun response() = ProtocolMessage(
            protocolVersion = 1,
            messageId = "response-1",
            type = responseType,
            senderDeviceId = "windows-test",
            recipientDeviceId = "android-test",
            timestampUtc = "2026-07-06T12:00:01Z",
            payload = ProtocolSerializer.payloadToJson(mapOf("code" to "TEST")),
        )
    }

    private class ConnectFailConnection(
        private val failure: Throwable,
    ) : DeviceConnection {
        var connectAttempted = false
        var sentCount = 0
        override suspend fun connect(host: String, port: Int) {
            connectAttempted = true
            throw failure
        }
        override suspend fun send(message: ProtocolMessage) {
            sentCount++
        }
        override suspend fun receive(): ProtocolMessage = error("not connected")
        override suspend fun disconnect() = Unit
    }

    private class InspectingReceiveConnection(
        windowsKeys: KeyPair,
        private val onReceive: () -> Unit,
    ) : ScriptedPairingConnection(windowsKeys) {
        override suspend fun receiveHandshake(timeoutMs: Long): ProtocolMessage {
            onReceive()
            return super.receiveHandshake(timeoutMs)
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

        fun coordinator(
            windowsKeys: KeyPair,
            connectionFactory: () -> DeviceConnection,
        ) = DefaultPairingCoordinator(
            identityRepository = FakeIdentityRepository(),
            identityKeyProvider = FakeIdentityKeyProvider(ecKeyPair()),
            trustedDeviceRepository = FakeTrustedDeviceRepository(),
            connectionFactory = connectionFactory,
            now = { Instant.parse("2026-07-06T12:00:00Z") },
        )

        fun qrPayload(
            windowsKeys: KeyPair,
            hostAddresses: List<String> = listOf("192.168.1.45"),
        ) = PairingQrPayload(
            format = "devicesync-pairing",
            version = 1,
            sessionId = "pair-test",
            pairingSecret = PAIRING_SECRET,
            expiresAtUtc = "2026-07-06T12:02:00Z",
            hostAddresses = hostAddresses,
            port = 54321,
            windowsDeviceId = "windows-test",
            windowsDeviceName = "Windows PC",
            windowsIdentityPublicKey = Base64Url.encode(windowsKeys.public.encoded),
            windowsIdentityFingerprint = SecurityEncoding.fingerprint(windowsKeys.public.encoded),
            tlsServerSpkiFingerprint = SecurityEncoding.fingerprint(windowsKeys.public.encoded),
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
