package com.example.devicesync.core.transfer

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class IncomingTransferCheckpoint(
    val transferId: String,
    val senderDeviceId: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val expectedSha256: String,
    val chunkSize: Int,
    val destinationUri: String,
    val receivedBytes: Long,
    val nextChunkIndex: Int,
    val startedAtMillis: Long,
    val lastActivityAtMillis: Long,
    val chunkSha256: List<String>,
)

interface IncomingTransferCheckpointStore {
    fun save(checkpoint: IncomingTransferCheckpoint)
    fun load(transferId: String): IncomingTransferCheckpoint?
    fun delete(transferId: String)
    fun expired(cutoffMillis: Long): List<IncomingTransferCheckpoint>
}

class JsonIncomingTransferCheckpointStore(context: Context) : IncomingTransferCheckpointStore {
    private val directory = File(context.filesDir, "transfers/incoming")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Synchronized
    override fun save(checkpoint: IncomingTransferCheckpoint) {
        directory.mkdirs()
        val destination = file(checkpoint.transferId)
        val temporary = File(destination.path + ".tmp")
        temporary.writeText(json.encodeToString(checkpoint))
        check(temporary.renameTo(destination) || run {
            destination.delete()
            temporary.renameTo(destination)
        }) { "Cannot atomically persist transfer checkpoint." }
    }

    @Synchronized
    override fun load(transferId: String): IncomingTransferCheckpoint? = runCatching {
        file(transferId).takeIf(File::exists)?.readText()?.let { json.decodeFromString<IncomingTransferCheckpoint>(it) }
    }.getOrNull()

    @Synchronized
    override fun delete(transferId: String) { file(transferId).delete() }

    @Synchronized
    override fun expired(cutoffMillis: Long): List<IncomingTransferCheckpoint> {
        if (!directory.exists()) return emptyList()
        return directory.listFiles { item -> item.extension == "json" }.orEmpty().mapNotNull { item ->
            runCatching { json.decodeFromString<IncomingTransferCheckpoint>(item.readText()) }.getOrElse {
                item.delete()
                null
            }
        }.filter { it.lastActivityAtMillis < cutoffMillis }
    }

    private fun file(transferId: String) = File(directory, "$transferId.json")
}
