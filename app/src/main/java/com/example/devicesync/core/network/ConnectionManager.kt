package com.example.devicesync.core.network

import com.example.devicesync.BuildConfig
import com.example.devicesync.core.data.DeviceRepository
import com.example.devicesync.core.data.OutgoingMessageQueue
import com.example.devicesync.core.data.PairedDevice
import com.example.devicesync.core.data.ProcessedMessageRepository
import com.example.devicesync.core.discovery.DiscoveryAddressSelector
import com.example.devicesync.core.model.ConnectionStatus
import com.example.devicesync.core.protocol.ConnectionClosePayload
import com.example.devicesync.core.protocol.ConnectionHelloAckPayload
import com.example.devicesync.core.protocol.ConnectionHelloPayload
import com.example.devicesync.core.protocol.AuthAcceptedPayload
import com.example.devicesync.core.protocol.AuthChallengePayload
import com.example.devicesync.core.protocol.AuthResponsePayload
import com.example.devicesync.core.protocol.MessageAckPayload
import com.example.devicesync.core.protocol.PingPayload
import com.example.devicesync.core.protocol.PongPayload
import com.example.devicesync.core.protocol.ProtocolErrorPayload
import com.example.devicesync.core.protocol.ProtocolMessage
import com.example.devicesync.core.protocol.ProtocolMessageType
import com.example.devicesync.core.protocol.ProtocolSerializer
import com.example.devicesync.core.security.Base64Url
import com.example.devicesync.core.security.DeviceIdentityKeyProvider
import com.example.devicesync.core.security.SecurityEncoding
import com.example.devicesync.core.security.TranscriptBuilder
import com.example.devicesync.core.security.TrustedDeviceRepository
import com.example.devicesync.core.settings.AppSettingsRepository
import com.example.devicesync.core.settings.DeviceIdentityRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

