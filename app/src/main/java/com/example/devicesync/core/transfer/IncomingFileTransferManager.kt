package com.example.devicesync.core.transfer

import android.content.ContentResolver
import android.net.Uri
import com.example.devicesync.core.network.FileTransferMessageListener
import com.example.devicesync.core.network.FileTransferTransport
import com.example.devicesync.core.protocol.*
import com.example.devicesync.core.security.Base64Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import java.io.OutputStream
import java.io.InputStream
import android.os.ParcelFileDescriptor
import com.example.devicesync.core.foldersync.FolderIncomingFileAuthorizer
import com.example.devicesync.core.foldersync.FolderIncomingTarget
import java.security.MessageDigest

sealed interface IncomingFileTransferState {
    data object Idle : IncomingFileTransferState
    data class Offered(val transfer: IncomingAndroidFileTransfer) : IncomingFileTransferState
    data class Receiving(val transfer: IncomingAndroidFileTransfer, val bytesPerSecond: Long) : IncomingFileTransferState
    data class Verifying(val transfer: IncomingAndroidFileTransfer) : IncomingFileTransferState
    data class Completed(val transfer: IncomingAndroidFileTransfer) : IncomingFileTransferState
    data class Failed(val transfer: IncomingAndroidFileTransfer?, val code: String, val message: String) : IncomingFileTransferState
    data object Cancelled : IncomingFileTransferState
}

data class IncomingAndroidFileTransfer(
    val transferId: String,
    val senderDeviceId: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val expectedSha256: String,
    val chunkSize: Int,
    val destinationUri: String? = null,
    val receivedBytes: Long = 0,
    val nextChunkIndex: Int = 0,
    val resumable: Boolean = false,
    val startedAtMillis: Long = System.currentTimeMillis(),
    val chunkSha256: List<String> = emptyList(),
    val folderTarget: FolderIncomingTarget? = null,
)

interface IncomingFileDestination {
    fun open(uri: String): OutputStream
    fun openResume(uri: String, offset: Long): OutputStream = open(uri)
    fun openRead(uri: String): InputStream = throw UnsupportedOperationException()
    fun delete(uri: String)
}

class ContentResolverIncomingFileDestination(private val resolver: ContentResolver) : IncomingFileDestination {
    override fun open(uri: String): OutputStream = resolver.openOutputStream(Uri.parse(uri), "w")
        ?: error("The selected destination cannot be opened.")
    override fun delete(uri: String) { runCatching { resolver.delete(Uri.parse(uri), null, null) } }
    override fun openResume(uri: String, offset: Long): OutputStream {
        val descriptor = resolver.openFileDescriptor(Uri.parse(uri), "rw") ?: error("The partial destination cannot be reopened.")
        return ParcelFileDescriptor.AutoCloseOutputStream(descriptor).also { it.channel.position(offset) }
    }
    override fun openRead(uri: String): InputStream = resolver.openInputStream(Uri.parse(uri))
        ?: error("The partial destination cannot be read.")
}

