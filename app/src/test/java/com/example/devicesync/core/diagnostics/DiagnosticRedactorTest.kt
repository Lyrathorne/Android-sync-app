package com.example.devicesync.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticRedactorTest {
    @Test
    fun sensitiveAttributeNamesAreDropped() {
        val sanitized = DiagnosticRedactor.sanitizeAttributes(
            mapOf(
                "clipboardText" to "private",
                "filePath" to "C:\\Users\\Name\\photo.jpg",
                "notificationTitle" to "Bank",
                "duration" to "42",
                "transport" to "LAN",
            ),
        )

        assertEquals(mapOf("duration" to "42", "transport" to "LAN"), sanitized)
    }

    @Test
    fun addressesPathsEmailsAndLongTokensAreRedacted() {
        val token = "A".repeat(48)
        val value = DiagnosticRedactor.sanitizeValue(
            "host=192.168.1.25 user=a@example.com path=C:\\Users\\Gleb\\secret.txt token=$token",
        )

        assertTrue(value.contains("[IP]"))
        assertTrue(value.contains("[EMAIL]"))
        assertTrue(value.contains("[PATH]"))
        assertTrue(value.contains("[TOKEN]"))
        assertFalse(value.contains("192.168.1.25"))
        assertFalse(value.contains("secret.txt"))
        assertFalse(value.contains(token))
    }

    @Test
    fun eventRejectsUnstableNamesAndNegativeCounters() {
        runCatching {
            DiagnosticEvent(domain = DiagnosticDomain.FILE, name = "file complete")
        }.onSuccess { throw AssertionError("Invalid event name was accepted") }
        runCatching {
            DiagnosticEvent(domain = DiagnosticDomain.FILE, name = "FILE_COMPLETE", byteCount = -1)
        }.onSuccess { throw AssertionError("Negative byte count was accepted") }
    }
}
