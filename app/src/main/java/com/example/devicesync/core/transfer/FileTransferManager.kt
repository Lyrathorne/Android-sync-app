package com.example.devicesync.core.transfer

import com.example.devicesync.core.network.ConnectionState
import com.example.devicesync.core.network.FileTransferMessageListener
import com.example.devicesync.core.network.FileTransferTransport
import com.example.devicesync.core.network.SupportedCapabilities
import com.example.devicesync.core.protocol.FileAcceptPayload
import com.example.devicesync.core.protocol.FileCancelPayload
import com.example.devicesync.core.protocol.FileChunkPayload
import com.example.devicesync.core.protocol.FileChunkReceivedPayload
import com.example.devicesync.core.protocol.FileCompletePayload
import com.example.devicesync.core.protocol.FileErrorPayload
import com.example.devicesync.core.protocol.FileOfferPayload
import com.example.devicesync.core.protocol.FileReceivedPayload
import com.example.devicesync.core.protocol.FileRejectPayload
import com.example.devicesync.core.protocol.FileResumeRequestPayload
import com.example.devicesync.core.protocol.FileResumeAcceptedPayload
import com.example.devicesync.core.protocol.ProtocolMessage
import com.example.devicesync.core.protocol.ProtocolMessageType
import com.example.devicesync.core.protocol.ProtocolSerializer
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

interface OutgoingFileTransferController {
    val state: StateFlow<FileTransferState>
    fun start(uri: String): Job
    suspend fun cancel()
}

data class AndroidOutgoingResumePoint(
    val transferId: String,
    val sha256: String,
    val acknowledgedOffset: Long,
    val nextChunkIndex: Int,
)

data class AndroidFolderTransferMetadata(val syncId: String, val relativePath: String, val conflictCopy: Boolean = false)

