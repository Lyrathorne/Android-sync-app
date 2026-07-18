package com.example.devicesync.core.diagnostics

import android.content.Context
import android.util.Log
import java.io.File
import java.util.ArrayDeque
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object StructuredDiagnosticLog {
    private const val TAG = "DeviceSyncDiagnostic"
    private const val MAX_RECENT = 250
    private const val MAX_FILE_BYTES = 256 * 1024L
    private const val MAX_FILES = 4
    private val lock = Any()
    private val recent = ArrayDeque<String>()
    private val json = Json { encodeDefaults = false }
    @Volatile private var directory: File? = null

    fun initialize(context: Context) {
        directory = File(context.filesDir, "diagnostics").apply { mkdirs() }
    }

    fun record(event: DiagnosticEvent) {
        val sanitized = event.copy(
            correlationId = event.correlationId?.let(DiagnosticRedactor::sanitizeValue),
            errorCode = event.errorCode?.let(DiagnosticRedactor::sanitizeValue),
            attributes = DiagnosticRedactor.sanitizeAttributes(event.attributes),
        )
        val line = serialize(sanitized)
        synchronized(lock) {
            recent.addLast(line)
            while (recent.size > MAX_RECENT) recent.removeFirst()
            appendRotating(line)
        }
        runCatching {
            when (event.level) {
                DiagnosticLevel.INFO -> Log.i(TAG, line)
                DiagnosticLevel.WARNING -> Log.w(TAG, line)
                DiagnosticLevel.ERROR -> Log.e(TAG, line)
            }
        }
    }

    fun recentLines(limit: Int = 100): List<String> = synchronized(lock) {
        recent.toList().takeLast(limit.coerceIn(1, MAX_RECENT))
    }

    fun persistedLines(): List<String> = synchronized(lock) {
        val dir = directory ?: return emptyList()
        (0 until MAX_FILES).asSequence()
            .map { File(dir, "events-$it.jsonl") }
            .filter(File::isFile)
            .sortedByDescending(File::lastModified)
            .flatMap { file -> file.useLines { it.toList() }.asSequence() }
            .take(MAX_RECENT * 4)
            .toList()
    }

    private fun appendRotating(line: String) {
        val dir = directory ?: return
        val active = File(dir, "events-0.jsonl")
        if (active.exists() && active.length() + line.length + 1 > MAX_FILE_BYTES) {
            File(dir, "events-${MAX_FILES - 1}.jsonl").delete()
            for (index in MAX_FILES - 2 downTo 0) {
                val source = File(dir, "events-$index.jsonl")
                if (source.exists()) source.renameTo(File(dir, "events-${index + 1}.jsonl"))
            }
        }
        active.appendText(line + "\n", Charsets.UTF_8)
    }

    private fun serialize(event: DiagnosticEvent): String = buildJsonObject {
        put("timestampUtc", event.timestampUtc)
        put("domain", event.domain.name.lowercase())
        put("name", event.name)
        put("level", event.level.name.lowercase())
        event.correlationId?.let { put("correlationId", it) }
        event.durationMs?.let { put("durationMs", it) }
        event.byteCount?.let { put("byteCount", it) }
        event.errorCode?.let { put("errorCode", it) }
        event.attributes.forEach { (key, value) -> put(key, value) }
    }.let { json.encodeToString(it) }
}
