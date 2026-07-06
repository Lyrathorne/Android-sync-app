package com.example.devicesync.core.network

import com.example.devicesync.core.protocol.ProtocolMessage
import com.example.devicesync.core.protocol.ProtocolMessageType
import com.example.devicesync.core.protocol.ConnectionHelloPayload
import com.example.devicesync.core.protocol.AuthAcceptedPayload
import com.example.devicesync.core.protocol.AuthChallengePayload
import com.example.devicesync.core.protocol.AuthResponsePayload
import com.example.devicesync.core.protocol.ProtocolSerializer
import com.example.devicesync.core.data.OutgoingMessageQueue
import com.example.devicesync.core.data.PendingMessage
import com.example.devicesync.core.protocol.helloAckMessage
import com.example.devicesync.core.security.Base64Url
import com.example.devicesync.core.security.DeviceIdentityKeyProvider
import com.example.devicesync.core.security.SecurityEncoding
import com.example.devicesync.core.security.TranscriptBuilder
import com.example.devicesync.core.security.TrustedDevice
import com.example.devicesync.core.security.TrustedDeviceRepository
import com.example.devicesync.core.settings.DeviceIdentityRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionManagerTest {
    @Test
    fun connect_movesFromDisconnectedToConnecting() = runTest {
        val releaseConnect = CompletableDeferred<Unit>()
        val fakeConnection = FakeDeviceConnection(onConnect = { releaseConnect.await() })
        val manager = manager(fakeConnection)

        val job = launch { manager.connect("127.0.0.1", 53321) }
        runCurrent()

        assertEquals(ConnectionState.Connecting("127.0.0.1", 53321), manager.state.value)
        releaseConnect.complete(Unit)
        job.join()
    }

    @Test
    fun connect_movesToHandshaking() = runTest {
        val releaseReceive = CompletableDeferred<Unit>()
        val fakeConnection = FakeDeviceConnection(onReceive = { releaseReceive.await() })
        val manager = manager(fakeConnection)

        val job = launch { manager.connect("127.0.0.1", 53321) }
        runCurrent()

        assertEquals(ConnectionState.Handshaking("127.0.0.1", 53321), manager.state.value)
        releaseReceive.complete(Unit)
        job.join()
    }

    @Test
    fun connect_movesToConnectedAfterValidAck() = runTest {
        val fakeConnection = FakeDeviceConnection()
        val manager = manager(fakeConnection)

        val connected = manager.connect("127.0.0.1", 53321)

        assertEquals("windows-device-id", connected.deviceId)
        assertEquals("Gleb-PC", connected.deviceName)
        assertEquals(ConnectionState.Connected::class, manager.state.value::class)
    }

    @Test
    fun connect_failsWhenCorrelationIdDoesNotMatch() = runTest {
        val fakeConnection = FakeDeviceConnection(
            responseProvider = { helloAckMessage(correlationId = "different-id") }
        )
        val manager = manager(fakeConnection)

        runCatching { manager.connect("127.0.0.1", 53321) }

        assertTrue(fakeConnection.disconnectCalled)
        assertEquals(ConnectionState.Failed("Получен некорректный ответ"), manager.state.value)
    }

    @Test
    fun connect_failsWhenProtocolVersionIsUnsupported() = runTest {
        val fakeConnection = FakeDeviceConnection(
            responseProvider = { sentHello ->
                helloAckMessage(
                    correlationId = sentHello.messageId,
                    acceptedProtocolVersion = 2,
                )
            }
        )
        val manager = manager(fakeConnection)

        runCatching { manager.connect("127.0.0.1", 53321) }

        assertEquals(ConnectionState.Failed("Версия протокола не поддерживается"), manager.state.value)
        assertTrue(fakeConnection.disconnectCalled)
    }

    @Test
    fun disconnect_movesToDisconnected() = runTest {
        val fakeConnection = FakeDeviceConnection()
        val manager = manager(fakeConnection)
        manager.connect("127.0.0.1", 53321)

        manager.disconnect()

        assertEquals(ConnectionState.Disconnected, manager.state.value)
        assertTrue(fakeConnection.disconnectCalled)
    }

    @Test
    fun revokeTrust_closesActiveConnectionAndClearsTrustAndPendingMessages() = runTest {
        val fakeConnection = FakeDeviceConnection()
        val queue = TestOutgoingQueue()
        val trust = MutableTrustedDeviceRepository()
        val manager = ConnectionManager(
            connectionFactory = { fakeConnection },
            androidDeviceId = "android-device",
            androidDeviceName = "Android Test",
            outgoingMessageQueue = queue,
            trustedDeviceRepository = trust,
        )
        manager.connect("127.0.0.1", 53321)

        manager.revokeTrust("windows-device-id")

        assertTrue(fakeConnection.disconnectCalled)
        assertEquals("windows-device-id", queue.deletedForDevice)
        assertEquals("windows-device-id", trust.revokedDeviceId)
        assertEquals(ConnectionState.PairingRequired, manager.state.value)
    }

    @Test
    fun mutualAuth_sendsNonceAndConnectsOnlyAfterAccepted() = runTest {
        val androidKeys = TestIdentityKeyProvider(ecKeyPair())
        val windowsKeys = ecKeyPair()
        val connection = AuthDeviceConnection(windowsKeys)
        val manager = ConnectionManager(
            connectionFactory = { connection },
            identityRepository = TestDeviceIdentityRepository(),
            identityKeyProvider = androidKeys,
            trustedDeviceRepository = TestTrustedDeviceRepository(windowsKeys),
        )

        val connected = manager.connect("127.0.0.1", 53321)

        val hello = connection.sent.first { it.type == ProtocolMessageType.CONNECTION_HELLO.value }
        val helloPayload = ProtocolSerializer.decodePayload<ConnectionHelloPayload>(hello.payload)
        assertEquals(1, helloPayload.authVersion)
        assertEquals(androidKeys.getPublicKeyFingerprint(), helloPayload.identityFingerprint)
        assertEquals(32, Base64Url.decode(helloPayload.clientNonce!!).size)
        assertTrue(connection.sent.any { it.type == ProtocolMessageType.AUTH_RESPONSE.value })
        assertEquals("windows-device-id", connected.deviceId)
        assertEquals(ConnectionState.Connected::class, manager.state.value::class)
    }

    @Test
    fun connect_addressesTriesNextAfterFirstConnectTimeout() = runTest {
        val first = AddressAttemptConnection(
            onConnectAttempt = { host, port -> throw ConnectionException.TcpConnectTimeout(host, port) },
        )
        val second = AddressAttemptConnection()
        val attempts = ArrayDeque(listOf(first, second))
        val manager = ConnectionManager(
            connectionFactory = { attempts.removeFirst() },
            androidDeviceId = "android-device",
            androidDeviceName = "Android Test",
        )

        val connected = manager.connect(listOf("192.168.1.40", "192.168.1.41"), 53321)

        assertEquals("windows-device-id", connected.deviceId)
        assertEquals(listOf("192.168.1.40"), first.connectedHosts)
        assertEquals(listOf("192.168.1.41"), second.connectedHosts)
    }

    @Test
    fun connect_addressesExhaustedReturnsReadableTcpTimeout() = runTest {
        val first = AddressAttemptConnection(
            onConnectAttempt = { host, port -> throw ConnectionException.TcpConnectTimeout(host, port) },
        )
        val manager = ConnectionManager(
            connectionFactory = { first },
            androidDeviceId = "android-device",
            androidDeviceName = "Android Test",
        )

        val error = runCatching { manager.connect(listOf("192.168.1.40"), 53321) }.exceptionOrNull()

        assertTrue(error is ConnectionException.TcpConnectTimeout)
        assertEquals(
            "Не удалось подключиться к 192.168.1.40:53321.\nПроверьте Windows Firewall и подключение к одной сети.",
            error?.message,
        )
    }

    @Test
    fun connect_handshakeTimeoutIsDistinctFromTcpConnectTimeout() = runTest {
        val connection = AddressAttemptConnection(
            responseProvider = { throw ConnectionException.Timeout() },
        )
        val manager = ConnectionManager(
            connectionFactory = { connection },
            androidDeviceId = "android-device",
            androidDeviceName = "Android Test",
        )

        val error = runCatching { manager.connect("192.168.1.41", 53321) }.exceptionOrNull()

        assertTrue(error is ConnectionException.HandshakeTimeout)
    }

    private fun manager(fakeConnection: FakeDeviceConnection): ConnectionManager {
        return ConnectionManager(
            connectionFactory = { fakeConnection },
            androidDeviceId = "android-device",
            androidDeviceName = "Android Test",
        )
    }
}

