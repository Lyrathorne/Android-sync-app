package com.example.devicesync.core.network

import com.example.devicesync.core.data.DeviceRepository
import com.example.devicesync.core.data.OutgoingMessageQueue
import com.example.devicesync.core.data.PairedDevice
import com.example.devicesync.core.data.ProcessedMessageRepository
import com.example.devicesync.core.model.ConnectionStatus
import com.example.devicesync.core.protocol.ConnectionHelloAckPayload
import com.example.devicesync.core.protocol.ConnectionHelloPayload
import com.example.devicesync.core.protocol.MessageAckPayload
import com.example.devicesync.core.protocol.PingPayload
import com.example.devicesync.core.protocol.PongPayload
import com.example.devicesync.core.protocol.ProtocolMessage
import com.example.devicesync.core.protocol.ProtocolMessageType
import com.example.devicesync.core.protocol.ProtocolSerializer
import com.example.devicesync.core.settings.AppSettingsRepository
import com.example.devicesync.core.settings.DeviceIdentityRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.UUID

class ConnectionManager(
    private val connectionFactory: () -> DeviceConnection = { TcpDeviceConnection() },
    private val androidDeviceId: String = UUID.randomUUID().toString(),
    private val androidDeviceName: String = android.os.Build.MODEL.orEmpty().ifBlank { "Android device" },
    private val appVersion: String = "0.1.0",
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val identityRepository: DeviceIdentityRepository? = null,
    private val deviceRepository: DeviceRepository? = null,
    private val outgoingMessageQueue: OutgoingMessageQueue? = null,
    private val processedMessageRepository: ProcessedMessageRepository? = null,
    private val settingsRepository: AppSettingsRepository? = null,
    private val networkMonitor: NetworkMonitor? = null,
    private val heartbeatConfig: HeartbeatConfig = HeartbeatConfig(),
    private val ackConfig: AckConfig = AckConfig(),
    private val reconnectConfig: ReconnectConfig = ReconnectConfig(),
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private var connection: DeviceConnection? = null
    private var writerChannel: Channel<ProtocolMessage>? = null
    private var writerJob: Job? = null
    private var readerJob: Job? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var manualDisconnect = false
    private var activeDevice: PairedDevice? = null
    private var remoteDeviceId: String? = null
    private var pingSequence = 0L
    private var missedPongs = 0
    private var pendingPong: PendingPong? = null
    private val pendingAcks = mutableMapOf<String, ProtocolMessage>()
    private val ackRetryJobs = mutableMapOf<String, Job>()

    suspend fun connect(host: String, port: Int): ConnectionState.Connected {
        manualDisconnect = false
        reconnectJob?.cancel()
        stopSession(updateState = false, sendClose = false)

        val activeConnection = connectionFactory()
        connection = activeConnection

        return try {
            _state.value = ConnectionState.Connecting(host, port)
            activeConnection.connect(host, port)

            _state.value = ConnectionState.Handshaking(host, port)
            val hello = buildHelloMessage()
            activeConnection.send(hello)
            val response = activeConnection.receive()
            val connected = validateHelloAck(response, hello.messageId, host, port)

            remoteDeviceId = connected.deviceId
            activeDevice = connected.toPairedDevice()
            deviceRepository?.saveDevice(activeDevice ?: connected.toPairedDevice())
            settingsRepository?.setLastSelectedDeviceId(connected.deviceId)
            _state.value = connected

            startWriter(activeConnection)
            startReader(activeConnection)
            startHeartbeat(connected.deviceId)
            restorePendingMessages(connected.deviceId)
            NetworkLogger.info("Handshake completed with ${connected.deviceName}")
            connected
        } catch (error: Throwable) {
            val connectionError = error.toConnectionException()
            NetworkLogger.error("Connection failed: ${connectionError::class.simpleName}", connectionError)
            runCatching { activeConnection.disconnect() }
            if (connection === activeConnection) connection = null
            _state.value = ConnectionState.Failed(connectionError.message.orEmpty())
            maybeScheduleReconnect(connectionError)
            throw connectionError
        }
    }

    suspend fun disconnect() {
        manualDisconnect = true
        reconnectJob?.cancel()
        NetworkLogger.info("Manual disconnect")
        stopSession(updateState = true, sendClose = true)
    }

    suspend fun sendQueued(message: ProtocolMessage) {
        if (message.requiresAcknowledgement) {
            outgoingMessageQueue?.enqueue(message)
            pendingAcks[message.messageId] = message
            startAckRetry(message)
        }
        writerChannel?.send(message)
    }

    private fun startWriter(activeConnection: DeviceConnection) {
        writerChannel?.close()
        writerJob?.cancel()
        val channel = Channel<ProtocolMessage>(Channel.BUFFERED)
        writerChannel = channel
        writerJob = scope.launch {
            for (message in channel) {
                activeConnection.send(message)
                if (message.requiresAcknowledgement) {
                    outgoingMessageQueue?.markSent(message.messageId)
                }
            }
        }
    }

    private fun startReader(activeConnection: DeviceConnection) {
        readerJob?.cancel()
        readerJob = scope.launch {
            try {
                while (true) {
                    handleIncomingMessage(activeConnection.receive())
                }
            } catch (error: Throwable) {
                if (!manualDisconnect) {
                    handleConnectionLost(error.toConnectionException())
                }
            }
        }
    }

    private fun startHeartbeat(recipientDeviceId: String) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            NetworkLogger.info("Heartbeat started")
            while (true) {
                delay(heartbeatConfig.pingInterval)
                val ping = buildPingMessage(recipientDeviceId)
                val waitingPong = PendingPong(
                    messageId = ping.messageId,
                    sequence = pingSequence,
                    deferred = CompletableDeferred(),
                )
                pendingPong = waitingPong
                writerChannel?.send(ping)
                NetworkLogger.info("Ping sent")

                val pongReceived = withTimeoutOrNull(heartbeatConfig.pongTimeout) {
                    waitingPong.deferred.await()
                } == true
                if (pongReceived) {
                    missedPongs = 0
                } else {
                    missedPongs += 1
                    NetworkLogger.info("Heartbeat timeout")
                    updateConnectedDiagnostics()
                }
                if (missedPongs >= heartbeatConfig.maxMissedPongs) {
                    handleConnectionLost(ConnectionException.Timeout())
                    return@launch
                }
            }
        }
    }

    private suspend fun handleIncomingMessage(message: ProtocolMessage) {
        when (message.type) {
            ProtocolMessageType.CONNECTION_PONG.value -> handlePong(message)
            ProtocolMessageType.CONNECTION_PING.value -> handlePing(message)
            ProtocolMessageType.MESSAGE_ACK.value -> handleAck(message)
            else -> handleFeatureMessage(message)
        }
    }

    private suspend fun handlePong(message: ProtocolMessage) {
        val payload = ProtocolSerializer.decodePayload<PongPayload>(message.payload)
        val waitingPong = pendingPong ?: return
        if (
            message.correlationId == waitingPong.messageId &&
            payload.sequence == waitingPong.sequence &&
            message.senderDeviceId == remoteDeviceId
        ) {
            NetworkLogger.info("Pong received")
            waitingPong.deferred.complete(true)
            pendingPong = null
            updateConnectedDiagnostics(lastPongAtUtc = payload.receivedAtUtc, missed = 0)
        }
    }

    private suspend fun handlePing(message: ProtocolMessage) {
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
        val correlationId = message.correlationId ?: return
        ProtocolSerializer.decodePayload<MessageAckPayload>(message.payload)
        val originalMessage = pendingAcks.remove(correlationId) ?: return
        ackRetryJobs.remove(correlationId)?.cancel()
        outgoingMessageQueue?.markAcknowledged(originalMessage.messageId)
        NetworkLogger.info("ACK received")
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
                    pendingAcks[message.messageId] = message
                    startAckRetry(message, alreadyAttempted = pending.attemptCount)
                    writerChannel?.send(message)
                }
            }
    }

    private suspend fun handleConnectionLost(error: ConnectionException) {
        NetworkLogger.info("Connection lost")
        stopSession(updateState = false, sendClose = false)
        activeDevice?.let { deviceRepository?.updateConnectionStatus(it.id, ConnectionStatus.OFFLINE) }
        _state.value = ConnectionState.Failed(error.message.orEmpty())
        maybeScheduleReconnect(error)
    }

    private suspend fun maybeScheduleReconnect(error: ConnectionException) {
        if (manualDisconnect || !error.isRecoverable()) return
        val device = activeDevice ?: return
        if (!device.isAutoConnectEnabled) return
        if (reconnectJob?.isActive == true) return

        reconnectJob = scope.launch {
            var attempt = 1
            while (attempt <= reconnectConfig.maxAttempts && !manualDisconnect) {
                val networkState = networkMonitor?.networkState?.value
                if (networkState == NetworkState.Unavailable) {
                    _state.value = ConnectionState.NetworkUnavailable
                    networkMonitor.networkState.first { it == NetworkState.Available }
                }

                val delayDuration = reconnectConfig.delayForAttempt(attempt)
                _state.value = ConnectionState.Reconnecting(
                    deviceId = device.id,
                    host = device.host,
                    port = device.port,
                    attempt = attempt,
                    nextRetryMessage = "Повторная попытка через ${delayDuration.inWholeSeconds} секунд",
                )
                NetworkLogger.info("Reconnect scheduled")
                delay(delayDuration)
                try {
                    NetworkLogger.info("Reconnect attempt started")
                    connect(device.host, device.port)
                    return@launch
                } catch (ignored: ConnectionException) {
                    attempt += 1
                }
            }
        }
    }

    private suspend fun stopSession(updateState: Boolean, sendClose: Boolean) {
        heartbeatJob?.cancel()
        readerJob?.cancel()
        writerJob?.cancel()
        writerChannel?.close()
        heartbeatJob = null
        readerJob = null
        writerJob = null
        writerChannel = null
        val activeConnection = connection
        connection = null
        if (activeConnection != null) {
            if (sendClose) runCatching { activeConnection.send(buildCloseMessage()) }
            activeConnection.disconnect()
        }
        pendingPong = null
        ackRetryJobs.values.forEach { it.cancel() }
        ackRetryJobs.clear()
        missedPongs = 0
        if (updateState) {
            activeDevice?.let { deviceRepository?.updateConnectionStatus(it.id, ConnectionStatus.OFFLINE) }
            _state.value = ConnectionState.Disconnected
        }
    }

    private suspend fun buildHelloMessage(): ProtocolMessage {
        val payload = ConnectionHelloPayload(
            deviceName = getAndroidDeviceName(),
            appVersion = appVersion,
            protocolVersion = PROTOCOL_VERSION,
            capabilities = listOf("text"),
        )
        return ProtocolMessage(
            protocolVersion = PROTOCOL_VERSION,
            messageId = UUID.randomUUID().toString(),
            type = ProtocolMessageType.CONNECTION_HELLO.value,
            senderDeviceId = getAndroidDeviceId(),
            timestampUtc = Instant.now().toString(),
            requiresAcknowledgement = true,
            payload = ProtocolSerializer.payloadToJson(payload),
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

    private fun updateConnectedDiagnostics(lastPongAtUtc: String? = null, missed: Int = missedPongs) {
        val current = _state.value
        if (current is ConnectionState.Connected) {
            _state.value = current.copy(
                lastPongAtUtc = lastPongAtUtc ?: current.lastPongAtUtc,
                missedPongs = missed,
                pendingMessageCount = pendingAcks.size,
            )
        }
    }

    private fun startAckRetry(message: ProtocolMessage, alreadyAttempted: Int = 0) {
        ackRetryJobs[message.messageId]?.cancel()
        ackRetryJobs[message.messageId] = scope.launch {
            var attempts = alreadyAttempted
            while (pendingAcks.containsKey(message.messageId) && attempts < ackConfig.maxAttempts) {
                delay(ackConfig.timeout)
                if (!pendingAcks.containsKey(message.messageId)) return@launch
                attempts += 1
                writerChannel?.send(message)
                outgoingMessageQueue?.markSent(message.messageId)
            }
            if (pendingAcks.remove(message.messageId) != null) {
                outgoingMessageQueue?.markFailed(message.messageId, "ACK timeout")
            }
        }
    }

    private suspend fun getAndroidDeviceId(): String {
        return identityRepository?.getOrCreateDeviceId() ?: androidDeviceId
    }

    private suspend fun getAndroidDeviceName(): String {
        return identityRepository?.getDeviceName() ?: androidDeviceName
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
}

private data class PendingPong(
    val messageId: String,
    val sequence: Long,
    val deferred: CompletableDeferred<Boolean>,
)

private fun ConnectionException.isRecoverable(): Boolean {
    return when (this) {
        is ConnectionException.InvalidFrame,
        is ConnectionException.InvalidMessage,
        is ConnectionException.UnsupportedProtocol -> false
        else -> true
    }
}
