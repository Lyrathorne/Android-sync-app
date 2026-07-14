package com.example.devicesync.core.security

import com.example.devicesync.BuildConfig
import com.example.devicesync.core.discovery.DEVICESYNC_PROTOCOL_VERSION
import com.example.devicesync.core.discovery.DiscoveryAddressSelector
import com.example.devicesync.core.network.ConnectionException
import com.example.devicesync.core.network.DeviceConnection
import com.example.devicesync.core.network.NetworkLogger
import com.example.devicesync.core.network.TcpDeviceConnection
import com.example.devicesync.core.protocol.PairingAcceptedPayload
import com.example.devicesync.core.protocol.PairingChallengePayload
import com.example.devicesync.core.protocol.PairingCompleteAckPayload
import com.example.devicesync.core.protocol.PairingConfirmPayload
import com.example.devicesync.core.protocol.PairingRequestPayload
import com.example.devicesync.core.protocol.ProtocolMessage
import com.example.devicesync.core.protocol.ProtocolMessageType
import com.example.devicesync.core.protocol.ProtocolSerializer
import com.example.devicesync.core.settings.DeviceIdentityRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.util.UUID

sealed interface PairingState {
    data object Idle : PairingState
    data class Connecting(val windowsDeviceName: String, val target: String, val targets: List<String>) : PairingState
    data class SendingRequest(val target: String, val targets: List<String>) : PairingState
    data class WaitingForChallenge(val target: String, val targets: List<String>) : PairingState
    data class VerifyingChallenge(val target: String, val targets: List<String>) : PairingState
    data class WaitingForUserConfirmation(
        val windowsDeviceId: String,
        val windowsDeviceName: String,
        val windowsFingerprint: String,
        val verificationCode: String,
        val expiresAtUtc: Instant,
    ) : PairingState
    data object WaitingForWindowsConfirmation : PairingState
    data object SavingTrust : PairingState
    data class Completed(
        val trustedDeviceId: String,
        val hostAddresses: List<String>,
        val port: Int,
    ) : PairingState
    data class Failed(val userMessage: String, val technicalCode: String? = null) : PairingState
    data object Cancelled : PairingState
    data object Expired : PairingState
}

interface PairingCoordinator {
    val state: StateFlow<PairingState>
    suspend fun startPairing(payload: PairingQrPayload)
    suspend fun confirmVerificationCode()
    suspend fun rejectVerificationCode()
    suspend fun cancelPairing()
}