private class AuthDeviceConnection(private val windowsKeys: KeyPair) : DeviceConnection {
    val sent = mutableListOf<ProtocolMessage>()
    private var receiveCount = 0
    override suspend fun connect(host: String, port: Int) = Unit
    override suspend fun send(message: ProtocolMessage) {
        sent += message
    }
    override suspend fun receive(): ProtocolMessage {
        receiveCount += 1
        return when (receiveCount) {
            1 -> challenge()
            2 -> accepted()
            else -> CompletableDeferred<ProtocolMessage>().await()
        }
    }
    override suspend fun disconnect() = Unit

    private fun challenge(): ProtocolMessage {
        val hello = sent.first { it.type == ProtocolMessageType.CONNECTION_HELLO.value }
        val payload = ProtocolSerializer.decodePayload<ConnectionHelloPayload>(hello.payload)
        val serverNonce = Base64Url.encode(ByteArray(32) { 4 })
        val transcript = TranscriptBuilder.sessionAuth(
            protocolVersion = 1,
            androidDeviceId = hello.senderDeviceId,
            windowsDeviceId = "windows-device-id",
            androidFingerprint = payload.identityFingerprint!!,
            windowsFingerprint = SecurityEncoding.fingerprint(windowsKeys.public.encoded),
            clientNonce = payload.clientNonce!!,
            serverNonce = serverNonce,
            helloMessageId = hello.messageId,
        )
        return ProtocolMessage(
            protocolVersion = 1,
            messageId = "auth-challenge-1",
            type = ProtocolMessageType.AUTH_CHALLENGE.value,
            senderDeviceId = "windows-device-id",
            recipientDeviceId = hello.senderDeviceId,
            timestampUtc = "2026-07-06T12:00:00Z",
            correlationId = hello.messageId,
            payload = ProtocolSerializer.payloadToJson(
                AuthChallengePayload(
                    serverNonce = serverNonce,
                    windowsIdentityFingerprint = SecurityEncoding.fingerprint(windowsKeys.public.encoded),
                    serverSignature = Base64Url.encode(sign(windowsKeys, transcript)),
                    helloMessageId = hello.messageId,
                )
            ),
        )
    }

