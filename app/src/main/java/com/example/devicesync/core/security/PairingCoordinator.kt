package com.example.devicesync.core.security

import com.example.devicesync.BuildConfig
import com.example.devicesync.core.discovery.DiscoveryAddressSelector
import com.example.devicesync.core.discovery.DEVICESYNC_PROTOCOL_VERSION
import com.example.devicesync.core.network.ConnectionException
import com.example.devicesync.core.network.DeviceConnection
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

sealed interface PairingState {
    data object Idle : PairingState
    data class Connecting(val windowsDeviceName: String) : PairingState
    data object SendingRequest : PairingState
    data object WaitingForChallenge : PairingState
    data object VerifyingChallenge : PairingState
    data class WaitingForUserConfirmation(
        val windowsDeviceId: String,
        val windowsDeviceName: String,
        val windowsFingerprint: String,
        val verificationCode: String,
        val expiresAtUtc: Instant,
    ) : PairingState
    data object WaitingForWindowsConfirmation : PairingState
    data object SavingTrust : PairingState
    data class Completed(val trustedDeviceId: String) : PairingState
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
        if (!Instant.parse(payload.expiresAtUtc).isAfter(now())) {
            _state.value = PairingState.Expired
            return
        }

        val hosts = addressSelector.orderedUsableAddresses(payload.hostAddresses)
        if (hosts.isEmpty()) {
            _state.value = PairingState.Failed("QR-код не содержит адрес компьютера.", "QR_NO_HOST")
            return
        }

        val connection = connectPairingSocket(payload, hosts) ?: return
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

            _state.value = PairingState.SendingRequest
            connection.send(
                ProtocolMessage(
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
            )

            _state.value = PairingState.WaitingForChallenge
            val challengeMessage = receivePairingHandshake(connection)
            if (challengeMessage.type != ProtocolMessageType.PAIRING_CHALLENGE.value) {
                failAndClose("Компьютер отклонил привязку.", "PAIRING_ORDER")
                return
            }

            _state.value = PairingState.VerifyingChallenge
            val challenge = ProtocolSerializer.decodePayload<PairingChallengePayload>(challengeMessage.payload)
            if (!verifyChallenge(payload, requestDraft, challenge)) {
                failAndClose("Проверка безопасности привязки не прошла.", "CHALLENGE_INVALID")
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
            failAndClose(error.message ?: "Не удалось выполнить привязку.", "PAIRING_FAILED")
        }
    }

    private suspend fun connectPairingSocket(payload: PairingQrPayload, hosts: List<String>): DeviceConnection? {
        var lastError: Throwable? = null
        for (host in hosts) {
            val connection = connectionFactory()
            active = ActivePairing(payload = payload, connection = connection)
            try {
                _state.value = PairingState.Connecting(payload.windowsDeviceName)
                connection.connect(host, payload.port)
                return connection
            } catch (error: Throwable) {
                lastError = error
                connection.disconnect()
                active = null
            }
        }

        _state.value = PairingState.Failed(
            lastError?.message ?: "РќРµ СѓРґР°Р»РѕСЃСЊ РІС‹РїРѕР»РЅРёС‚СЊ РїСЂРёРІСЏР·РєСѓ.",
            "PAIRING_FAILED",
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
            failAndClose("QR-код устарел. Создайте новый код на компьютере.", "PAIRING_EXPIRED")
            return
        }

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
        val acceptedMessage = receivePairingHandshake(pairing.connection)
        if (acceptedMessage.type != ProtocolMessageType.PAIRING_ACCEPTED.value) {
            failAndClose("Компьютер не завершил привязку.", "ACCEPTED_EXPECTED")
            return
        }
        val accepted = ProtocolSerializer.decodePayload<PairingAcceptedPayload>(acceptedMessage.payload)
        if (!verifyAccepted(pairing, accepted)) {
            failAndClose("Подпись компьютера не прошла проверку.", "ACCEPTED_INVALID")
            return
        }

        _state.value = PairingState.SavingTrust
        trustedDeviceRepository.saveTrustedDevice(
            TrustedDevice(
                deviceId = pairing.payload.windowsDeviceId,
                deviceName = pairing.payload.windowsDeviceName,
                identityPublicKey = pairing.payload.windowsIdentityPublicKey,
                identityFingerprint = pairing.payload.windowsIdentityFingerprint,
                futureTlsCertificateFingerprint = null,
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
        _state.value = PairingState.Completed(pairing.payload.windowsDeviceId)
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
        _state.value = PairingState.Failed(message, technicalCode)
    }

    private suspend fun receivePairingHandshake(connection: DeviceConnection): ProtocolMessage {
        return try {
            connection.receiveHandshake(PAIRING_TIMEOUT_MS)
        } catch (error: TimeoutCancellationException) {
            throw ConnectionException.PairingTimeout(error)
        } catch (error: ConnectionException.Timeout) {
            throw ConnectionException.PairingTimeout(error)
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

    private data class ActivePairing(
        val payload: PairingQrPayload,
        val connection: DeviceConnection,
        val androidDeviceId: String? = null,
        val androidDeviceName: String? = null,
        val androidPublicKey: String? = null,
        val androidFingerprint: String? = null,
        val androidNonce: String? = null,
        val windowsNonce: String? = null,
        val verificationCode: String? = null,
    )

    private companion object {
        const val PAIRING_TIMEOUT_MS = 8_000L
    }
}
