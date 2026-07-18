package com.example.devicesync.core.network

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import com.example.devicesync.core.protocol.ProtocolFrameReader
import com.example.devicesync.core.protocol.ProtocolFrameWriter
import com.example.devicesync.core.protocol.ProtocolMessage
import com.example.devicesync.core.security.Base64Url
import com.example.devicesync.core.security.SecurityEncoding
import java.io.ByteArrayInputStream
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.bouncycastle.tls.Certificate
import org.bouncycastle.tls.DefaultTlsClient
import org.bouncycastle.tls.TlsAuthentication
import org.bouncycastle.tls.TlsClientProtocol
import org.bouncycastle.tls.TlsCredentials
import org.bouncycastle.tls.TlsServerCertificate
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto

class BluetoothRfcommDeviceConnection(
    private val adapterProvider: () -> BluetoothAdapter?,
) : DeviceConnection {
    companion object {
        val SERVICE_ID: UUID = UUID.fromString("7d7d8f4a-6bd1-4d98-9b9c-5aa89f4a6210")
        const val MAXIMUM_FILE_BYTES: Long = 2L * 1024 * 1024
    }

    override val transportKind: TransportKind = TransportKind.BLUETOOTH_RFCOMM
    private val writeMutex = Mutex()
    private var socket: BluetoothSocket? = null
    private var tls: TlsClientProtocol? = null
    private var reader: ProtocolFrameReader? = null
    private var writer: ProtocolFrameWriter? = null
    @Volatile private var expectedFingerprint: String? = null

    override fun setTlsServerSpkiFingerprint(fingerprint: String) {
        require(Base64Url.decode(fingerprint).size == 32) { "TLS server fingerprint is invalid." }
        expectedFingerprint = fingerprint
    }

    override suspend fun connect(endpoint: TransportEndpoint) {
        require(endpoint.kind == TransportKind.BLUETOOTH_RFCOMM)
        connect(endpoint.address, 0)
    }

    @SuppressLint("MissingPermission")
    override suspend fun connect(host: String, port: Int) = withContext(Dispatchers.IO) {
        disconnect()
        val fingerprint = expectedFingerprint
            ?: throw ConnectionException.InvalidMessage("PAIRING_REQUIRED_TLS_PIN")
        val adapter = adapterProvider()
            ?: throw ConnectionException.InvalidMessage("BLUETOOTH_UNAVAILABLE")
        if (!adapter.isEnabled) throw ConnectionException.InvalidMessage("BLUETOOTH_DISABLED")
        try {
            adapter.cancelDiscovery()
            val device = adapter.getRemoteDevice(host)
            val activeSocket = device.createRfcommSocketToServiceRecord(SERVICE_ID)
            socket = activeSocket
            activeSocket.connect()
            val protocol = TlsClientProtocol(activeSocket.inputStream, activeSocket.outputStream)
            protocol.connect(PinnedTlsClient(fingerprint))
            tls = protocol
            reader = ProtocolFrameReader(protocol.inputStream)
            writer = ProtocolFrameWriter(protocol.outputStream)
            NetworkLogger.info("BLUETOOTH_TLS_CONNECTED slowTransport=true")
        } catch (error: CancellationException) {
            disconnect()
            throw error
        } catch (error: SecurityException) {
            disconnect()
            throw ConnectionException.InvalidMessage("BLUETOOTH_PERMISSION_REQUIRED", error)
        } catch (error: Throwable) {
            disconnect()
            throw error.toConnectionException()
        }
    }

    override suspend fun receiveHandshake(timeoutMs: Long): ProtocolMessage = withTimeout(timeoutMs) { receive() }

    override suspend fun send(message: ProtocolMessage) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            writer?.write(message) ?: throw ConnectionException.ConnectionClosed()
        }
    }

    override suspend fun receive(): ProtocolMessage = withContext(Dispatchers.IO) {
        reader?.read() ?: throw ConnectionException.ConnectionClosed()
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        runCatching { tls?.close() }
        runCatching { socket?.close() }
        tls = null
        socket = null
        reader = null
        writer = null
    }

    private class PinnedTlsClient(
        private val expectedFingerprint: String,
    ) : DefaultTlsClient(BcTlsCrypto(SecureRandom())) {
        override fun getAuthentication(): TlsAuthentication = object : TlsAuthentication {
            override fun notifyServerCertificate(serverCertificate: TlsServerCertificate) {
                verifyCertificate(serverCertificate.certificate)
            }

            override fun getClientCredentials(certificateRequest: org.bouncycastle.tls.CertificateRequest): TlsCredentials? = null
        }

        private fun verifyCertificate(chain: Certificate) {
            val encoded = chain.certificateList.firstOrNull()?.encoded
                ?: throw java.security.cert.CertificateException("Server certificate is missing.")
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(encoded)) as X509Certificate
            certificate.checkValidity()
            val actual = SecurityEncoding.fingerprint(certificate.publicKey.encoded)
            if (!SecurityEncoding.fixedTimeEquals(Base64Url.decode(expectedFingerprint), Base64Url.decode(actual))) {
                throw java.security.cert.CertificateException("TLS server public key pin mismatch.")
            }
        }
    }
}