    private fun accepted(): ProtocolMessage {
        val response = sent.first { it.type == ProtocolMessageType.AUTH_RESPONSE.value }
        val payload = ProtocolSerializer.decodePayload<AuthResponsePayload>(response.payload)
        return ProtocolMessage(
            protocolVersion = 1,
            messageId = "auth-accepted-1",
            type = ProtocolMessageType.AUTH_ACCEPTED.value,
            senderDeviceId = "windows-device-id",
            recipientDeviceId = response.senderDeviceId,
            timestampUtc = "2026-07-06T12:00:01Z",
            correlationId = response.messageId,
            payload = ProtocolSerializer.payloadToJson(AuthAcceptedPayload(status = "accepted")),
        ).also { assertEquals(sent.first().messageId, payload.helloMessageId) }
    }
}

private class AddressAttemptConnection(
    private val onConnectAttempt: suspend (String, Int) -> Unit = { _, _ -> },
    private val responseProvider: (ProtocolMessage) -> ProtocolMessage = { sentHello ->
        helloAckMessage(correlationId = sentHello.messageId)
    },
) : DeviceConnection {
    val connectedHosts = mutableListOf<String>()
    private var sentHello: ProtocolMessage? = null
    private var helloAckReturned = false

    override suspend fun connect(host: String, port: Int) {
        connectedHosts += host
        onConnectAttempt(host, port)
    }

    override suspend fun send(message: ProtocolMessage) {
        if (message.type == ProtocolMessageType.CONNECTION_HELLO.value) {
            sentHello = message
        }
    }

    override suspend fun receive(): ProtocolMessage {
        if (helloAckReturned) {
            CompletableDeferred<Unit>().await()
        }
        helloAckReturned = true
        return responseProvider(sentHello ?: error("Hello was not sent"))
    }

    override suspend fun disconnect() = Unit
}

private class TestDeviceIdentityRepository : DeviceIdentityRepository {
    override suspend fun getOrCreateDeviceId(): String = "android-device"
    override suspend fun getDeviceName(): String = "Android Test"
    override suspend fun updateDeviceName(name: String) = Unit
}

