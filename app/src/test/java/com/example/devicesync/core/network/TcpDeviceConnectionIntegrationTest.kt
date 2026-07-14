package com.example.devicesync.core.network

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpDeviceConnectionIntegrationTest {
    @Test
    fun tcpConnectionRefusesToConnectWithoutPinnedTlsIdentity() = runTest {
        val connection = TcpDeviceConnection(connectTimeoutMs = 100, readTimeoutMs = 100)

        val error = runCatching { connection.connect("127.0.0.1", 54321) }.exceptionOrNull()

        assertTrue(error is ConnectionException.InvalidMessage)
        assertTrue(error?.message == "PAIRING_REQUIRED_TLS_PIN")
    }
}