class ConnectionManager(
    private val connectionFactory: () -> DeviceConnection = { TcpDeviceConnection() },
    private val androidDeviceId: String = UUID.randomUUID().toString(),
    private val androidDeviceName: String = android.os.Build.MODEL.orEmpty().ifBlank { "Android device" },
    private val appVersion: String = BuildConfig.VERSION_NAME,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val identityRepository: DeviceIdentityRepository? = null,
    private val deviceRepository: DeviceRepository? = null,
    private val outgoingMessageQueue: OutgoingMessageQueue? = null,
    private val processedMessageRepository: ProcessedMessageRepository? = null,
    private val settingsRepository: AppSettingsRepository? = null,
    private val networkMonitor: NetworkMonitor? = null,
    private val identityKeyProvider: DeviceIdentityKeyProvider? = null,
    private val trustedDeviceRepository: TrustedDeviceRepository? = null,
    private val heartbeatConfig: HeartbeatConfig = HeartbeatConfig(),
    private val ackConfig: AckConfig = AckConfig(),
    private val reconnectConfig: ReconnectConfig = ReconnectConfig(),
    private val addressSelector: DiscoveryAddressSelector = DiscoveryAddressSelector(),
) : FileTransferTransport, SharingTransport {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val connectionAttemptMutex = Mutex()
    private val terminationMutex = Mutex()
    private val ackMutex = Mutex()

    private var connection: DeviceConnection? = null
    private var writerChannel: Channel<ProtocolMessage>? = null
    private var writerJob: Job? = null
    private var readerJob: Job? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var startupConnectJob: Job? = null
    private var manualDisconnect = false
    private var activeDevice: PairedDevice? = null
    private var remoteDeviceId: String? = null
    private var pingSequence = 0L
    private var missedPongs = 0
    private var pendingPong: PendingPong? = null
    private var sessionId = 0L
    private var terminatedSessionId = 0L
    private val pendingAcks = mutableMapOf<String, ProtocolMessage>()
    private val ackRetryJobs = mutableMapOf<String, Job>()
    private var sessionSecurityState: SessionSecurityState = SessionSecurityState.Unauthenticated
    private val fileTransferListeners = CopyOnWriteArraySet<FileTransferMessageListener>()
    private val sharingListeners = CopyOnWriteArraySet<SharingMessageListener>()

    suspend fun connect(host: String, port: Int): ConnectionState.Connected {
        return connectManually(host, port)
    }

    suspend fun connect(hostAddresses: List<String>, port: Int): ConnectionState.Connected {
        val orderedAddresses = addressSelector.orderedUsableAddresses(hostAddresses)
        if (orderedAddresses.isEmpty()) {
            throw ConnectionException.InvalidAddress()
        }

        var lastError: ConnectionException? = null
        for (host in orderedAddresses) {
            try {
                return connectManually(host, port)
            } catch (error: ConnectionException) {
                lastError = error
            }
        }

        throw lastError ?: ConnectionException.InvalidAddress()
    }

    suspend fun connectManually(host: String, port: Int): ConnectionState.Connected {
        manualDisconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        NetworkLogger.info("Manual connection started")
        return connectInternal(host, port, ConnectionAttemptSource.Manual)
    }

    fun startStartupAutoConnect() {
        if (startupConnectJob?.isActive == true) return
        val settingsRepository = settingsRepository ?: return
        val deviceRepository = deviceRepository ?: return
        startupConnectJob = scope.launch {
            try {
                val settings = settingsRepository.settings.first()
                if (!settings.autoConnectEnabled || !settings.restoreConnectionEnabled) return@launch
                val deviceId = settings.lastSelectedDeviceId ?: return@launch
                val device = deviceRepository.getDevice(deviceId)
                if (device == null) {
                    settingsRepository.setLastSelectedDeviceId(null)
                    return@launch
                }
                val monitor = networkMonitor
                if (monitor != null) {
                    monitor.networkState.first { state -> state is NetworkState.Available }
                }
                connectInternal(device.host, device.port, ConnectionAttemptSource.Startup)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val connectionError = error.toConnectionException()
                NetworkLogger.error("Startup auto-connect failed", connectionError)
                _state.value = ConnectionState.Failed(connectionError.message.orEmpty())
            }
        }
    }

    suspend fun disconnect() {
        manualDisconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        NetworkLogger.info("Manual disconnect")
        terminateSession(SessionTerminationReason.ManualDisconnect, reconnectAllowed = false)
    }

    suspend fun disconnectDevice(deviceId: String) {
        if (activeDevice?.id == deviceId || remoteDeviceId == deviceId) {
            disconnect()
        }
        settingsRepository?.setLastSelectedDeviceId(null)
    }

    suspend fun revokeTrust(deviceId: String) {
        manualDisconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        val wasActive = activeDevice?.id == deviceId || remoteDeviceId == deviceId
        if (wasActive) {
            terminateSession(SessionTerminationReason.ManualDisconnect, reconnectAllowed = false)
        }
        ackMutex.withLock {
            val revokedMessageIds = pendingAcks
                .filterValues { it.recipientDeviceId == deviceId }
                .keys
                .toSet()
            revokedMessageIds.forEach { messageId ->
                pendingAcks.remove(messageId)
                ackRetryJobs.remove(messageId)?.cancel()
            }
        }
        outgoingMessageQueue?.deleteForDevice(deviceId)
        trustedDeviceRepository?.revoke(deviceId, Instant.now())
        if (settingsRepository?.settings?.first()?.lastSelectedDeviceId == deviceId) {
            settingsRepository.setLastSelectedDeviceId(null)
        }
        if (activeDevice?.id == deviceId) {
            activeDevice = null
        }
        if (remoteDeviceId == deviceId) {
            remoteDeviceId = null
        }
        _state.value = ConnectionState.PairingRequired
    }

    suspend fun sendQueued(message: ProtocolMessage) {
        if (message.requiresAcknowledgement) {
            outgoingMessageQueue?.enqueue(message)
            ackMutex.withLock {
                pendingAcks[message.messageId] = message
            }
            startAckRetry(message)
        }
        writerChannel?.send(message)
    }

    override suspend fun sendFileTransferMessage(type: String, payload: JsonElement) {
        val connected = state.value as? ConnectionState.Connected
            ?: throw ConnectionException.InvalidMessage("File transfer requires an active connection")
        if (SupportedCapabilities.FILE_TRANSFER_V1 !in connected.capabilities) {
            throw ConnectionException.InvalidMessage("The connected device does not support file-transfer-v1")
        }
        if (sessionSecurityState != SessionSecurityState.Authenticated) {
            throw ConnectionException.InvalidMessage("File transfer requires an authenticated connection")
        }
        val channel = writerChannel
            ?: throw ConnectionException.InvalidMessage("Connection writer is unavailable")
        channel.send(
            ProtocolMessage(
                protocolVersion = PROTOCOL_VERSION,
                messageId = UUID.randomUUID().toString(),
                type = type,
                senderDeviceId = getAndroidDeviceId(),
                recipientDeviceId = connected.deviceId,
                timestampUtc = Instant.now().toString(),
                requiresAcknowledgement = false,
                payload = payload,
            )
        )
    }

    override fun setFileTransferListener(listener: FileTransferMessageListener?) {
        fileTransferListeners.clear()
        if (listener != null) fileTransferListeners += listener
    }

    override fun addFileTransferListener(listener: FileTransferMessageListener) { fileTransferListeners += listener }
    override fun removeFileTransferListener(listener: FileTransferMessageListener) { fileTransferListeners -= listener }

    override suspend fun sendSharingMessage(type: String, payload: JsonElement) = sendFileTransferMessage(type, payload)
    override fun addSharingListener(listener: SharingMessageListener) { sharingListeners += listener }
    override fun removeSharingListener(listener: SharingMessageListener) { sharingListeners -= listener }

    private suspend fun connectInternal(
        host: String,
        port: Int,
        source: ConnectionAttemptSource,
    ): ConnectionState.Connected = connectionAttemptMutex.withLock {
        if (source != ConnectionAttemptSource.Reconnect) {
            reconnectJob?.cancel()
            reconnectJob = null
        }
        terminateSession(SessionTerminationReason.NewConnectionAttempt, reconnectAllowed = false)

        val activeConnection = connectionFactory()
        connection = activeConnection
        val currentSessionId = ++sessionId
        terminatedSessionId = 0L

        return@withLock try {
            _state.value = ConnectionState.Connecting(host, port)
            configureTlsPin(activeConnection, host, port)
            activeConnection.connect(host, port)

            _state.value = ConnectionState.Handshaking(host, port)
            NetworkLogger.info("Handshake started session=$currentSessionId")
            val helloContext = buildHelloMessage()
            val hello = helloContext.message
            activeConnection.send(hello)
            val connected = if (identityKeyProvider != null && trustedDeviceRepository != null) {
                _state.value = ConnectionState.AuthenticatingWindows(host, port)
                val challenge = receiveAuthHandshake(activeConnection)
                val authContext = validateAuthChallenge(challenge, helloContext, host, port)
                _state.value = ConnectionState.ProvingAndroidIdentity(host, port)
                activeConnection.send(buildAuthResponse(authContext))
                val acceptedMessage = receiveAuthHandshake(activeConnection)
                validateAuthAccepted(acceptedMessage, authContext, host, port)
            } else {
                val response = receiveHelloAck(activeConnection)
                validateHelloAck(response, hello.messageId, host, port)
            }
            activeConnection.onHandshakeComplete()

            remoteDeviceId = connected.deviceId
            activeDevice = connected.toPairedDevice()
            deviceRepository?.saveDevice(activeDevice ?: connected.toPairedDevice())
            settingsRepository?.setLastSelectedDeviceId(connected.deviceId)
            reconnectJob?.cancel()
            reconnectJob = null
            sessionSecurityState = SessionSecurityState.Authenticated
            _state.value = ConnectionState.Authenticated(connected.deviceId, connected.deviceName)
            _state.value = connected.copy(reconnectAttempt = 0)

            startWriter(activeConnection, currentSessionId)
            startReader(activeConnection, currentSessionId)
            startHeartbeat(connected.deviceId, currentSessionId)
            restorePendingMessages(connected.deviceId)
            NetworkLogger.info("Handshake completed session=$currentSessionId device=${connected.deviceName}")
            connected
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                activeConnection.disconnect()
                if (connection === activeConnection) connection = null
            }
            throw error
        } catch (error: SecurityAuthException) {
            NetworkLogger.error("Authentication security error: ${error.code}", error)
            withContext(NonCancellable) {
                activeConnection.disconnect()
                if (connection === activeConnection) connection = null
            }
            _state.value = when (error.code) {
                "PAIRING_REQUIRED" -> ConnectionState.PairingRequired
                "IDENTITY_KEY_CHANGED" -> ConnectionState.IdentityChanged(error.deviceId.orEmpty())
                "TRUST_REVOKED" -> ConnectionState.TrustRevoked
                else -> ConnectionState.AuthenticationFailed(error.code)
            }
            throw ConnectionException.InvalidMessage(error.code, error)
        } catch (error: Throwable) {
            val connectionError = error.toConnectionException()
            NetworkLogger.error("Connection failed: ${connectionError::class.simpleName}", connectionError)
            withContext(NonCancellable) {
                activeConnection.disconnect()
                if (connection === activeConnection) connection = null
            }
            _state.value = ConnectionState.Failed(connectionError.message.orEmpty())
            maybeScheduleReconnect(connectionError)
            throw connectionError
        }
    }

    private suspend fun configureTlsPin(connection: DeviceConnection, host: String, port: Int) {
        val devices = deviceRepository ?: return
        val trustRepository = trustedDeviceRepository ?: return
        val pairedDevice = devices.observeDevices().first()
            .firstOrNull { it.host == host && it.port == port }
            ?: throw SecurityAuthException("PAIRING_REQUIRED", null)
        val trusted = trustRepository.getTrustedDevice(pairedDevice.id)
            ?: throw SecurityAuthException("PAIRING_REQUIRED", pairedDevice.id)
        val fingerprint = trusted.futureTlsCertificateFingerprint
            ?: throw SecurityAuthException("PAIRING_REQUIRED_TLS_PIN", pairedDevice.id)
        connection.setTlsServerSpkiFingerprint(fingerprint)
    }

    private fun startWriter(activeConnection: DeviceConnection, ownerSessionId: Long) {
        writerChannel?.close()
        writerJob?.cancel()
        val channel = Channel<ProtocolMessage>(Channel.BUFFERED)
        writerChannel = channel
        writerJob = scope.launch {
            NetworkLogger.info("Writer started session=$ownerSessionId")
            try {
                for (message in channel) {
                    activeConnection.send(message)
                    if (message.requiresAcknowledgement) {
                        outgoingMessageQueue?.markSent(message.messageId)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                terminateSession(
                    SessionTerminationReason.WriterError(error.toConnectionException()),
                    reconnectAllowed = true,
                )
            } finally {
                NetworkLogger.info("Writer stopped session=$ownerSessionId")
            }
        }
    }

    private fun startReader(activeConnection: DeviceConnection, ownerSessionId: Long) {
        readerJob?.cancel()
        readerJob = scope.launch {
            NetworkLogger.info("Reader started session=$ownerSessionId")
            try {
                while (currentCoroutineContext().isActive && ownerSessionId == sessionId) {
                    handleIncomingMessage(activeConnection.receive())
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!manualDisconnect) {
                    terminateSession(
                        SessionTerminationReason.ReaderError(error.toConnectionException()),
                        reconnectAllowed = true,
                    )
                }
            } finally {
                NetworkLogger.info("Reader stopped session=$ownerSessionId")
            }
        }
    }

    private fun startHeartbeat(recipientDeviceId: String, ownerSessionId: Long) {
        if (sessionSecurityState != SessionSecurityState.Authenticated) {
            NetworkLogger.info("Heartbeat suppressed before authentication session=$ownerSessionId")
            return
        }
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            NetworkLogger.info("Heartbeat started session=$ownerSessionId")
            try {
                while (currentCoroutineContext().isActive && ownerSessionId == sessionId) {
                    delay(heartbeatConfig.pingInterval)
                    val ping = buildPingMessage(recipientDeviceId)
                    val waitingPong = PendingPong(
                        messageId = ping.messageId,
                        sequence = pingSequence,
                        deferred = CompletableDeferred(),
                    )
                    pendingPong = waitingPong
                    writerChannel?.send(ping)
                    NetworkLogger.info("Ping sent sequence=${waitingPong.sequence} session=$ownerSessionId")

                    val pongReceived = withTimeoutOrNull(heartbeatConfig.pongTimeout) {
                        waitingPong.deferred.await()
                    } == true
                    if (pongReceived) {
                        missedPongs = 0
                    } else {
                        missedPongs += 1
                        NetworkLogger.info("Heartbeat timeout session=$ownerSessionId")
                        updateConnectedDiagnostics()
                    }
                    if (missedPongs >= heartbeatConfig.maxMissedPongs) {
                        terminateSession(SessionTerminationReason.HeartbeatTimeout, reconnectAllowed = true)
                        return@launch
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                terminateSession(
                    SessionTerminationReason.HeartbeatError(error.toConnectionException()),
                    reconnectAllowed = true,
                )
            } finally {
                NetworkLogger.info("Heartbeat stopped session=$ownerSessionId")
            }
        }
    }

    private suspend fun handleIncomingMessage(message: ProtocolMessage) {
        when (message.type) {
            ProtocolMessageType.CONNECTION_PONG.value -> handlePong(message)
            ProtocolMessageType.CONNECTION_PING.value -> handlePing(message)
            ProtocolMessageType.MESSAGE_ACK.value -> handleAck(message)
            ProtocolMessageType.CONNECTION_CLOSE.value -> handleRemoteClose(message)
            ProtocolMessageType.ERROR_PROTOCOL.value -> handleProtocolError(message)
            ProtocolMessageType.FILE_ACCEPT.value,
            ProtocolMessageType.FILE_REJECT.value,
            ProtocolMessageType.FILE_RECEIVED.value,
            ProtocolMessageType.FILE_CANCEL.value,
            ProtocolMessageType.FILE_ERROR.value -> {
                if (isFromActiveDevice(message)) {
                    fileTransferListeners.forEach { it.onFileTransferMessage(message) }
                }
            }
            ProtocolMessageType.FILE_CHUNK_RECEIVED.value,
            ProtocolMessageType.FILE_RESUME_ACCEPTED.value -> {
                if (isFromActiveDevice(message)) {
                    fileTransferListeners.forEach { it.onFileTransferMessage(message) }
                }
            }
            ProtocolMessageType.FILE_OFFER.value,
            ProtocolMessageType.FILE_CHUNK.value,
            ProtocolMessageType.FILE_COMPLETE.value -> {
                if (isFromActiveDevice(message)) {
                    fileTransferListeners.forEach { it.onFileTransferMessage(message) }
                }
            }
            ProtocolMessageType.FILE_RESUME_REQUEST.value -> {
                if (isFromActiveDevice(message)) {
                    fileTransferListeners.forEach { it.onFileTransferMessage(message) }
                }
            }
            ProtocolMessageType.CLIPBOARD_UPDATE.value,
            ProtocolMessageType.TEXT_SHARE.value,
            ProtocolMessageType.NOTIFICATION_POSTED.value,
            ProtocolMessageType.NOTIFICATION_REMOVED.value -> {
                if (isFromActiveDevice(message)) sharingListeners.forEach { it.onSharingMessage(message) }
            }
            ProtocolMessageType.FOLDER_MANIFEST.value,
            ProtocolMessageType.FOLDER_PLAN.value,
            ProtocolMessageType.FOLDER_PLAN_APPROVED.value,
            ProtocolMessageType.FOLDER_CANCEL.value,
            ProtocolMessageType.FOLDER_ERROR.value -> {
                if (isFromActiveDevice(message)) sharingListeners.forEach { it.onSharingMessage(message) }
            }
            ProtocolMessageType.CONNECTION_HELLO_ACK.value,
            ProtocolMessageType.CONNECTION_HELLO.value -> throw ConnectionException.InvalidMessage()
            else -> handleFeatureMessage(message)
        }
    }

    private suspend fun handleRemoteClose(message: ProtocolMessage) {
        if (!isFromActiveDevice(message)) return
        val payload = runCatching {
            ProtocolSerializer.decodePayload<ConnectionClosePayload>(message.payload)
        }.getOrDefault(ConnectionClosePayload())
        if (message.requiresAcknowledgement) {
            sendAck(message, "processed")
        }
        terminateSession(
            SessionTerminationReason.RemoteClose(payload.reason),
            reconnectAllowed = payload.allowReconnect,
        )
    }

    private suspend fun handleProtocolError(message: ProtocolMessage) {
        if (!isFromActiveDevice(message)) return
        val payload = ProtocolSerializer.decodePayload<ProtocolErrorPayload>(message.payload)
        val error = ConnectionException.InvalidMessage(payload.code)
        _state.value = ConnectionState.Failed(payload.message ?: payload.code)
        terminateSession(
            SessionTerminationReason.ProtocolError(error),
            reconnectAllowed = !payload.fatal,
        )
    }

    private suspend fun handlePong(message: ProtocolMessage) {
        val payload = ProtocolSerializer.decodePayload<PongPayload>(message.payload)
        val waitingPong = pendingPong ?: return
        if (
            message.correlationId == waitingPong.messageId &&
            payload.sequence == waitingPong.sequence &&
            message.senderDeviceId == remoteDeviceId
        ) {
            NetworkLogger.info("Pong received sequence=${payload.sequence}")
            waitingPong.deferred.complete(true)
            pendingPong = null
            updateConnectedDiagnostics(lastPongAtUtc = payload.receivedAtUtc, missed = 0)
        }
    }

    private suspend fun handlePing(message: ProtocolMessage) {
        if (!isFromActiveDevice(message)) return
        val payload = ProtocolSerializer.decodePayload<PingPayload>(message.payload)
        val pong = ProtocolMessage(
            protocolVersion = PROTOCOL_VERSION,
            messageId = UUID.randomUUID().toString(),
            type = ProtocolMessageType.CONNECTION_PONG.value,
            senderDeviceId = getAndroidDeviceId(),
            recipientDeviceId = message.senderDeviceId,
            timestampUtc = Instant.now().toString(),
            correlationId = message.messageId,
            payload = ProtocolSerializer.payloadToJson(
                PongPayload(sequence = payload.sequence, receivedAtUtc = Instant.now().toString())
            ),
        )
        writerChannel?.send(pong)
    }

    private suspend fun handleAck(message: ProtocolMessage) {
        if (!isFromActiveDevice(message)) return
        val correlationId = message.correlationId ?: return
        val payload = ProtocolSerializer.decodePayload<MessageAckPayload>(message.payload)
        val originalMessage = ackMutex.withLock {
            val original = pendingAcks[correlationId] ?: return
            when (payload.status) {
                "received" -> null
                "processed" -> {
                    pendingAcks.remove(correlationId)
                    ackRetryJobs.remove(correlationId)?.cancel()
                    original
                }
                "rejected", "failed" -> {
                    pendingAcks.remove(correlationId)
                    ackRetryJobs.remove(correlationId)?.cancel()
                    original
                }
                else -> throw ConnectionException.InvalidMessage("Unknown ACK status: ${payload.status}")
            }
        }

        when (payload.status) {
            "received" -> NetworkLogger.info("ACK received intermediate")
            "processed" -> {
                if (originalMessage != null) {
                    outgoingMessageQueue?.markAcknowledged(originalMessage.messageId)
                }
                NetworkLogger.info("ACK processed")
            }
            "rejected", "failed" -> {
                if (originalMessage != null) {
                    outgoingMessageQueue?.markFailed(
                        originalMessage.messageId,
                        payload.errorMessage ?: payload.errorCode ?: payload.status,
                    )
                }
                NetworkLogger.info("ACK ${payload.status}")
            }
        }
        updateConnectedDiagnostics()
    }

    private suspend fun handleFeatureMessage(message: ProtocolMessage) {
        val processedRepository = processedMessageRepository ?: return
        if (processedRepository.isProcessed(message.senderDeviceId, message.messageId)) {
            sendAck(message, "processed")
            NetworkLogger.info("Duplicate message ignored")
            return
        }
        processedRepository.markProcessed(message)
        sendAck(message, "processed")
    }

    private suspend fun sendAck(message: ProtocolMessage, status: String) {
        val ack = ProtocolMessage(
            protocolVersion = PROTOCOL_VERSION,
            messageId = UUID.randomUUID().toString(),
            type = ProtocolMessageType.MESSAGE_ACK.value,
            senderDeviceId = getAndroidDeviceId(),
            recipientDeviceId = message.senderDeviceId,
            timestampUtc = Instant.now().toString(),
            correlationId = message.messageId,
            payload = ProtocolSerializer.payloadToJson(MessageAckPayload(status = status)),
        )
        writerChannel?.send(ack)
    }

    private suspend fun restorePendingMessages(deviceId: String) {
        outgoingMessageQueue?.pendingForDevice(deviceId)
            ?.filter { it.requiresAcknowledgement }
            ?.forEach { pending ->
                if (pending.attemptCount < ackConfig.maxAttempts) {
                    val message = ProtocolSerializer.deserialize(pending.serializedMessage)
                    ackMutex.withLock {
                        pendingAcks[message.messageId] = message
                    }
                    startAckRetry(message, alreadyAttempted = pending.attemptCount)
                    writerChannel?.send(message)
                }
            }
    }

    private suspend fun maybeScheduleReconnect(error: ConnectionException) {
        if (manualDisconnect || !error.isRecoverable()) return
        if (settingsRepository?.settings?.first()?.restoreConnectionEnabled == false) return
        val device = activeDevice ?: return
        if (!device.isAutoConnectEnabled) return
        if (reconnectJob?.isActive == true) return

        reconnectJob = scope.launch {
            var attempt = 1
            try {
                while (attempt <= reconnectConfig.maxAttempts && !manualDisconnect) {
                    val monitor = networkMonitor
                    if (monitor?.networkState?.value == NetworkState.Unavailable) {
                        _state.value = ConnectionState.NetworkUnavailable
                        monitor.networkState.first { state -> state is NetworkState.Available }
                    }

                    val delayDuration = reconnectConfig.delayForAttempt(attempt)
                    _state.value = ConnectionState.Reconnecting(
                        deviceId = device.id,
                        host = device.host,
                        port = device.port,
                        attempt = attempt,
                        nextRetryMessage = "Retry in ${delayDuration.inWholeSeconds} seconds",
                    )
                    NetworkLogger.info("Reconnect scheduled attempt=$attempt")
                    delay(delayDuration)
                    try {
                        NetworkLogger.info("Reconnect attempt started attempt=$attempt")
                        connectInternal(device.host, device.port, ConnectionAttemptSource.Reconnect)
                        return@launch
                    } catch (error: CancellationException) {
                        throw error
                    } catch (ignored: ConnectionException) {
                        attempt += 1
                    }
                }
            } catch (error: CancellationException) {
                throw error
            }
        }
    }

    private suspend fun terminateSession(
        reason: SessionTerminationReason,
        reconnectAllowed: Boolean,
    ) {
        val shouldReconnect = terminationMutex.withLock {
            val currentSessionId = sessionId
            if (currentSessionId != 0L && terminatedSessionId == currentSessionId) {
                NetworkLogger.info("Duplicate cleanup suppressed session=$currentSessionId")
                return
            }
            terminatedSessionId = currentSessionId
            NetworkLogger.info("Session terminating session=$currentSessionId reason=$reason")

            val currentJob = currentCoroutineContext()[Job]
            val jobs = listOf(heartbeatJob, writerJob, readerJob)
            jobs.filterNotNull().filter { it != currentJob }.forEach { it.cancel() }
            heartbeatJob = null
            writerJob = null
            readerJob = null
            writerChannel?.close()
            writerChannel = null
            pendingPong = null
            fileTransferListeners.forEach { it.onFileTransferDisconnected() }

            ackMutex.withLock {
                ackRetryJobs.values.forEach { it.cancel() }
                ackRetryJobs.clear()
            }

            val activeConnection = connection
            connection = null
            withContext(NonCancellable) {
                if (activeConnection != null) {
                    if (reason is SessionTerminationReason.ManualDisconnect) {
                        runCatching { activeConnection.send(buildCloseMessage()) }
                    }
                    activeConnection.disconnect()
                }
            }

            missedPongs = 0
            activeDevice?.let { deviceRepository?.updateConnectionStatus(it.id, ConnectionStatus.OFFLINE) }
            val error = reason.error
            when {
                reason is SessionTerminationReason.ManualDisconnect -> _state.value = ConnectionState.Disconnected
                error != null -> _state.value = ConnectionState.Failed(error.message.orEmpty())
                reason is SessionTerminationReason.NewConnectionAttempt -> Unit
                else -> _state.value = ConnectionState.Disconnected
            }
            reconnectAllowed && !manualDisconnect && error?.isRecoverable() != false
        }

        if (shouldReconnect) {
            maybeScheduleReconnect(SessionTerminationReason.toConnectionException(reason))
        }
    }

    private suspend fun buildHelloMessage(): HelloContext {
        val fingerprint = identityKeyProvider?.getPublicKeyFingerprint()
        val clientNonce = Base64Url.encode(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val payload = ConnectionHelloPayload(
            deviceName = getAndroidDeviceName(),
            appVersion = appVersion,
            protocolVersion = PROTOCOL_VERSION,
            capabilities = SupportedCapabilities.values,
            identityFingerprint = fingerprint,
            clientNonce = clientNonce,
            authVersion = if (fingerprint != null) 1 else 0,
        )
        val message = ProtocolMessage(
            protocolVersion = PROTOCOL_VERSION,
            messageId = UUID.randomUUID().toString(),
            type = ProtocolMessageType.CONNECTION_HELLO.value,
            senderDeviceId = getAndroidDeviceId(),
            timestampUtc = Instant.now().toString(),
            requiresAcknowledgement = true,
            payload = ProtocolSerializer.payloadToJson(payload),
        )
        return HelloContext(message, fingerprint.orEmpty(), clientNonce)
    }

    private suspend fun validateAuthChallenge(
        response: ProtocolMessage,
        helloContext: HelloContext,
        host: String,
        port: Int,
    ): AuthContext {
        if (response.type == ProtocolMessageType.ERROR_PROTOCOL.value) {
            val payload = ProtocolSerializer.decodePayload<ProtocolErrorPayload>(response.payload)
            throw SecurityAuthException(payload.code, response.senderDeviceId)
        }
        if (response.type != ProtocolMessageType.AUTH_CHALLENGE.value || response.correlationId != helloContext.message.messageId) {
            throw ConnectionException.InvalidMessage()
        }
        if (Base64Url.decode(helloContext.clientNonce).size != 32) {
            throw ConnectionException.InvalidMessage()
        }
        val payload = ProtocolSerializer.decodePayload<AuthChallengePayload>(response.payload)
        if (payload.helloMessageId != helloContext.message.messageId || Base64Url.decode(payload.serverNonce).size != 32) {
            throw ConnectionException.InvalidMessage()
        }
        val trusted = trustedDeviceRepository?.getTrustedDevice(response.senderDeviceId)
            ?: throw SecurityAuthException("PAIRING_REQUIRED", response.senderDeviceId)
        if (trusted.revokedAt != null) {
            throw SecurityAuthException("TRUST_REVOKED", response.senderDeviceId)
        }
        if (trusted.identityFingerprint != payload.windowsIdentityFingerprint) {
            throw SecurityAuthException("IDENTITY_KEY_CHANGED", response.senderDeviceId)
        }
        val transcript = TranscriptBuilder.sessionAuth(
            protocolVersion = PROTOCOL_VERSION,
            androidDeviceId = getAndroidDeviceId(),
            windowsDeviceId = response.senderDeviceId,
            androidFingerprint = helloContext.androidFingerprint,
            windowsFingerprint = payload.windowsIdentityFingerprint,
            clientNonce = helloContext.clientNonce,
            serverNonce = payload.serverNonce,
            helloMessageId = helloContext.message.messageId,
        )
        val signatureOk = identityKeyProvider?.verify(
            publicKey = Base64Url.decode(trusted.identityPublicKey),
            data = transcript,
            signature = Base64Url.decode(payload.serverSignature),
        ) == true
        if (!signatureOk) {
            throw SecurityAuthException("AUTH_SIGNATURE_INVALID", response.senderDeviceId)
        }
        return AuthContext(
            windowsDeviceId = response.senderDeviceId,
            windowsDeviceName = trusted.deviceName,
            host = host,
            port = port,
            helloMessageId = helloContext.message.messageId,
            transcript = transcript,
        )
    }

    private suspend fun receiveHelloAck(activeConnection: DeviceConnection): ProtocolMessage {
        return try {
            activeConnection.receiveHandshake(HELLO_ACK_TIMEOUT_MS)
        } catch (error: TimeoutCancellationException) {
            throw ConnectionException.HandshakeTimeout(error)
        } catch (error: ConnectionException.Timeout) {
            throw ConnectionException.HandshakeTimeout(error)
        }
    }

    private suspend fun receiveAuthHandshake(activeConnection: DeviceConnection): ProtocolMessage {
        return try {
            activeConnection.receiveHandshake(AUTH_TIMEOUT_MS)
        } catch (error: TimeoutCancellationException) {
            throw ConnectionException.AuthTimeout(error)
        } catch (error: ConnectionException.Timeout) {
            throw ConnectionException.AuthTimeout(error)
        }
    }

    private suspend fun buildAuthResponse(context: AuthContext): ProtocolMessage {
        val signature = identityKeyProvider?.sign(context.transcript) ?: throw ConnectionException.InvalidMessage()
        return ProtocolMessage(
            protocolVersion = PROTOCOL_VERSION,
            messageId = UUID.randomUUID().toString(),
            type = ProtocolMessageType.AUTH_RESPONSE.value,
            senderDeviceId = getAndroidDeviceId(),
            recipientDeviceId = context.windowsDeviceId,
            timestampUtc = Instant.now().toString(),
            payload = ProtocolSerializer.payloadToJson(
                AuthResponsePayload(
                    helloMessageId = context.helloMessageId,
                    clientSignature = Base64Url.encode(signature),
                )
            ),
        )
    }

    private suspend fun validateAuthAccepted(
        message: ProtocolMessage,
        context: AuthContext,
        host: String,
        port: Int,
    ): ConnectionState.Connected {
        if (message.type == ProtocolMessageType.AUTH_REJECTED.value) {
            throw SecurityAuthException("AUTH_REJECTED", context.windowsDeviceId)
        }
        if (message.type != ProtocolMessageType.AUTH_ACCEPTED.value || message.senderDeviceId != context.windowsDeviceId) {
            throw ConnectionException.InvalidMessage()
        }
        val payload = ProtocolSerializer.decodePayload<AuthAcceptedPayload>(message.payload)
        if (payload.status != "accepted") {
            throw ConnectionException.InvalidMessage()
        }
        trustedDeviceRepository?.updateLastVerifiedAt(context.windowsDeviceId, Instant.now())
        return ConnectionState.Connected(
            deviceId = context.windowsDeviceId,
            deviceName = context.windowsDeviceName,
            host = host,
            port = port,
            acceptedProtocolVersion = PROTOCOL_VERSION,
            capabilities = SupportedCapabilities.values,
        )
    }

    private suspend fun buildCloseMessage(): ProtocolMessage {
        return ProtocolMessage(
            protocolVersion = PROTOCOL_VERSION,
            messageId = UUID.randomUUID().toString(),
            type = ProtocolMessageType.CONNECTION_CLOSE.value,
            senderDeviceId = getAndroidDeviceId(),
            timestampUtc = Instant.now().toString(),
            payload = JsonObject(emptyMap()),
        )
    }

    private suspend fun buildPingMessage(recipientDeviceId: String): ProtocolMessage {
        pingSequence += 1
        val now = Instant.now().toString()
        return ProtocolMessage(
            protocolVersion = PROTOCOL_VERSION,
            messageId = UUID.randomUUID().toString(),
            type = ProtocolMessageType.CONNECTION_PING.value,
            senderDeviceId = getAndroidDeviceId(),
            recipientDeviceId = recipientDeviceId,
            timestampUtc = now,
            payload = ProtocolSerializer.payloadToJson(PingPayload(sequence = pingSequence, sentAtUtc = now)),
        )
    }

    private fun validateHelloAck(
        response: ProtocolMessage,
        helloMessageId: String,
        host: String,
        port: Int,
    ): ConnectionState.Connected {
        if (response.type != ProtocolMessageType.CONNECTION_HELLO_ACK.value) {
            throw ConnectionException.InvalidMessage()
        }
        if (response.correlationId != helloMessageId) {
            throw ConnectionException.InvalidMessage()
        }
        if (response.protocolVersion != PROTOCOL_VERSION) {
            throw ConnectionException.UnsupportedProtocol()
        }

        val payload = ProtocolSerializer.decodePayload<ConnectionHelloAckPayload>(response.payload)
        if (payload.acceptedProtocolVersion != PROTOCOL_VERSION) {
            throw ConnectionException.UnsupportedProtocol()
        }
        if (payload.deviceName.isBlank()) {
            throw ConnectionException.InvalidMessage()
        }

        return ConnectionState.Connected(
            deviceId = response.senderDeviceId,
            deviceName = payload.deviceName,
            host = host,
            port = port,
            acceptedProtocolVersion = payload.acceptedProtocolVersion,
            capabilities = payload.capabilities,
        )
    }

    private suspend fun updateConnectedDiagnostics(lastPongAtUtc: String? = null, missed: Int = missedPongs) {
        val pendingCount = ackMutex.withLock { pendingAcks.size }
        val current = _state.value
        if (current is ConnectionState.Connected) {
            _state.value = current.copy(
                lastPongAtUtc = lastPongAtUtc ?: current.lastPongAtUtc,
                missedPongs = missed,
                pendingMessageCount = pendingCount,
            )
        }
    }

    private fun startAckRetry(message: ProtocolMessage, alreadyAttempted: Int = 1) {
        scope.launch {
            ackMutex.withLock {
                ackRetryJobs.remove(message.messageId)?.cancel()
                ackRetryJobs[message.messageId] = currentCoroutineContext()[Job] ?: return@withLock
            }
            try {
                var attempts = alreadyAttempted
                while (attempts < ackConfig.maxAttempts) {
                    delay(ackConfig.timeout)
                    val shouldRetry = ackMutex.withLock { pendingAcks.containsKey(message.messageId) }
                    if (!shouldRetry) return@launch
                    attempts += 1
                    writerChannel?.send(message)
                    outgoingMessageQueue?.markSent(message.messageId)
                }
                val timedOut = ackMutex.withLock {
                    ackRetryJobs.remove(message.messageId)
                    pendingAcks.remove(message.messageId) != null
                }
                if (timedOut) {
                    outgoingMessageQueue?.markFailed(message.messageId, "ACK timeout")
                    updateConnectedDiagnostics()
                }
            } catch (error: CancellationException) {
                throw error
            }
        }
    }

    private suspend fun getAndroidDeviceId(): String {
        return identityRepository?.getOrCreateDeviceId() ?: androidDeviceId
    }

    private suspend fun getAndroidDeviceName(): String {
        return identityRepository?.getDeviceName() ?: androidDeviceName
    }

    private fun isFromActiveDevice(message: ProtocolMessage): Boolean {
        val remote = remoteDeviceId ?: return true
        if (message.senderDeviceId != remote) return false
        val recipient = message.recipientDeviceId
        return recipient == null || recipient == androidDeviceId
    }

    private fun ConnectionState.Connected.toPairedDevice(): PairedDevice {
        return PairedDevice(
            id = deviceId,
            name = deviceName,
            host = host,
            port = port,
            protocolVersion = acceptedProtocolVersion,
            capabilities = capabilities,
            lastConnectedAt = Instant.now(),
            isAutoConnectEnabled = true,
            connectionStatus = ConnectionStatus.CONNECTED,
        )
    }

    private companion object {
        const val HELLO_ACK_TIMEOUT_MS = 8_000L
        const val AUTH_TIMEOUT_MS = 8_000L
    }
}

private enum class ConnectionAttemptSource {
    Manual,
    Reconnect,
    Startup,
}

private sealed interface SessionTerminationReason {
    val error: ConnectionException?
        get() = null

    data object ManualDisconnect : SessionTerminationReason
    data object NewConnectionAttempt : SessionTerminationReason
    data object HeartbeatTimeout : SessionTerminationReason {
        override val error: ConnectionException = ConnectionException.Timeout()
    }
    data class ReaderError(override val error: ConnectionException) : SessionTerminationReason
    data class WriterError(override val error: ConnectionException) : SessionTerminationReason
    data class HeartbeatError(override val error: ConnectionException) : SessionTerminationReason
    data class RemoteClose(val reason: String?) : SessionTerminationReason
    data class ProtocolError(override val error: ConnectionException) : SessionTerminationReason

    companion object {
        fun toConnectionException(reason: SessionTerminationReason): ConnectionException {
            return reason.error ?: ConnectionException.ConnectionClosed()
        }
    }
}

private data class PendingPong(
    val messageId: String,
    val sequence: Long,
    val deferred: CompletableDeferred<Boolean>,
)

private data class HelloContext(
    val message: ProtocolMessage,
    val androidFingerprint: String,
    val clientNonce: String,
)

private data class AuthContext(
    val windowsDeviceId: String,
    val windowsDeviceName: String,
    val host: String,
    val port: Int,
    val helloMessageId: String,
    val transcript: ByteArray,
)

private enum class SessionSecurityState {
    Unauthenticated,
    Authenticated,
}

private class SecurityAuthException(
    val code: String,
    val deviceId: String? = null,
) : Exception(code)

private fun ConnectionException.isRecoverable(): Boolean {
    return when (this) {
        is ConnectionException.InvalidFrame,
        is ConnectionException.InvalidMessage,
        is ConnectionException.UnsupportedProtocol -> false
        else -> true
    }
}