class IncomingFileTransferManager(
    private val transport: FileTransferTransport,
    private val destination: IncomingFileDestination,
    private val scope: CoroutineScope,
    private val checkpointStore: IncomingTransferCheckpointStore? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val folderAuthorizer: FolderIncomingFileAuthorizer? = null,
) : FileTransferMessageListener {
    companion object {
        const val MAX_FILE_SIZE_BYTES = 100L * 1024 * 1024
        const val CHUNK_SIZE_BYTES = 64 * 1024
    }

    private val _state = MutableStateFlow<IncomingFileTransferState>(IncomingFileTransferState.Idle)
    val state: StateFlow<IncomingFileTransferState> = _state.asStateFlow()
    private var active: IncomingAndroidFileTransfer? = null
    private var output: OutputStream? = null
    private var digest: MessageDigest? = null
    private var startedAtNanos = 0L
    private var offerJob: Job? = null
    private val completedReceipts = mutableMapOf<String, FileReceivedPayload>()

    init { transport.addFileTransferListener(this) }

    fun close() { transport.removeFileTransferListener(this) }

    override suspend fun onFileTransferMessage(message: ProtocolMessage) {
        when (message.type) {
            ProtocolMessageType.FILE_OFFER.value -> handleOffer(message)
            ProtocolMessageType.FILE_CHUNK.value -> handleChunk(ProtocolSerializer.decodePayload(message.payload))
            ProtocolMessageType.FILE_COMPLETE.value -> handleComplete(ProtocolSerializer.decodePayload(message.payload))
            ProtocolMessageType.FILE_RESUME_REQUEST.value -> handleResume(message)
            ProtocolMessageType.FILE_CANCEL.value -> {
                val payload = ProtocolSerializer.decodePayload<FileCancelPayload>(message.payload)
                if (payload.transferId == active?.transferId) cleanup(cancelled = true)
            }
        }
    }

    override fun onFileTransferDisconnected() {
        scope.launch(Dispatchers.IO) {
            val transfer = active
            if (transfer?.resumable == true && transfer.destinationUri != null) {
                runCatching { output?.flush() }
                runCatching { output?.close() }
                output = null
                saveCheckpoint()
                digest = null
                active = null
                _state.value = IncomingFileTransferState.Failed(transfer, "disconnected", "The connection was lost; transfer can be resumed.")
            } else {
                cleanup(cancelled = true)
            }
        }
    }

    private suspend fun handleOffer(message: ProtocolMessage) {
        val offer = ProtocolSerializer.decodePayload<FileOfferPayload>(message.payload)
        val invalid = validate(offer)
        if (invalid != null || active != null) {
            send(ProtocolMessageType.FILE_REJECT, FileRejectPayload(offer.transferId, invalid ?: "transfer_busy", "Incoming transfer rejected."))
            return
        }
        val folderTarget = try { folderAuthorizer?.authorize(offer) } catch (error: Throwable) {
            send(ProtocolMessageType.FILE_REJECT, FileRejectPayload(offer.transferId, "folder_transfer_not_authorized", error.message))
            return
        }
        if (offer.folderSyncId != null && folderTarget == null) {
            send(ProtocolMessageType.FILE_REJECT, FileRejectPayload(offer.transferId, "folder_transfer_not_authorized", "Folder transfer is not authorized."))
            return
        }
        active = IncomingAndroidFileTransfer(
            transferId = offer.transferId,
            senderDeviceId = message.senderDeviceId,
            fileName = offer.fileName,
            mimeType = offer.mimeType,
            sizeBytes = offer.sizeBytes,
            expectedSha256 = offer.sha256,
            chunkSize = offer.chunkSize,
            resumable = folderTarget == null && (transport.state.value as? com.example.devicesync.core.network.ConnectionState.Connected)
                ?.capabilities?.contains(com.example.devicesync.core.network.SupportedCapabilities.FILE_TRANSFER_V2) == true && checkpointStore != null,
            startedAtMillis = nowMillis(),
            folderTarget = folderTarget,
        )
        _state.value = IncomingFileTransferState.Offered(active!!)
        if (folderTarget != null) acceptImmediately(folderTarget.temporaryUri)
    }

    private suspend fun acceptImmediately(destinationUri: String) {
        val transfer = active ?: return
        try {
            output = destination.open(destinationUri)
            digest = MessageDigest.getInstance("SHA-256")
            active = transfer.copy(destinationUri = destinationUri)
            startedAtNanos = System.nanoTime()
            _state.value = IncomingFileTransferState.Receiving(active!!, 0)
            saveCheckpoint()
            send(ProtocolMessageType.FILE_ACCEPT, FileAcceptPayload(transfer.transferId))
        } catch (error: Throwable) {
            fail("destination_open_failed", error.message ?: "Cannot open destination.")
        }
    }

    fun accept(destinationUri: String) {
        val transfer = active ?: return
        if (_state.value !is IncomingFileTransferState.Offered) return
        offerJob = scope.launch(Dispatchers.IO) {
            try {
                output = destination.open(destinationUri)
                digest = MessageDigest.getInstance("SHA-256")
                active = transfer.copy(destinationUri = destinationUri)
                startedAtNanos = System.nanoTime()
                _state.value = IncomingFileTransferState.Receiving(active!!, 0)
                saveCheckpoint()
                send(ProtocolMessageType.FILE_ACCEPT, FileAcceptPayload(transfer.transferId))
            } catch (error: Throwable) {
                fail("destination_open_failed", error.message ?: "Cannot open destination.")
            }
        }
    }

    fun reject(code: String = "user_rejected") {
        val transfer = active ?: return
        scope.launch {
            send(ProtocolMessageType.FILE_REJECT, FileRejectPayload(transfer.transferId, code, "The user rejected the file."))
            cleanup(cancelled = true)
        }
    }

    fun cancel() {
        val transfer = active ?: return
        scope.launch {
            send(ProtocolMessageType.FILE_CANCEL, FileCancelPayload(transfer.transferId, "user_cancelled"))
            cleanup(cancelled = true)
        }
    }

    private suspend fun handleChunk(chunk: FileChunkPayload) {
        val transfer = active ?: return
        if (_state.value !is IncomingFileTransferState.Receiving || chunk.transferId != transfer.transferId) return
        try {
            val bytes = java.util.Base64.getDecoder().decode(chunk.data)
            if (transfer.resumable && chunk.index < transfer.nextChunkIndex) {
                if (chunk.index !in transfer.chunkSha256.indices || chunk.chunkSha256 != transfer.chunkSha256[chunk.index]) {
                    fail("resume_conflict", "Duplicate chunk differs from the durable chunk.")
                } else {
                    send(ProtocolMessageType.FILE_CHUNK_RECEIVED, FileChunkReceivedPayload(
                        transfer.transferId, transfer.nextChunkIndex, transfer.receivedBytes,
                    ))
                }
                return
            }
            require(chunk.index == transfer.nextChunkIndex) { "chunk_index" }
            require(chunk.offset == transfer.receivedBytes) { "chunk_offset" }
            require(bytes.size <= transfer.chunkSize) { "chunk_too_large" }
            require(transfer.receivedBytes + bytes.size <= transfer.sizeBytes) { "size_exceeded" }
            val actualChunkHash = Base64Url.encode(MessageDigest.getInstance("SHA-256").digest(bytes))
            if (transfer.resumable) require(chunk.chunkSha256 == actualChunkHash) { "chunk_checksum_mismatch" }
            output!!.write(bytes)
            digest!!.update(bytes)
            output!!.flush()
            active = transfer.copy(
                receivedBytes = transfer.receivedBytes + bytes.size,
                nextChunkIndex = transfer.nextChunkIndex + 1,
                chunkSha256 = if (transfer.resumable) transfer.chunkSha256 + actualChunkHash else transfer.chunkSha256,
            )
            saveCheckpoint()
            val elapsed = (System.nanoTime() - startedAtNanos).coerceAtLeast(1) / 1_000_000_000.0
            _state.value = IncomingFileTransferState.Receiving(active!!, (active!!.receivedBytes / elapsed).toLong())
            if (transfer.resumable) send(ProtocolMessageType.FILE_CHUNK_RECEIVED, FileChunkReceivedPayload(
                active!!.transferId, active!!.nextChunkIndex, active!!.receivedBytes,
            ))
        } catch (error: Throwable) {
            fail(error.message ?: "chunk_invalid", "Invalid file chunk.")
        }
    }

    private suspend fun handleComplete(complete: FileCompletePayload) {
        completedReceipts[complete.transferId]?.let {
            send(ProtocolMessageType.FILE_RECEIVED, it)
            return
        }
        val transfer = active ?: return
        if (complete.transferId != transfer.transferId) return
        try {
            require(complete.sizeBytes == transfer.sizeBytes && transfer.receivedBytes == transfer.sizeBytes) { "complete_too_early" }
            require(complete.totalChunks == transfer.nextChunkIndex) { "chunk_count" }
            _state.value = IncomingFileTransferState.Verifying(transfer)
            output!!.flush()
            output!!.close()
            output = null
            val actual = Base64Url.encode(digest!!.digest())
            require(actual == transfer.expectedSha256) { "checksum_mismatch" }
            val committedUri = transfer.folderTarget?.let { folderAuthorizer?.commit(it) }
            val savedName = transfer.folderTarget?.finalName ?: transfer.fileName
            val receipt = FileReceivedPayload(transfer.transferId, transfer.sizeBytes, actual, savedName)
            completedReceipts[transfer.transferId] = receipt
            send(ProtocolMessageType.FILE_RECEIVED, receipt)
            _state.value = IncomingFileTransferState.Completed(transfer.copy(destinationUri = committedUri ?: transfer.destinationUri))
            checkpointStore?.delete(transfer.transferId)
            active = null
            digest = null
        } catch (error: Throwable) {
            fail(error.message ?: "complete_invalid", "File verification failed.")
        }
    }

    private suspend fun fail(code: String, message: String) {
        val transfer = active
        if (transfer != null) send(ProtocolMessageType.FILE_ERROR, FileErrorPayload(transfer.transferId, code, message))
        output?.close()
        output = null
        transfer?.destinationUri?.let(destination::delete)
        transfer?.let { checkpointStore?.delete(it.transferId) }
        active = null
        digest = null
        _state.value = IncomingFileTransferState.Failed(transfer, code, message)
    }

    private fun cleanup(cancelled: Boolean) {
        offerJob?.cancel()
        runCatching { output?.close() }
        output = null
        active?.destinationUri?.let(destination::delete)
        active?.let { checkpointStore?.delete(it.transferId) }
        active = null
        digest = null
        if (cancelled) _state.value = IncomingFileTransferState.Cancelled
    }

    private fun validate(offer: FileOfferPayload): String? = when {
        offer.transferId.isBlank() -> "invalid_transfer_id"
        offer.fileName.isBlank() || offer.fileName.contains('/') || offer.fileName.contains('\\') -> "unsafe_file_name"
        offer.sizeBytes < 0 || offer.sizeBytes > MAX_FILE_SIZE_BYTES -> "file_too_large"
        offer.chunkSize != CHUNK_SIZE_BYTES -> "unsupported_chunk_size"
        runCatching { Base64Url.decode(offer.sha256).size }.getOrDefault(0) != 32 -> "invalid_sha256"
        else -> null
    }

    private suspend fun send(type: ProtocolMessageType, payload: Any) {
        val json: JsonElement = when (payload) {
            is FileAcceptPayload -> ProtocolSerializer.payloadToJson(payload)
            is FileRejectPayload -> ProtocolSerializer.payloadToJson(payload)
            is FileReceivedPayload -> ProtocolSerializer.payloadToJson(payload)
            is FileCancelPayload -> ProtocolSerializer.payloadToJson(payload)
            is FileErrorPayload -> ProtocolSerializer.payloadToJson(payload)
            is FileChunkReceivedPayload -> ProtocolSerializer.payloadToJson(payload)
            is FileResumeAcceptedPayload -> ProtocolSerializer.payloadToJson(payload)
            else -> error("Unsupported file payload")
        }
        transport.sendFileTransferMessage(type.value, json)
    }

    private suspend fun handleResume(message: ProtocolMessage) {
        val request = ProtocolSerializer.decodePayload<FileResumeRequestPayload>(message.payload)
        val checkpoint = checkpointStore?.load(request.transferId)
        if (checkpoint == null) {
            send(ProtocolMessageType.FILE_REJECT, FileRejectPayload(request.transferId, "resume_not_found", "No partial transfer exists."))
            return
        }
        if (checkpoint.senderDeviceId != message.senderDeviceId || checkpoint.fileName != request.fileName ||
            checkpoint.sizeBytes != request.sizeBytes || checkpoint.expectedSha256 != request.sha256 || checkpoint.chunkSize != request.chunkSize) {
            send(ProtocolMessageType.FILE_REJECT, FileRejectPayload(request.transferId, "resume_conflict", "Resume metadata differs."))
            return
        }
        try {
            val restored = IncomingAndroidFileTransfer(
                checkpoint.transferId, checkpoint.senderDeviceId, checkpoint.fileName, checkpoint.mimeType,
                checkpoint.sizeBytes, checkpoint.expectedSha256, checkpoint.chunkSize, checkpoint.destinationUri,
                checkpoint.receivedBytes, checkpoint.nextChunkIndex, true, checkpoint.startedAtMillis, checkpoint.chunkSha256,
            )
            val restoredDigest = MessageDigest.getInstance("SHA-256")
            destination.openRead(checkpoint.destinationUri).use { input ->
                val buffer = ByteArray(CHUNK_SIZE_BYTES)
                var remaining = checkpoint.receivedBytes
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    require(read > 0) { "partial_truncated" }
                    restoredDigest.update(buffer, 0, read)
                    remaining -= read
                }
            }
            output = destination.openResume(checkpoint.destinationUri, checkpoint.receivedBytes)
            digest = restoredDigest
            active = restored
            startedAtNanos = System.nanoTime()
            _state.value = IncomingFileTransferState.Receiving(restored, 0)
            send(ProtocolMessageType.FILE_RESUME_ACCEPTED, FileResumeAcceptedPayload(
                restored.transferId, restored.nextChunkIndex, restored.receivedBytes,
            ))
        } catch (error: Throwable) {
            fail("resume_io_error", error.message ?: "Cannot restore partial transfer.")
        }
    }

    fun cleanupStalePartials(maximumAgeMillis: Long = 7L * 24 * 60 * 60 * 1000) {
        scope.launch(Dispatchers.IO) {
            checkpointStore?.expired(nowMillis() - maximumAgeMillis).orEmpty().forEach { checkpoint ->
                destination.delete(checkpoint.destinationUri)
                checkpointStore?.delete(checkpoint.transferId)
            }
        }
    }

    private fun saveCheckpoint() {
        val transfer = active ?: return
        if (!transfer.resumable || transfer.destinationUri == null) return
        checkpointStore?.save(IncomingTransferCheckpoint(
            transfer.transferId, transfer.senderDeviceId, transfer.fileName, transfer.mimeType,
            transfer.sizeBytes, transfer.expectedSha256, transfer.chunkSize, transfer.destinationUri,
            transfer.receivedBytes, transfer.nextChunkIndex, transfer.startedAtMillis, nowMillis(), transfer.chunkSha256,
        ))
    }
}
