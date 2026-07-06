package com.example.devicesync.core.network

import com.example.devicesync.core.protocol.ProtocolFrameReader
import com.example.devicesync.core.protocol.ProtocolFrameWriter
import com.example.devicesync.core.protocol.ProtocolMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class TcpDeviceConnection(
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
) : DeviceConnection {
    private val writeMutex = Mutex()
    private var socket: Socket? = null
    private var reader: ProtocolFrameReader? = null
    private var writer: ProtocolFrameWriter? = null

    override suspend fun connect(host: String, port: Int) = withContext(Dispatchers.IO) {
        disconnect()
        try {
            NetworkLogger.info("Connecting to $host:$port")
            val newSocket = Socket()
            newSocket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            newSocket.soTimeout = readTimeoutMs

            socket = newSocket
            reader = ProtocolFrameReader(newSocket.getInputStream())
            writer = ProtocolFrameWriter(newSocket.getOutputStream())
            NetworkLogger.info("TCP connection established")
        } catch (error: CancellationException) {
            disconnect()
            throw error
        } catch (error: SocketTimeoutException) {
            disconnect()
            throw ConnectionException.TcpConnectTimeout(host, port, error)
        } catch (error: Throwable) {
            disconnect()
            throw error.toConnectionException()
        }
    }

    override suspend fun onHandshakeComplete() = withContext(Dispatchers.IO) {
        socket?.soTimeout = 0
        NetworkLogger.info("TCP read timeout disabled after handshake")
    }

    override suspend fun receiveHandshake(timeoutMs: Long): ProtocolMessage = withContext(Dispatchers.IO) {
        withTimeout(timeoutMs) {
            receive()
        }
    }

    override suspend fun send(message: ProtocolMessage) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val currentWriter = writer ?: throw ConnectionException.ConnectionClosed()
            try {
                NetworkLogger.info("Sending ${message.type}")
                currentWriter.write(message)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                disconnect()
                throw error.toConnectionException()
            }
        }
    }

    override suspend fun receive(): ProtocolMessage = withContext(Dispatchers.IO) {
        val currentReader = reader ?: throw ConnectionException.ConnectionClosed()
        try {
            val message = currentReader.read()
            NetworkLogger.info("Received ${message.type}")
            message
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            disconnect()
            throw error.toConnectionException()
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        runCatching { socket?.getOutputStream()?.close() }
        runCatching { socket?.getInputStream()?.close() }
        runCatching { socket?.close() }
        socket = null
        reader = null
        writer = null
        NetworkLogger.info("Disconnected")
    }
}

fun Throwable.toConnectionException(): ConnectionException {
    return when (this) {
        is ConnectionException -> this
        is TimeoutCancellationException -> ConnectionException.Timeout(this)
        is SocketTimeoutException -> ConnectionException.Timeout(this)
        is ConnectException -> ConnectionException.ConnectionRefused(this)
        is UnknownHostException,
        is NoRouteToHostException,
        is IllegalArgumentException -> ConnectionException.InvalidAddress(this)
        is IOException -> ConnectionException.ConnectionClosed(this)
        else -> ConnectionException.Unknown(this)
    }
}
