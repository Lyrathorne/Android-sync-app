package com.example.devicesync.core.diagnostics

import android.content.Context
import android.os.Build
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class SupportBundleSummary(
    val appVersion: String,
    val protocolVersion: String,
    val capabilities: List<String>,
    val connectionState: String,
    val transport: String,
    val lastDisconnectReason: String,
    val permissionSummary: String,
    val backgroundRestriction: String,
)

class SupportBundleBuilder(private val context: Context) {
    fun preview(summary: SupportBundleSummary): String = buildString {
        appendLine("DeviceSync support bundle preview")
        appendLine("App: ${summary.appVersion}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Protocol: ${summary.protocolVersion}")
        appendLine("Capabilities: ${summary.capabilities.sorted().joinToString()}")
        appendLine("Connection: ${DiagnosticRedactor.sanitizeValue(summary.connectionState)}")
        appendLine("Transport: ${DiagnosticRedactor.sanitizeValue(summary.transport)}")
        appendLine("Last disconnect: ${DiagnosticRedactor.sanitizeValue(summary.lastDisconnectReason)}")
        appendLine("Permissions: ${DiagnosticRedactor.sanitizeValue(summary.permissionSummary)}")
        appendLine("Background: ${DiagnosticRedactor.sanitizeValue(summary.backgroundRestriction)}")
        appendLine("Diagnostic events: ${StructuredDiagnosticLog.persistedLines().size}")
        appendLine()
        append("The bundle excludes clipboard/file/notification contents, typed text, paths, secrets and private keys.")
    }

    fun build(summary: SupportBundleSummary): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("summary.txt"))
            zip.write(preview(summary).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("events.jsonl"))
            StructuredDiagnosticLog.persistedLines().forEach { line ->
                zip.write(line.toByteArray(Charsets.UTF_8))
                zip.write('\n'.code)
            }
            zip.closeEntry()
        }
        return output.toByteArray()
    }
}
