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
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import com.example.devicesync.core.security.Base64Url
import com.example.devicesync.core.security.SecurityEncoding

class TcpDeviceConnection(
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
) : DeviceConnection {
    private val writeMutex = Mutex()
    private var socket: Socket? = null
    private var reader: ProtocolFrameReader? = null
    private var writer: ProtocolFrameWriter? = null
    @Volatile
    private var expectedTlsServerSpkiFingerprint: String? = null

    override fun setTlsServerSpkiFingerprint(fingerprint: String) {
        require(Base64Url.decode(fingerprint).size == 32) { "TLS server fingerprint is invalid." }
        expectedTlsServerSpkiFingerprint = fingerprint
    }

    override suspend fun connect(host: String, port: Int) = withContext(Dispatchers.IO) {
        disconnect()
        try {
            NetworkLogger.info("TCP_ATTEMPT_STARTED $host:$port")
            val expectedFingerprint = expectedTlsServerSpkiFingerprint
                ?: throw ConnectionException.InvalidMessage("PAIRING_REQUIRED_TLS_PIN")
            val tcpSocket = Socket()
            tcpSocket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            tcpSocket.soTimeout = readTimeoutMs
            val trustManager = PinnedSpkiTrustManager(expectedFingerprint)
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf(trustManager), SecureRandom())
            val newSocket = sslContext.socketFactory
                .createSocket(tcpSocket, host, port, true) as SSLSocket
            newSocket.enabledProtocols = newSocket.supportedProtocols
                .filter { it == "TLSv1.2" || it == "TLSv1.3" }
                .toTypedArray()
            newSocket.startHandshake()
            newSocket.soTimeout = readTimeoutMs

            socket = newSocket
            reader = ProtocolFrameReader(newSocket.getInputStream())
            writer = ProtocolFrameWriter(newSocket.getOutputStream())
            NetworkLogger.info("TLS_CONNECTED ${newSocket.session.protocol}")
        } catch (error: CancellationException) {
            disconnect()
            throw error
        } catch (error: SocketTimeoutException) {
            disconnect()
            NetworkLogger.info("TCP_CONNECT_TIMEOUT $host:$port")
            throw ConnectionException.TcpConnectTimeout(host, port, error)
        } catch (error: SSLHandshakeException) {
            disconnect()
            NetworkLogger.info("TLS_HANDSHAKE_FAILED $host:$port")
            throw ConnectionException.InvalidMessage("TLS_PIN_MISMATCH", error)
        } catch (error: ConnectException) {
            disconnect()
            NetworkLogger.info("TCP_CONNECT_REFUSED $host:$port")
            throw ConnectionException.ConnectionRefused(error)
        } catch (error: NoRouteToHostException) {
            disconnect()
            NetworkLogger.info("TCP_CONNECT_FAILED $host:$port NO_ROUTE_TO_HOST")
            throw ConnectionException.NoRouteToHost(error)
        } catch (error: UnknownHostException) {
            disconnect()
            NetworkLogger.info("TCP_CONNECT_FAILED $host:$port INVALID_ADDRESS")
            throw ConnectionException.InvalidAddress(error)
        } catch (error: IllegalArgumentException) {
            disconnect()
            NetworkLogger.info("TCP_CONNECT_FAILED $host:$port INVALID_ADDRESS")
            throw ConnectionException.InvalidAddress(error)
        } catch (error: Throwable) {
            disconnect()
            NetworkLogger.info("TCP_CONNECT_FAILED $host:$port")
            throw error.toConnectionException()
        }
    }

    override suspend fun onHandshakeComplete() = withContext(Dispatchers.IO) {
        socket?.soTimeout = 0
        NetworkLogger.info("TCP read timeout disabled after handshake")
    }

    override suspend fun setReadTimeout(timeoutMs: Int) = withContext(Dispatchers.IO) {
        socket?.soTimeout = timeoutMs
    }

    override suspend fun receiveHandshake(timeoutMs: Long): ProtocolMessage = withContext(Dispatchers.IO) {
        socket?.soTimeout = timeoutMs.toInt()
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

internal class PinnedSpkiTrustManager(
    private val expectedFingerprint: String,
) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val certificate = chain?.firstOrNull() ?: throw CertificateException("Server certificate is missing.")
        if (!matchesPinnedSpki(expectedFingerprint, certificate.publicKey.encoded)) {
            throw CertificateException("TLS server public key pin mismatch.")
        }
        certificate.checkValidity()
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

internal fun matchesPinnedSpki(expectedFingerprint: String, publicKeySpki: ByteArray): Boolean = runCatching {
    val actual = SecurityEncoding.fingerprint(publicKeySpki)
    SecurityEncoding.fixedTimeEquals(Base64Url.decode(expectedFingerprint), Base64Url.decode(actual))
}.getOrDefault(false)

fun Throwable.toConnectionException(): ConnectionException {
    return when (this) {
        is ConnectionException -> this
        is TimeoutCancellationException -> ConnectionException.Timeout(this)
        is SocketTimeoutException -> ConnectionException.Timeout(this)
        is ConnectException -> ConnectionException.ConnectionRefused(this)
        is NoRouteToHostException -> ConnectionException.NoRouteToHost(this)
        is UnknownHostException,
        is IllegalArgumentException -> ConnectionException.InvalidAddress(this)
        is IOException -> ConnectionException.ConnectionClosed(this)
        else -> ConnectionException.Unknown(this)
    }
}
