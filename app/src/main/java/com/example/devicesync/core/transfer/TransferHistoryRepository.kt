package com.example.devicesync.core.transfer

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class TransferHistoryEntry(
    val transferId: String,
    val direction: String,
    val fileName: String,
    val status: String,
    val sizeBytes: Long? = null,
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val detail: String? = null,
)

class TransferHistoryRepository(context: Context) {
    private val path = File(context.filesDir, "transfers/history.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<TransferHistoryEntry>> = _entries.asStateFlow()

    @Synchronized
    fun record(entry: TransferHistoryEntry) {
        val updated = (listOf(entry) + _entries.value.filterNot { it.transferId == entry.transferId })
            .sortedByDescending(TransferHistoryEntry::updatedAtMillis)
            .take(MaximumEntries)
        _entries.value = updated
        path.parentFile?.mkdirs()
        val temporary = File(path.path + ".tmp")
        temporary.writeText(json.encodeToString(updated))
        check(temporary.renameTo(path) || run { path.delete(); temporary.renameTo(path) })
    }

    private fun load(): List<TransferHistoryEntry> = runCatching {
        if (!path.exists()) emptyList()
        else json.decodeFromString<List<TransferHistoryEntry>>(path.readText())
            .sortedByDescending(TransferHistoryEntry::updatedAtMillis)
            .take(MaximumEntries)
    }.getOrDefault(emptyList())

    private companion object {
        const val MaximumEntries = 50
    }
}