class FileTransferManager(
    private val metadataSource: FileMetadataSource,
    private val transport: FileTransferTransport,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : FileTransferMessageListener, OutgoingFileTransferController {
    companion object {
        const val MAXIMUM_FILE_SIZE = 104_857_600L
        const val CHUNK_SIZE = 65_536
        private const val OFFER_TIMEOUT_MILLIS = 60_000L
        private const val RECEIPT_TIMEOUT_MILLIS = 30_000L
        private const val CHUNK_ACK_TIMEOUT_MILLIS = 30_000L
    }

    private val _state = MutableStateFlow<FileTransferState>(FileTransferState.Idle)
    override val state: StateFlow<FileTransferState> = _state.asStateFlow()
    private val transferMutex = Mutex()
    private val activeTransfer = AtomicReference<OutgoingFileTransfer?>(null)
    private var activeJob: Job? = null
    private var offerResult: CompletableDeferred<OfferResult>? = null
    private var receiptResult: CompletableDeferred<FileReceivedPayload>? = null
    private var chunkAcknowledgement: CompletableDeferred<FileChunkReceivedPayload>? = null
    @Volatile
    var lastResumePoint: AndroidOutgoingResumePoint? = null
        private set

    init {
        transport.addFileTransferListener(this)
    }

    override fun start(uri: String): Job {
        check(activeJob?.isActive != true) { "A file transfer is already active." }
        return scope.launch { transfer(uri) }.also { activeJob = it }
    }

    suspend fun transfer(
        uri: String,
        resumePoint: AndroidOutgoingResumePoint? = null,
        requestedTransferId: String? = null,
        folder: AndroidFolderTransferMetadata? = null,
    ) = transferMutex.withLock {
        try {
            lastResumePoint = null
            val connected = transport.state.value as? ConnectionState.Connected
                ?: fail("not_connected", "No authenticated computer is connected.")
            if (SupportedCapabilities.FILE_TRANSFER_V1 !in connected.capabilities) {
                fail("unsupported", "The connected computer does not support file transfer.")
            }
            val useV2 = SupportedCapabilities.FILE_TRANSFER_V2 in connected.capabilities

            _state.value = FileTransferState.ReadingMetadata
            val metadata = metadataSource.read(uri)
            if (metadata.sizeBytes !in 0..MAXIMUM_FILE_SIZE) {
                fail("file_too_large", "The selected file exceeds the 100 MiB limit.")
            }
            val transportLimit = com.example.devicesync.core.network.TransportProfile
                .forKind(connected.transportKind)
                .maximumFileBytes
            val transferChunkSize = if (connected.slowTransport) 24 * 1024 else CHUNK_SIZE
            if (metadata.sizeBytes > transportLimit) {
                fail(
                    "transport_file_too_large",
                    "This transport supports files up to ${transportLimit / (1024 * 1024)} MiB.",
                )
            }

            _state.value = FileTransferState.Hashing
            val sha256 = resumePoint?.sha256 ?: metadataSource.open(uri).use { stream ->
                hashStream(stream, metadata.sizeBytes)
            }
            val transfer = OutgoingFileTransfer(
                transferId = resumePoint?.transferId ?: requestedTransferId ?: UUID.randomUUID().toString(),
                uri = uri,
                fileName = metadata.displayName,
                sizeBytes = metadata.sizeBytes,
                mimeType = metadata.mimeType,
                sha256 = sha256,
                targetDeviceId = connected.deviceId,
                startedAtMillis = nowMillis(),
            )
            activeTransfer.set(transfer)
            val decision = CompletableDeferred<OfferResult>()
            offerResult = decision
            if (resumePoint == null) {
                transport.sendFileTransferMessage(
                    ProtocolMessageType.FILE_OFFER.value,
                    ProtocolSerializer.payloadToJson(FileOfferPayload(
                        transferId = transfer.transferId,
                        fileName = transfer.fileName,
                        sizeBytes = transfer.sizeBytes,
                        mimeType = transfer.mimeType,
                        sha256 = transfer.sha256,
                        chunkSize = transferChunkSize,
                        folderSyncId = folder?.syncId,
                        relativePath = folder?.relativePath,
                        conflictCopy = folder?.conflictCopy ?: false,
                    )),
                )
            } else {
                if (!useV2) fail("resume_unsupported", "The connected computer does not support resume.")
                transport.sendFileTransferMessage(
                    ProtocolMessageType.FILE_RESUME_REQUEST.value,
                    ProtocolSerializer.payloadToJson(FileResumeRequestPayload(
                        transfer.transferId, transfer.fileName, transfer.sizeBytes, transfer.sha256, transferChunkSize,
                        folder?.syncId, folder?.relativePath, folder?.conflictCopy ?: false,
                    )),
                )
            }
            _state.value = FileTransferState.WaitingForAcceptance
            var sentBytes = 0L
            var chunkIndex = 0
            when (val result = withTimeout(OFFER_TIMEOUT_MILLIS) { decision.await() }) {
                OfferResult.Accepted -> Unit
                is OfferResult.Resumed -> {
                    if (resumePoint == null || result.offset !in 0..resumePoint.acknowledgedOffset || result.nextChunkIndex < 0)
                        fail("invalid_resume_point", "The receiver returned an invalid resume point.")
                    sentBytes = result.offset
                    chunkIndex = result.nextChunkIndex
                }
                is OfferResult.Rejected -> {
                    _state.value = FileTransferState.Rejected(result.code, result.message)
                    return@withLock
                }
            }
            if (useV2) lastResumePoint = AndroidOutgoingResumePoint(transfer.transferId, sha256, sentBytes, chunkIndex)

            metadataSource.open(uri).use { stream ->
                skipFully(stream, sentBytes)
                val buffer = ByteArray(transferChunkSize)
                while (true) {
                    val count = readChunk(stream, buffer)
                    if (count == 0) break
                    val acknowledgement = if (useV2) CompletableDeferred<FileChunkReceivedPayload>().also {
                        chunkAcknowledgement = it
                    } else null
                    transport.sendFileTransferMessage(
                        ProtocolMessageType.FILE_CHUNK.value,
                        ProtocolSerializer.payloadToJson(
                            FileChunkPayload(
                                transferId = transfer.transferId,
                                index = chunkIndex,
                                offset = sentBytes,
                                data = Base64.getEncoder().encodeToString(buffer.copyOf(count)),
                                chunkSha256 = if (useV2) Base64.getUrlEncoder().withoutPadding().encodeToString(
                                    MessageDigest.getInstance("SHA-256").digest(buffer.copyOf(count))
                                ) else null,
                            )
                        ),
                    )
                    sentBytes += count
                    chunkIndex++
                    if (acknowledgement != null) {
                        val acknowledged = withTimeout(CHUNK_ACK_TIMEOUT_MILLIS) { acknowledgement.await() }
                        if (acknowledged.nextChunkIndex != chunkIndex || acknowledged.offset != sentBytes) {
                            fail("invalid_chunk_ack", "The receiver acknowledged an unexpected resume point.")
                        }
                        chunkAcknowledgement = null
                        lastResumePoint = AndroidOutgoingResumePoint(transfer.transferId, transfer.sha256, sentBytes, chunkIndex)
                    }
                    activeTransfer.set(activeTransfer.get()?.copy(sentBytes = sentBytes, nextChunkIndex = chunkIndex))
                    val elapsedMillis = (nowMillis() - transfer.startedAtMillis).coerceAtLeast(1)
                    _state.value = FileTransferState.Transferring(
                        sentBytes,
                        transfer.sizeBytes,
                        sentBytes * 1000 / elapsedMillis,
                    )
                }
            }
            if (sentBytes != transfer.sizeBytes) {
                fail("size_changed", "The provider returned a different number of bytes on the second read.")
            }

            val receipt = CompletableDeferred<FileReceivedPayload>()
            receiptResult = receipt
            transport.sendFileTransferMessage(
                ProtocolMessageType.FILE_COMPLETE.value,
                ProtocolSerializer.payloadToJson(
                    FileCompletePayload(
                        transferId = transfer.transferId,
                        totalChunks = chunkIndex,
                        sizeBytes = sentBytes,
                    )
                ),
            )
            _state.value = FileTransferState.WaitingForReceipt
            val received = withTimeout(RECEIPT_TIMEOUT_MILLIS) { receipt.await() }
            _state.value = FileTransferState.Completed(received.savedFileName)
            lastResumePoint = null
        } catch (error: CancellationException) {
            if (_state.value !is FileTransferState.Cancelled && _state.value !is FileTransferState.Failed) {
                _state.value = FileTransferState.Cancelled
            }
            throw error
        } catch (error: TransferFailure) {
            _state.value = FileTransferState.Failed(error.code, error.message.orEmpty())
        } catch (error: Throwable) {
            _state.value = FileTransferState.Failed("provider_error", error.message ?: "File transfer failed.")
        } finally {
            offerResult = null
            receiptResult = null
            chunkAcknowledgement = null
            activeTransfer.set(null)
            if (activeJob == currentCoroutineContext()[Job]) {
                activeJob = null
            }
        }
    }

    override suspend fun cancel() {
        val transfer = activeTransfer.get()
        if (transfer != null) {
            runCatching {
                transport.sendFileTransferMessage(
                    ProtocolMessageType.FILE_CANCEL.value,
                    ProtocolSerializer.payloadToJson(
                        FileCancelPayload(transfer.transferId, "user_cancelled")
                    ),
                )
            }
        }
        _state.value = FileTransferState.Cancelled
        activeJob?.cancel()
    }

    override suspend fun onFileTransferMessage(message: ProtocolMessage) {
        val transfer = activeTransfer.get() ?: return
        when (message.type) {
            ProtocolMessageType.FILE_ACCEPT.value -> {
                val payload = ProtocolSerializer.decodePayload<FileAcceptPayload>(message.payload)
                if (payload.transferId == transfer.transferId) offerResult?.complete(OfferResult.Accepted)
            }
            ProtocolMessageType.FILE_REJECT.value -> {
                val payload = ProtocolSerializer.decodePayload<FileRejectPayload>(message.payload)
                if (payload.transferId == transfer.transferId) {
                    offerResult?.complete(OfferResult.Rejected(payload.code, payload.message))
                }
            }
            ProtocolMessageType.FILE_RECEIVED.value -> {
                val payload = ProtocolSerializer.decodePayload<FileReceivedPayload>(message.payload)
                if (payload.transferId == transfer.transferId) receiptResult?.complete(payload)
            }
            ProtocolMessageType.FILE_CHUNK_RECEIVED.value -> {
                val payload = ProtocolSerializer.decodePayload<FileChunkReceivedPayload>(message.payload)
                if (payload.transferId == transfer.transferId) chunkAcknowledgement?.complete(payload)
            }
            ProtocolMessageType.FILE_RESUME_ACCEPTED.value -> {
                val payload = ProtocolSerializer.decodePayload<FileResumeAcceptedPayload>(message.payload)
                if (payload.transferId == transfer.transferId) {
                    offerResult?.complete(OfferResult.Resumed(payload.nextChunkIndex, payload.offset))
                }
            }
            ProtocolMessageType.FILE_CANCEL.value -> {
                val payload = ProtocolSerializer.decodePayload<FileCancelPayload>(message.payload)
                if (payload.transferId == transfer.transferId) {
                    _state.value = FileTransferState.Cancelled
                    offerResult?.completeExceptionally(TransferFailure("remote_cancelled", payload.reason))
                    receiptResult?.completeExceptionally(TransferFailure("remote_cancelled", payload.reason))
                    activeJob?.cancel(CancellationException(payload.reason))
                }
            }
            ProtocolMessageType.FILE_ERROR.value -> {
                val payload = ProtocolSerializer.decodePayload<FileErrorPayload>(message.payload)
                if (payload.transferId == transfer.transferId) {
                    val failure = TransferFailure(payload.code, payload.message ?: payload.code)
                    _state.value = FileTransferState.Failed(payload.code, payload.message ?: payload.code)
                    offerResult?.completeExceptionally(failure)
                    receiptResult?.completeExceptionally(failure)
                    chunkAcknowledgement?.completeExceptionally(failure)
                    activeJob?.cancel(CancellationException(failure.message))
                }
            }
        }
    }

    override fun onFileTransferDisconnected() {
        _state.value = FileTransferState.Failed("disconnected", "The connection was lost.")
        offerResult?.completeExceptionally(TransferFailure("disconnected", "The connection was lost."))
        receiptResult?.completeExceptionally(TransferFailure("disconnected", "The connection was lost."))
        chunkAcknowledgement?.completeExceptionally(TransferFailure("disconnected", "The connection was lost."))
        activeJob?.cancel(CancellationException("The connection was lost."))
    }

    private fun hashStream(stream: InputStream, expectedSize: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(CHUNK_SIZE)
        var bytesRead = 0L
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            digest.update(buffer, 0, count)
            bytesRead += count
            if (bytesRead > MAXIMUM_FILE_SIZE) fail("file_too_large", "The stream exceeds the 100 MiB limit.")
        }
        if (bytesRead != expectedSize) fail("size_changed", "The provider size does not match the stream.")
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest())
    }

    private fun readChunk(stream: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val count = stream.read(buffer, total, buffer.size - total)
            if (count < 0) break
            if (count == 0) continue
            total += count
        }
        return total
    }

    private fun skipFully(stream: InputStream, byteCount: Long) {
        var remaining = byteCount
        val buffer = ByteArray(CHUNK_SIZE)
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) fail("source_truncated", "The source is shorter than the resume point.")
                remaining -= read
            }
        }
    }

    private fun fail(code: String, message: String): Nothing = throw TransferFailure(code, message)

    private sealed interface OfferResult {
        data object Accepted : OfferResult
        data class Resumed(val nextChunkIndex: Int, val offset: Long) : OfferResult
        data class Rejected(val code: String, val message: String?) : OfferResult
    }

    private class TransferFailure(val code: String, message: String) : Exception(message)
}
