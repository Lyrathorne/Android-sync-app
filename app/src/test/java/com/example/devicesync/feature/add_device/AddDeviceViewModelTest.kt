package com.example.devicesync.feature.add_device

import com.example.devicesync.MainDispatcherRule
import com.example.devicesync.core.model.ConnectionStatus
import com.example.devicesync.core.model.InMemoryDeviceStore
import com.example.devicesync.core.network.ConnectionException
import com.example.devicesync.core.network.ConnectionManager
import com.example.devicesync.core.network.DeviceConnection
import com.example.devicesync.core.protocol.ProtocolMessage
import com.example.devicesync.core.protocol.helloAckMessage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AddDeviceViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun connectManually_showsLoadingAndSuccess() = runTest {
        val store = InMemoryDeviceStore(initialDevices = emptyList())
        val connection = FakeHandshakeConnection()
        val viewModel = AddDeviceViewModel(store, manager(connection))

        viewModel.showManualForm()
        viewModel.onIpChanged("127.0.0.1")
        viewModel.onPortChanged("53321")
        viewModel.connectManually()

        val state = viewModel.uiState.value
        assertTrue(state.manualConnectionStatus is ManualConnectionStatus.Connected)
        assertEquals("windows-device-id", state.connectedDeviceId)
    }

    @Test
    fun connectManually_addsConnectedDeviceToStore() = runTest {
        val store = InMemoryDeviceStore(initialDevices = emptyList())
        val viewModel = AddDeviceViewModel(store, manager(FakeHandshakeConnection()))

        viewModel.onIpChanged("localhost")
        viewModel.onPortChanged("53321")
        viewModel.connectManually()

        val device = store.devices.value.single()
        assertEquals("Gleb-PC", device.name)
        assertEquals(ConnectionStatus.CONNECTED, device.connectionStatus)
        assertEquals("localhost", device.host)
    }

    @Test
    fun connectManually_showsReadableError() = runTest {
        val store = InMemoryDeviceStore(initialDevices = emptyList())
        val viewModel = AddDeviceViewModel(
            store,
            manager(FakeHandshakeConnection(error = ConnectionException.ConnectionRefused())),
        )

        viewModel.onIpChanged("127.0.0.1")
        viewModel.onPortChanged("53321")
        viewModel.connectManually()

        assertEquals(
            ManualConnectionStatus.Failed("Соединение отклонено"),
            viewModel.uiState.value.manualConnectionStatus,
        )
    }

    private fun manager(connection: FakeHandshakeConnection): ConnectionManager {
        return ConnectionManager(
            connectionFactory = { connection },
            androidDeviceId = "android-device",
            androidDeviceName = "Android Test",
        )
    }
}

private class FakeHandshakeConnection(
    private val error: ConnectionException? = null,
) : DeviceConnection {
    private var hello: ProtocolMessage? = null

    override suspend fun connect(host: String, port: Int) {
        error?.let { throw it }
    }

    override suspend fun send(message: ProtocolMessage) {
        hello = message
    }

    override suspend fun receive(): ProtocolMessage {
        val sentHello = hello ?: error("Hello was not sent")
        return helloAckMessage(correlationId = sentHello.messageId)
    }

    override suspend fun disconnect() = Unit
}
