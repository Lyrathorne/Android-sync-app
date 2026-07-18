package com.example.devicesync.core.network

import android.util.Log
import com.example.devicesync.BuildConfig
import com.example.devicesync.core.diagnostics.DiagnosticDomain
import com.example.devicesync.core.diagnostics.DiagnosticEvent
import com.example.devicesync.core.diagnostics.DiagnosticLevel
import com.example.devicesync.core.diagnostics.DiagnosticRedactor
import com.example.devicesync.core.diagnostics.StructuredDiagnosticLog

object NetworkLogger {
    private const val Tag = "DeviceSyncNetwork"

    fun info(message: String) {
        val safeMessage = DiagnosticRedactor.sanitizeValue(message)
        StructuredDiagnosticLog.record(
            DiagnosticEvent(
                domain = domain(message),
                name = stableName(message),
                attributes = mapOf("detail" to safeMessage),
            ),
        )
        if (BuildConfig.DEBUG) runCatching { Log.i(Tag, safeMessage) }
    }

    fun error(message: String, throwable: Throwable? = null) {
        val safeMessage = DiagnosticRedactor.sanitizeValue(message)
        StructuredDiagnosticLog.record(
            DiagnosticEvent(
                domain = domain(message),
                name = stableName(message),
                level = DiagnosticLevel.ERROR,
                errorCode = throwable?.javaClass?.simpleName ?: "UNKNOWN",
                attributes = mapOf("detail" to safeMessage),
            ),
        )
        if (BuildConfig.DEBUG) runCatching { Log.e(Tag, safeMessage) }
    }

    fun event(event: DiagnosticEvent) = StructuredDiagnosticLog.record(event)

    private fun domain(message: String): DiagnosticDomain = when {
        message.contains("clipboard", true) -> DiagnosticDomain.CLIPBOARD
        message.contains("file", true) || message.contains("transfer", true) -> DiagnosticDomain.FILE
        message.contains("catalog", true) || message.contains("thumbnail", true) -> DiagnosticDomain.CATALOG
        message.contains("notification", true) -> DiagnosticDomain.NOTIFICATION
        message.contains("IME", true) || message.contains("keyboard", true) -> DiagnosticDomain.IME
        message.contains("session", true) || message.contains("handshake", true) ||
            message.contains("auth", true) -> DiagnosticDomain.SESSION
        else -> DiagnosticDomain.TRANSPORT
    }

    private fun stableName(message: String): String {
        val token = message.trim().takeWhile { it.isLetterOrDigit() || it == '_' }
            .uppercase().replace(Regex("[^A-Z0-9_]"), "_").take(48)
        return token.takeIf { it.length >= 3 && it.first().isLetter() } ?: "NETWORK_EVENT"
    }
}
