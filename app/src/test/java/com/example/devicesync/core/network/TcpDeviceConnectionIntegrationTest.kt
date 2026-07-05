package com.example.devicesync.core.network

import com.example.devicesync.core.protocol.ProtocolFrameReader
import com.example.devicesync.core.protocol.ProtocolFrameWriter
import com.example.devicesync.core.protocol.ProtocolMessageType
import com.example.devicesync.core.protocol.helloAckMessage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class TcpDeviceConnectionIntegrationTest {
    @Test
    fun connectionManagerCompletesHelloHandshakeWithLocalTcpServer() = runTest {
        ServerSocket(0).use { serverSocket ->
            val serverThread = thread(start = true) {
                serverSocket.accept().use { socket ->
                    val reader = ProtocolFrameReader(socket.getInputStream())
                    val writer = ProtocolFrameWriter(socket.getOutputStream())
                    val hello = reader.read()

                    assertEquals(ProtocolMessageType.CONNECTION_HELLO.value, hello.type)
                    writer.write(helloAckMessage(correlationId = hello.messageId))
                }
            }
            val manager = ConnectionManager(
                connectionFactory = { TcpDeviceConnection(connectTimeoutMs = 2_000, readTimeoutMs = 2_000) },
                androidDeviceId = "android-device",
                androidDeviceName = "Android Test",
            )

            val connected = manager.connect("127.0.0.1", serverSocket.localPort)

            assertEquals("Gleb-PC", connected.deviceName)
            assertEquals("windows-device-id", connected.deviceId)
            serverThread.join(2_000)
        }
    }
}
