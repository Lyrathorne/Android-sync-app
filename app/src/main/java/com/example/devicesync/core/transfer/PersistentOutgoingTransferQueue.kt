package com.example.devicesync.core.transfer

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class AndroidOutgoingQueueItem(
    val transferId: String,
    val uri: String,
    val sha256: String? = null,
    val acknowledgedOffset: Long = 0,
    val nextChunkIndex: Int = 0,
    val attemptCount: Int = 0,
    val state: String = "queued",
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val lastError: String? = null,
    val folderSyncId: String? = null,
    val relativePath: String? = null,
    val conflictCopy: Boolean = false,
)

interface AndroidOutgoingQueueStore {
    fun load(): List<AndroidOutgoingQueueItem>
    fun upsert(item: AndroidOutgoingQueueItem)
    fun delete(transferId: String)
}

class JsonAndroidOutgoingQueueStore(context: Context) : AndroidOutgoingQueueStore {
    private val path = File(context.filesDir, "transfers/outgoing-queue.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    @Synchronized override fun load(): List<AndroidOutgoingQueueItem> = runCatching {
        if (!path.exists()) emptyList<AndroidOutgoingQueueItem>()
        else json.decodeFromString<List<AndroidOutgoingQueueItem>>(path.readText())
    }.getOrDefault(emptyList())
    @Synchronized override fun upsert(item: AndroidOutgoingQueueItem) {
        val items = load().toMutableList()
        val index = items.indexOfFirst { it.transferId == item.transferId }
        if (index >= 0) items[index] = item else items += item
        write(items)
    }
    @Synchronized override fun delete(transferId: String) = write(load().filterNot { it.transferId == transferId })
    private fun write(items: List<AndroidOutgoingQueueItem>) {
        path.parentFile?.mkdirs()
        val temporary = File(path.path + ".tmp")
        temporary.writeText(json.encodeToString(items))
        check(temporary.renameTo(path) || run { path.delete(); temporary.renameTo(path) })
    }
}

class PersistentOutgoingTransferQueue(
    private val manager: FileTransferManager,
    private val store: AndroidOutgoingQueueStore,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : OutgoingFileTransferController {
    override val state: StateFlow<FileTransferState> = manager.state
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val items = mutableListOf<AndroidOutgoingQueueItem>()
    private var active: AndroidOutgoingQueueItem? = null
    private val worker = scope.launch(Dispatchers.IO) { workerLoop() }

    init {
        items += store.load().filter { it.state !in setOf("completed", "cancelled") }
        if (items.any { it.state != "failed" }) signal.trySend(Unit)
    }

    override fun start(uri: String): Job = scope.launch(Dispatchers.IO) {
        val now = nowMillis()
        val item = AndroidOutgoingQueueItem(UUID.randomUUID().toString(), uri, createdAtMillis = now, updatedAtMillis = now)
        synchronized(items) { items += item }
        store.upsert(item)
        signal.send(Unit)
    }

    suspend fun enqueueFolder(uri: String, metadata: AndroidFolderTransferMetadata) {
        val now = nowMillis()
        val item = AndroidOutgoingQueueItem(
            UUID.randomUUID().toString(), uri, createdAtMillis = now, updatedAtMillis = now,
            folderSyncId = metadata.syncId, relativePath = metadata.relativePath, conflictCopy = metadata.conflictCopy,
        )
        synchronized(items) { items += item }
        store.upsert(item)
        signal.send(Unit)
    }

    fun wake() { signal.trySend(Unit) }

    override suspend fun cancel() {
        val item = active
        manager.cancel()
        if (item != null) {
            val cancelled = item.copy(state = "cancelled", updatedAtMillis = nowMillis())
            replace(cancelled)
            store.upsert(cancelled)
        }
    }

    private suspend fun workerLoop() {
        for (ignored in signal) {
            while (true) {
                var item = synchronized(items) {
                    items.filter { it.state == "queued" }.minByOrNull { it.createdAtMillis }
                } ?: break
                active = item
                item = item.copy(state = "sending", updatedAtMillis = nowMillis())
                replace(item)
                store.upsert(item)
                val resume = if (item.sha256 != null) AndroidOutgoingResumePoint(
                    item.transferId, item.sha256, item.acknowledgedOffset, item.nextChunkIndex,
                ) else null
                val folder = if (item.folderSyncId != null && item.relativePath != null)
                    AndroidFolderTransferMetadata(item.folderSyncId, item.relativePath, item.conflictCopy) else null
                manager.transfer(item.uri, resume, item.transferId, folder)
                when (val result = manager.state.value) {
                    is FileTransferState.Completed -> {
                        replace(item.copy(state = "completed", updatedAtMillis = nowMillis()))
                        store.delete(item.transferId)
                    }
                    is FileTransferState.Rejected, FileTransferState.Cancelled -> {
                        val terminal = item.copy(state = "cancelled", updatedAtMillis = nowMillis(), lastError = result.toString())
                        replace(terminal)
                        store.upsert(terminal)
                    }
                    else -> scheduleRetry(item, result.toString())
                }
                active = null
            }
        }
    }

    private suspend fun scheduleRetry(item: AndroidOutgoingQueueItem, error: String) {
        val resume = manager.lastResumePoint
        val attempts = item.attemptCount + 1
        var updated = item.copy(
            sha256 = resume?.sha256 ?: item.sha256,
            acknowledgedOffset = resume?.acknowledgedOffset ?: item.acknowledgedOffset,
            nextChunkIndex = resume?.nextChunkIndex ?: item.nextChunkIndex,
            attemptCount = attempts,
            state = if (attempts >= 5) "failed" else "waiting_retry",
            updatedAtMillis = nowMillis(),
            lastError = error,
        )
        replace(updated)
        store.upsert(updated)
        if (attempts >= 5) return
        delay(1000L shl (attempts - 1))
        updated = updated.copy(state = "queued", updatedAtMillis = nowMillis())
        replace(updated)
        store.upsert(updated)
    }

    private fun replace(item: AndroidOutgoingQueueItem) = synchronized(items) {
        val index = items.indexOfFirst { it.transferId == item.transferId }
        if (index >= 0) items[index] = item else items += item
    }
}
