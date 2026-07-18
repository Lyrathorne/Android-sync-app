package com.example.devicesync.core.diagnostics

import java.time.Instant
import java.util.UUID

enum class DiagnosticDomain {
    TRANSPORT,
    SESSION,
    CLIPBOARD,
    FILE,
    CATALOG,
    NOTIFICATION,
    IME,
    APPLICATION,
}

enum class DiagnosticLevel { INFO, WARNING, ERROR }

data class DiagnosticEvent(
    val timestampUtc: String = Instant.now().toString(),
    val domain: DiagnosticDomain,
    val name: String,
    val level: DiagnosticLevel = DiagnosticLevel.INFO,
    val correlationId: String? = null,
    val durationMs: Long? = null,
    val byteCount: Long? = null,
    val errorCode: String? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(name.matches(STABLE_NAME))
        require(correlationId == null || correlationId.length <= 80)
        require(durationMs == null || durationMs >= 0)
        require(byteCount == null || byteCount >= 0)
    }

    companion object {
        private val STABLE_NAME = Regex("[A-Z][A-Z0-9_]{2,63}")
        fun correlationId(): String = UUID.randomUUID().toString()
    }
}
