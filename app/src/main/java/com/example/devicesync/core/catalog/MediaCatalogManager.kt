package com.example.devicesync.core.catalog

import com.example.devicesync.core.network.MediaCatalogMessageListener
import com.example.devicesync.core.network.MediaCatalogTransport
import com.example.devicesync.core.protocol.CatalogCancelPayload
import com.example.devicesync.core.protocol.CatalogChangedPayload
import com.example.devicesync.core.protocol.CatalogErrorPayload
import com.example.devicesync.core.protocol.CatalogFileDownloadRequestPayload
import com.example.devicesync.core.protocol.CatalogPagePayload
import com.example.devicesync.core.protocol.CatalogPermissionPayload
import com.example.devicesync.core.protocol.CatalogQueryPayload
import com.example.devicesync.core.protocol.CatalogThumbnailRequestPayload
import com.example.devicesync.core.protocol.CatalogThumbnailResponsePayload
import com.example.devicesync.core.protocol.ProtocolMessage
import com.example.devicesync.core.protocol.ProtocolMessageType
import com.example.devicesync.core.protocol.ProtocolSerializer
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class MediaCatalogManager(
    private val source: MediaCatalogSource,
    private val transport: MediaCatalogTransport,
    private val startDownload: suspend (uri: String, transferId: String) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : MediaCatalogMessageListener {
    private val operations = ConcurrentHashMap<String, Job>()
    private val thumbnailSlots = Semaphore(4)
    private var changeNotificationJob: Job? = null

    init {
        transport.addMediaCatalogListener(this)
        source.startObserving { generation ->
            changeNotificationJob?.cancel()
            changeNotificationJob = scope.launch {
                delay(350)
                runCatching {
                    transport.sendCatalogMessage(
                        ProtocolMessageType.CATALOG_CHANGED.value,
                        ProtocolSerializer.payloadToJson(CatalogChangedPayload(generation, requiresRefresh = true)),
                    )
                }
            }
        }
    }

    override suspend fun onMediaCatalogMessage(message: ProtocolMessage) {
        when (message.type) {
            ProtocolMessageType.CATALOG_QUERY.value -> {
                val payload = decode<CatalogQueryPayload>(message) ?: return
                launchOperation(payload.queryId) { handleQuery(payload) }
            }
            ProtocolMessageType.CATALOG_THUMBNAIL_REQUEST.value -> {
                val payload = decode<CatalogThumbnailRequestPayload>(message) ?: return
                launchOperation(payload.requestId) { handleThumbnail(payload) }
            }
            ProtocolMessageType.CATALOG_FILE_DOWNLOAD_REQUEST.value -> {
                val payload = decode<CatalogFileDownloadRequestPayload>(message) ?: return
                launchOperation(payload.requestId) { handleDownload(payload) }
            }
            ProtocolMessageType.CATALOG_CANCEL.value -> {
                val payload = decode<CatalogCancelPayload>(message) ?: return
                operations.remove(payload.requestId)?.cancel(CancellationException(payload.reason))
            }
        }
    }

    override fun onMediaCatalogDisconnected() {
        changeNotificationJob?.cancel()
        changeNotificationJob = null
        operations.values.forEach { it.cancel(CancellationException("Disconnected")) }
        operations.clear()
    }

    fun permissionState(): CatalogPermissionState = source.permissionState()

    fun revokeAllAccess() {
        onMediaCatalogDisconnected()
        source.clearRevokedData()
        scope.launch { sendPermission(CatalogPermissionState("revoked", emptyList(), canRequest = true, "user_revoked")) }
    }

    fun close() {
        onMediaCatalogDisconnected()
        source.stopObserving()
        transport.removeMediaCatalogListener(this)
    }

    private suspend fun handleQuery(payload: CatalogQueryPayload) {
        val permission = source.permissionState()
        if (permission.state == "denied" || permission.state == "revoked") {
            sendPermission(permission, payload.queryId)
            throw CatalogSourceException("PERMISSION_REQUIRED", "Media access is not granted.")
        }
        val page = source.query(payload)
        transport.sendCatalogMessage(
            ProtocolMessageType.CATALOG_PAGE.value,
            ProtocolSerializer.payloadToJson(
                CatalogPagePayload(
                    queryId = payload.queryId,
                    items = page.items,
                    nextPageToken = page.nextPageToken,
                    snapshotGeneration = page.snapshotGeneration,
                    hasMore = page.nextPageToken != null,
                )
            ),
        )
    }

    private suspend fun handleThumbnail(payload: CatalogThumbnailRequestPayload) {
        if (payload.maxWidth !in 32..512 || payload.maxHeight !in 32..512 ||
            payload.quality !in 40..90 || payload.format !in setOf("jpeg", "webp")) {
            throw CatalogSourceException("INVALID_REQUEST", "Invalid thumbnail limits.")
        }
        thumbnailSlots.withPermit {
            val thumbnail = source.thumbnail(
                payload.itemId,
                payload.expectedRevision,
                payload.maxWidth,
                payload.maxHeight,
                payload.format,
                payload.quality,
            )
            if (thumbnail.bytes.size > 256 * 1024) {
                throw CatalogSourceException("THUMBNAIL_TOO_LARGE", "Thumbnail exceeds 256 KiB.")
            }
            transport.sendCatalogMessage(
                ProtocolMessageType.CATALOG_THUMBNAIL_RESPONSE.value,
                ProtocolSerializer.payloadToJson(
                    CatalogThumbnailResponsePayload(
                        requestId = payload.requestId,
                        itemId = thumbnail.itemId,
                        revision = thumbnail.revision,
                        mimeType = thumbnail.mimeType,
                        width = thumbnail.width,
                        height = thumbnail.height,
                        sizeBytes = thumbnail.bytes.size.toLong(),
                        data = Base64.getEncoder().encodeToString(thumbnail.bytes),
                    )
                ),
            )
        }
    }

    private suspend fun handleDownload(payload: CatalogFileDownloadRequestPayload) {
        val uri = source.resolveOriginal(payload.itemId, payload.expectedRevision)
        startDownload(uri, payload.transferId)
    }

    private fun launchOperation(requestId: String, block: suspend () -> Unit) {
        if (operations.containsKey(requestId)) {
            scope.launch { sendError(requestId, CatalogSourceException("BUSY", "Request is already active.", true)) }
            return
        }
        val job = scope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                // Cancellation is idempotent and intentionally produces no late response.
            } catch (error: CatalogSourceException) {
                if (error.code == "PERMISSION_REVOKED") {
                    source.clearRevokedData()
                    sendPermission(CatalogPermissionState("revoked", emptyList(), true, "permission_revoked"), requestId)
                }
                sendError(requestId, error)
            } catch (error: Throwable) {
                sendError(requestId, CatalogSourceException("INTERNAL_ERROR", error.message ?: "Catalog operation failed."))
            } finally {
                operations.remove(requestId)
            }
        }
        operations[requestId] = job
    }

    private suspend fun sendPermission(state: CatalogPermissionState, requestId: String? = null) {
        transport.sendCatalogMessage(
            ProtocolMessageType.CATALOG_PERMISSION.value,
            ProtocolSerializer.payloadToJson(
                CatalogPermissionPayload(requestId, state.state, state.grantedCategories, state.canRequest, state.reasonCode)
            ),
        )
    }

    private suspend fun sendError(requestId: String, error: CatalogSourceException) {
        runCatching {
            transport.sendCatalogMessage(
                ProtocolMessageType.CATALOG_ERROR.value,
                ProtocolSerializer.payloadToJson(
                    CatalogErrorPayload(requestId, error.code, error.message, error.retryable, error.currentGeneration)
                ),
            )
        }
    }

    private suspend inline fun <reified T> decode(message: ProtocolMessage): T? = try {
        ProtocolSerializer.decodePayload(message.payload)
    } catch (error: Throwable) {
        sendError(message.messageId, CatalogSourceException("INVALID_REQUEST", "Payload is invalid."))
        null
    }
}