private class TestIdentityKeyProvider(private val keys: KeyPair) : DeviceIdentityKeyProvider {
    override suspend fun getOrCreatePublicKey(): ByteArray = keys.public.encoded
    override suspend fun getPublicKeyFingerprint(): String = SecurityEncoding.fingerprint(keys.public.encoded)
    override suspend fun sign(data: ByteArray): ByteArray = sign(keys, data)
    override suspend fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
        val parsed = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKey))
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(parsed)
        verifier.update(data)
        return verifier.verify(signature)
    }
}

private class TestTrustedDeviceRepository(windowsKeys: KeyPair) : TrustedDeviceRepository {
    private val trusted = TrustedDevice(
        deviceId = "windows-device-id",
        deviceName = "Gleb-PC",
        identityPublicKey = Base64Url.encode(windowsKeys.public.encoded),
        identityFingerprint = SecurityEncoding.fingerprint(windowsKeys.public.encoded),
        futureTlsCertificateFingerprint = null,
        pairedAt = Instant.parse("2026-07-06T12:00:00Z"),
        lastVerifiedAt = null,
        revokedAt = null,
    )
    private val devices = MutableStateFlow(listOf(trusted))
    override fun observeTrustedDevices(): Flow<List<TrustedDevice>> = devices
    override suspend fun getTrustedDevice(deviceId: String): TrustedDevice? = trusted.takeIf { it.deviceId == deviceId }
    override suspend fun saveTrustedDevice(device: TrustedDevice) = Unit
    override suspend fun updateLastVerifiedAt(deviceId: String, timestamp: Instant) = Unit
    override suspend fun revoke(deviceId: String, timestamp: Instant) = Unit
    override suspend fun delete(deviceId: String) = Unit
}

private class MutableTrustedDeviceRepository : TrustedDeviceRepository {
    var revokedDeviceId: String? = null
    private val devices = MutableStateFlow<List<TrustedDevice>>(emptyList())
    override fun observeTrustedDevices(): Flow<List<TrustedDevice>> = devices
    override suspend fun getTrustedDevice(deviceId: String): TrustedDevice? = null
    override suspend fun saveTrustedDevice(device: TrustedDevice) = Unit
    override suspend fun updateLastVerifiedAt(deviceId: String, timestamp: Instant) = Unit
    override suspend fun revoke(deviceId: String, timestamp: Instant) {
        revokedDeviceId = deviceId
    }
    override suspend fun delete(deviceId: String) = Unit
}

private class TestOutgoingQueue : OutgoingMessageQueue {
    var deletedForDevice: String? = null
    override suspend fun enqueue(message: ProtocolMessage) = Unit
    override fun observePendingMessages(): Flow<List<PendingMessage>> = MutableStateFlow(emptyList())
    override suspend fun pendingForDevice(deviceId: String): List<PendingMessage> = emptyList()
    override suspend fun markSent(messageId: String) = Unit
    override suspend fun markAcknowledged(messageId: String) = Unit
    override suspend fun markFailed(messageId: String, reason: String) = Unit
    override suspend fun deleteForDevice(deviceId: String) {
        deletedForDevice = deviceId
    }
}

private fun ecKeyPair(): KeyPair {
    val generator = KeyPairGenerator.getInstance("EC")
    generator.initialize(ECGenParameterSpec("secp256r1"))
    return generator.generateKeyPair()
}

private fun sign(keys: KeyPair, data: ByteArray): ByteArray {
    val signature = Signature.getInstance("SHA256withECDSA")
    signature.initSign(keys.private)
    signature.update(data)
    return signature.sign()
}

private class FakeDeviceConnection(
    private val responseProvider: (ProtocolMessage) -> ProtocolMessage = { sentHello ->
        helloAckMessage(correlationId = sentHello.messageId)
    },
    private val onConnect: suspend () -> Unit = {},
    private val onReceive: suspend () -> Unit = {},
) : DeviceConnection {
    private var sentHello: ProtocolMessage? = null
    private var helloAckReturned = false
    var disconnectCalled = false
        private set

    override suspend fun connect(host: String, port: Int) {
        onConnect()
    }

    override suspend fun send(message: ProtocolMessage) {
        if (message.type == ProtocolMessageType.CONNECTION_HELLO.value) {
            sentHello = message
        }
    }

    override suspend fun receive(): ProtocolMessage {
        onReceive()
        if (helloAckReturned) {
            CompletableDeferred<Unit>().await()
        }
        helloAckReturned = true
        return responseProvider(sentHello ?: error("Hello was not sent"))
    }

    override suspend fun disconnect() {
        disconnectCalled = true
    }
}
