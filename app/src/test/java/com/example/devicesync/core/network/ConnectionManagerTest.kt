package com.example.devicesync.core.network

import com.example.devicesync.core.protocol.ProtocolMessage
import com.example.devicesync.core.protocol.ProtocolMessageType
import com.example.devicesync.core.protocol.helloAckMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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

    private fun manager(fakeConnection: FakeDeviceConnection): ConnectionManager {
        return ConnectionManager(
            connectionFactory = { fakeConnection },
            androidDeviceId = "android-device",
            androidDeviceName = "Android Test",
        )
    }
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