class DefaultPairingCoordinator(
    private val identityRepository: DeviceIdentityRepository,
    private val identityKeyProvider: DeviceIdentityKeyProvider,
    private val trustedDeviceRepository: TrustedDeviceRepository,
    private val connectionFactory: () -> DeviceConnection = { TcpDeviceConnection() },
    private val pairingProtocol: PairingProtocol = PairingProtocol(),
    private val addressSelector: DiscoveryAddressSelector = DiscoveryAddressSelector(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Instant = { Instant.now() },
) : PairingCoordinator {
    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    override val state: StateFlow<PairingState> = _state.asStateFlow()

    private var active: ActivePairing? = null

    override suspend fun startPairing(payload: PairingQrPayload) {
        terminateActive()
        NetworkLogger.info("QR_PARSED")
        if (!Instant.parse(payload.expiresAtUtc).isAfter(now())) {
            _state.value = PairingState.Expired
            NetworkLogger.info("PAIRING_FAILED code=PAIRING_SESSION_EXPIRED")
            return
        }

        val hosts = addressSelector.orderedUsableAddresses(payload.hostAddresses)
        val targets = hosts.map { "$it:${payload.port}" }
        if (targets.isNotEmpty()) {
            NetworkLogger.info("PAIRING_TARGETS ${targets.joinToString()}")
        }
        if (hosts.isEmpty()) {
            failWithoutActive("QR-код не содержит доступный адрес компьютера.", "PAIRING_PROTOCOL_ERROR")
            return
        }

        val connection = connectPairingSocket(payload, hosts, targets) ?: return
        val target = active?.target ?: "${hosts.first()}:${payload.port}"
        try {
            val androidDeviceId = identityRepository.getOrCreateDeviceId()
            val androidDeviceName = identityRepository.getDeviceName()
            val androidPublicKey = identityKeyProvider.getOrCreatePublicKey()
            val requestDraft = pairingProtocol.createRequestProof(payload, androidDeviceId, androidPublicKey)
            active = active?.copy(
                androidDeviceId = androidDeviceId,
                androidDeviceName = androidDeviceName,
                androidPublicKey = Base64Url.encode(androidPublicKey),
                androidFingerprint = requestDraft.androidFingerprint,
                androidNonce = requestDraft.androidNonce,
            )

            _state.value = PairingState.SendingRequest(target, targets)
            val requestMessage = ProtocolMessage(
                protocolVersion = DEVICESYNC_PROTOCOL_VERSION,
                messageId = UUID.randomUUID().toString(),
                type = ProtocolMessageType.PAIRING_REQUEST.value,
                senderDeviceId = androidDeviceId,
                recipientDeviceId = payload.windowsDeviceId,
                timestampUtc = now().toString(),
                payload = ProtocolSerializer.payloadToJson(
                    PairingRequestPayload(
                        sessionId = payload.sessionId,
                        androidDeviceId = androidDeviceId,
                        androidDeviceName = androidDeviceName,
                        androidAppVersion = BuildConfig.VERSION_NAME,
                        androidIdentityPublicKey = Base64Url.encode(androidPublicKey),
                        androidIdentityFingerprint = requestDraft.androidFingerprint,
                        androidNonce = requestDraft.androidNonce,
                        proof = requestDraft.proof,
                    )
                ),
            )
            check(requestMessage.type == ProtocolMessageType.PAIRING_REQUEST.value) {
                "Pairing connection first outgoing type must be pairing.request"
            }
            NetworkLogger.info("PAIRING_REQUEST_BUILT")
            withTimeout(PAIRING_REQUEST_SEND_TIMEOUT_MS) {
                connection.send(requestMessage)
            }
            NetworkLogger.info("PAIRING_REQUEST_SENT")

            _state.value = PairingState.WaitingForChallenge(target, targets)
            NetworkLogger.info("WAITING_FOR_CHALLENGE")
            val challengeMessage = receivePairingHandshake(connection)
            NetworkLogger.info("PAIRING_MESSAGE_RECEIVED ${challengeMessage.type}")
            when (challengeMessage.type) {
                ProtocolMessageType.PAIRING_CHALLENGE.value -> Unit
                ProtocolMessageType.PAIRING_REJECTED.value -> {
                    failAndClose("Компьютер отклонил привязку.", "PAIRING_PROTOCOL_ERROR")
                    return
                }
                ProtocolMessageType.PAIRING_CANCEL.value -> {
                    failAndClose("Привязка отменена на компьютере.", "PAIRING_PROTOCOL_ERROR")
                    return
                }
                else -> {
                    failAndClose("Компьютер прислал неожиданный ответ: ${challengeMessage.type}.", "PAIRING_PROTOCOL_ERROR")
                    return
                }
            }

            _state.value = PairingState.VerifyingChallenge(target, targets)
            val challenge = ProtocolSerializer.decodePayload<PairingChallengePayload>(challengeMessage.payload)
            NetworkLogger.info(
                if (challenge.sessionId == payload.sessionId) {
                    "Pairing session ID match"
                } else {
                    "Pairing session ID mismatch"
                }
            )
            if (!verifyChallenge(payload, requestDraft, challenge)) {
                failAndClose("Проверка безопасности привязки не прошла.", "PAIRING_PROTOCOL_ERROR")
                return
            }

            val verificationCode = SecurityEncoding.verificationCode(
                payload.sessionId,
                payload.windowsDeviceId,
                androidDeviceId,
                payload.windowsIdentityFingerprint,
                requestDraft.androidFingerprint,
                requestDraft.androidNonce,
                challenge.windowsNonce,
            )
            active = active?.copy(windowsNonce = challenge.windowsNonce, verificationCode = verificationCode)
            _state.value = PairingState.WaitingForUserConfirmation(
                windowsDeviceId = payload.windowsDeviceId,
                windowsDeviceName = payload.windowsDeviceName,
                windowsFingerprint = payload.windowsIdentityFingerprint,
                verificationCode = verificationCode,
                expiresAtUtc = Instant.parse(payload.expiresAtUtc),
            )
        } catch (error: Throwable) {
            val code = error.toPairingFailureCode()
            failAndClose(error.message ?: "Не удалось выполнить привязку.", code)
        }
    }

    private suspend fun connectPairingSocket(payload: PairingQrPayload, hosts: List<String>, targets: List<String>): DeviceConnection? {
        var lastError: Throwable? = null
        for (host in hosts) {
            val connection = connectionFactory()
            val target = "$host:${payload.port}"
            active = ActivePairing(payload = payload, connection = connection, target = target)
            try {
                _state.value = PairingState.Connecting(payload.windowsDeviceName, target, targets)
                NetworkLogger.info("PAIRING_CONNECT_STARTED $target")
                connection.setTlsServerSpkiFingerprint(payload.tlsServerSpkiFingerprint)
                connection.connect(host, payload.port)
                connection.setReadTimeout(PAIRING_CHALLENGE_TIMEOUT_MS.toInt())
                NetworkLogger.info("PAIRING_TCP_CONNECTED")
                return connection
            } catch (error: Throwable) {
                lastError = error
                connection.disconnect()
                active = null
            }
        }

        val lastTarget = "${hosts.last()}:${payload.port}"
        failWithoutActive(
            lastError?.message ?: "Не удалось подключиться к компьютеру.\n\nАдрес: $lastTarget\nПроверьте Windows Firewall и подключение к одной сети.",
            lastError.toTcpConnectFailureCode(),
        )
        return null
    }

    override suspend fun confirmVerificationCode() {
        val pairing = active ?: return
        val androidDeviceId = pairing.androidDeviceId ?: return
        val androidNonce = pairing.androidNonce ?: return
        val androidFingerprint = pairing.androidFingerprint ?: return
        val windowsNonce = pairing.windowsNonce ?: return
        val verificationCode = pairing.verificationCode ?: return
        if (!Instant.parse(pairing.payload.expiresAtUtc).isAfter(now())) {
            failAndClose("QR-код устарел. Создайте новый код на компьютере.", "PAIRING_SESSION_EXPIRED")
            return
        }

        try {
            completePairingConfirmation(
                pairing = pairing,
                androidDeviceId = androidDeviceId,
                androidNonce = androidNonce,
                androidFingerprint = androidFingerprint,
                windowsNonce = windowsNonce,
                verificationCode = verificationCode,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val code = error.toPairingFailureCode()
            failAndClose(error.message ?: "Не удалось завершить привязку.", code)
        }
    }

    private suspend fun completePairingConfirmation(
        pairing: ActivePairing,
        androidDeviceId: String,
        androidNonce: String,
        androidFingerprint: String,
        windowsNonce: String,
        verificationCode: String,
    ) {
        val transcript = TranscriptBuilder.pairingConfirmation(
            sessionId = pairing.payload.sessionId,
            windowsDeviceId = pairing.payload.windowsDeviceId,
            androidDeviceId = androidDeviceId,
            windowsFingerprint = pairing.payload.windowsIdentityFingerprint,
            androidFingerprint = androidFingerprint,
            androidNonce = androidNonce,
            windowsNonce = windowsNonce,
            verificationCode = verificationCode,
        )
        val signature = Base64Url.encode(identityKeyProvider.sign(transcript))
        _state.value = PairingState.WaitingForWindowsConfirmation
        pairing.connection.send(
            ProtocolMessage(
                protocolVersion = DEVICESYNC_PROTOCOL_VERSION,
                messageId = UUID.randomUUID().toString(),
                type = ProtocolMessageType.PAIRING_CONFIRM.value,
                senderDeviceId = androidDeviceId,
                recipientDeviceId = pairing.payload.windowsDeviceId,
                timestampUtc = now().toString(),
                payload = ProtocolSerializer.payloadToJson(
                    PairingConfirmPayload(
                        sessionId = pairing.payload.sessionId,
                        confirmed = true,
                        androidSignature = signature,
                    )
                ),
            )
        )
        val acceptedMessage = receivePairingHandshake(
            pairing.connection,
            PAIRING_ACCEPTED_TIMEOUT_MS,
            "PAIRING_ACCEPTED_TIMEOUT",
        )
        if (acceptedMessage.type != ProtocolMessageType.PAIRING_ACCEPTED.value) {
            failAndClose("Компьютер не завершил привязку.", "PAIRING_PROTOCOL_ERROR")
            return
        }
        val accepted = ProtocolSerializer.decodePayload<PairingAcceptedPayload>(acceptedMessage.payload)
        if (!verifyAccepted(pairing, accepted)) {
            failAndClose("Подпись компьютера не прошла проверку.", "PAIRING_PROTOCOL_ERROR")
            return
        }

        _state.value = PairingState.SavingTrust
        trustedDeviceRepository.saveTrustedDevice(
            TrustedDevice(
                deviceId = pairing.payload.windowsDeviceId,
                deviceName = pairing.payload.windowsDeviceName,
                identityPublicKey = pairing.payload.windowsIdentityPublicKey,
                identityFingerprint = pairing.payload.windowsIdentityFingerprint,
                futureTlsCertificateFingerprint = pairing.payload.tlsServerSpkiFingerprint,
                pairedAt = Instant.parse(accepted.pairedAtUtc),
                lastVerifiedAt = null,
                revokedAt = null,
            )
        )
        pairing.connection.send(
            ProtocolMessage(
                protocolVersion = DEVICESYNC_PROTOCOL_VERSION,
                messageId = UUID.randomUUID().toString(),
                type = ProtocolMessageType.PAIRING_COMPLETE_ACK.value,
                senderDeviceId = androidDeviceId,
                recipientDeviceId = pairing.payload.windowsDeviceId,
                timestampUtc = now().toString(),
                payload = ProtocolSerializer.payloadToJson(
                    PairingCompleteAckPayload(
                        sessionId = pairing.payload.sessionId,
                        status = "stored",
                    )
                ),
            )
        )
        terminateActive()
        _state.value = PairingState.Completed(
            trustedDeviceId = pairing.payload.windowsDeviceId,
            hostAddresses = pairing.payload.hostAddresses,
            port = pairing.payload.port,
        )
    }

    override suspend fun rejectVerificationCode() {
        sendCancel()
        terminateActive()
        _state.value = PairingState.Cancelled
    }

    override suspend fun cancelPairing() {
        sendCancel()
        terminateActive()
        _state.value = PairingState.Cancelled
    }

    private fun verifyChallenge(
        payload: PairingQrPayload,
        requestDraft: PairingRequestDraft,
        challenge: PairingChallengePayload,
    ): Boolean {
        if (challenge.sessionId != payload.sessionId) return false
        if (challenge.windowsDeviceId != payload.windowsDeviceId) return false
        if (challenge.windowsIdentityPublicKey != payload.windowsIdentityPublicKey) return false
        if (challenge.windowsIdentityFingerprint != payload.windowsIdentityFingerprint) return false
        if (challenge.androidNonce != requestDraft.androidNonce) return false
        if (Base64Url.decode(challenge.windowsNonce).size != 32) return false
        val expectedFingerprint = SecurityEncoding.fingerprint(Base64Url.decode(challenge.windowsIdentityPublicKey))
        if (expectedFingerprint != challenge.windowsIdentityFingerprint) return false
        val transcript = TranscriptBuilder.pairingChallenge(
            sessionId = payload.sessionId,
            windowsDeviceId = payload.windowsDeviceId,
            androidDeviceId = active?.androidDeviceId.orEmpty(),
            windowsFingerprint = payload.windowsIdentityFingerprint,
            androidFingerprint = requestDraft.androidFingerprint,
            androidNonce = requestDraft.androidNonce,
            windowsNonce = challenge.windowsNonce,
        )
        val expectedProof = SecurityEncoding.hmacSha256(Base64Url.decode(payload.pairingSecret), transcript)
        return SecurityEncoding.fixedTimeEquals(expectedProof, Base64Url.decode(challenge.proof))
    }

    private suspend fun verifyAccepted(pairing: ActivePairing, accepted: PairingAcceptedPayload): Boolean {
        val androidDeviceId = pairing.androidDeviceId ?: return false
        val androidFingerprint = pairing.androidFingerprint ?: return false
        val androidNonce = pairing.androidNonce ?: return false
        val windowsNonce = pairing.windowsNonce ?: return false
        val verificationCode = pairing.verificationCode ?: return false
        if (accepted.sessionId != pairing.payload.sessionId) return false
        if (accepted.permissions != listOf("basic_connection", "heartbeat")) return false
        if (!Instant.parse(pairing.payload.expiresAtUtc).isAfter(now())) return false
        val transcript = TranscriptBuilder.pairingAccepted(
            sessionId = pairing.payload.sessionId,
            windowsDeviceId = pairing.payload.windowsDeviceId,
            androidDeviceId = androidDeviceId,
            windowsFingerprint = pairing.payload.windowsIdentityFingerprint,
            androidFingerprint = androidFingerprint,
            androidNonce = androidNonce,
            windowsNonce = windowsNonce,
            verificationCode = verificationCode,
            pairedAtUtc = accepted.pairedAtUtc,
            permissions = accepted.permissions,
        )
        return identityKeyProvider.verify(
            publicKey = Base64Url.decode(pairing.payload.windowsIdentityPublicKey),
            data = transcript,
            signature = Base64Url.decode(accepted.windowsSignature),
        )
    }

    private suspend fun sendCancel() {
        val pairing = active ?: return
        val androidDeviceId = pairing.androidDeviceId ?: return
        runCatching {
            pairing.connection.send(
                ProtocolMessage(
                    protocolVersion = DEVICESYNC_PROTOCOL_VERSION,
                    messageId = UUID.randomUUID().toString(),
                    type = ProtocolMessageType.PAIRING_CANCEL.value,
                    senderDeviceId = androidDeviceId,
                    recipientDeviceId = pairing.payload.windowsDeviceId,
                    timestampUtc = now().toString(),
                    payload = ProtocolSerializer.payloadToJson(mapOf("sessionId" to pairing.payload.sessionId)),
                )
            )
        }
    }

    private suspend fun failAndClose(message: String, technicalCode: String) {
        terminateActive()
        failWithoutActive(message, technicalCode)
    }

    private fun failWithoutActive(message: String, technicalCode: String) {
        NetworkLogger.info("PAIRING_FAILED code=$technicalCode")
        _state.value = PairingState.Failed(message, technicalCode)
    }

    private suspend fun receivePairingHandshake(
        connection: DeviceConnection,
        timeoutMs: Long = PAIRING_CHALLENGE_TIMEOUT_MS,
        timeoutCode: String = "PAIRING_CHALLENGE_TIMEOUT",
    ): ProtocolMessage {
        return try {
            connection.receiveHandshake(timeoutMs)
        } catch (error: TimeoutCancellationException) {
            throw PairingFlowException("Превышено время ожидания ответа компьютера.", timeoutCode, error)
        } catch (error: ConnectionException.Timeout) {
            throw PairingFlowException("Превышено время ожидания ответа компьютера.", timeoutCode, error)
        } catch (error: ConnectionException.ConnectionClosed) {
            throw PairingFlowException("Компьютер закрыл соединение до ответа.", "PAIRING_CONNECTION_CLOSED", error)
        }
    }

    private suspend fun terminateActive() {
        val pairing = active
        active = null
        pairing?.connection?.disconnect()
    }

    @Suppress("unused")
    private fun keepRepositoryReferenced() {
        scope.launch { trustedDeviceRepository.observeTrustedDevices().collect {} }
    }

    private fun Throwable.toPairingFailureCode(): String {
        return when (this) {
            is PairingFlowException -> code
            is TimeoutCancellationException -> "PAIRING_REQUEST_SEND_TIMEOUT"
            is ConnectionException.TcpConnectTimeout -> "TCP_CONNECT_TIMEOUT"
            is ConnectionException.ConnectionRefused -> "TCP_CONNECTION_REFUSED"
            is ConnectionException.NoRouteToHost -> "NO_ROUTE_TO_HOST"
            is ConnectionException.ConnectionClosed -> "PAIRING_CONNECTION_CLOSED"
            is ConnectionException.Timeout -> "PAIRING_CHALLENGE_TIMEOUT"
            else -> "PAIRING_PROTOCOL_ERROR"
        }
    }

    private fun Throwable?.toTcpConnectFailureCode(): String {
        return when (this) {
            is ConnectionException.TcpConnectTimeout -> "TCP_CONNECT_TIMEOUT"
            is ConnectionException.ConnectionRefused -> "TCP_CONNECTION_REFUSED"
            is ConnectionException.NoRouteToHost -> "NO_ROUTE_TO_HOST"
            else -> "TCP_CONNECT_FAILED"
        }
    }

    private data class ActivePairing(
        val payload: PairingQrPayload,
        val connection: DeviceConnection,
        val target: String,
        val androidDeviceId: String? = null,
        val androidDeviceName: String? = null,
        val androidPublicKey: String? = null,
        val androidFingerprint: String? = null,
        val androidNonce: String? = null,
        val windowsNonce: String? = null,
        val verificationCode: String? = null,
    )

    private companion object {
        const val PAIRING_REQUEST_SEND_TIMEOUT_MS = 5_000L
        const val PAIRING_CHALLENGE_TIMEOUT_MS = 10_000L
        const val PAIRING_ACCEPTED_TIMEOUT_MS = 60_000L
    }
}

private class PairingFlowException(
    message: String,
    val code: String,
    cause: Throwable? = null,
) : Exception(message, cause)
