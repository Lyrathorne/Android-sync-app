package com.example.devicesync.core.network

import com.example.devicesync.core.security.SecurityEncoding
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TlsSpkiPinTest {
    @Test
    fun pinMatchesOnlyExactSubjectPublicKeyInfo() {
        val spki = "test-server-subject-public-key-info".encodeToByteArray()
        val pin = SecurityEncoding.fingerprint(spki)

        assertTrue(matchesPinnedSpki(pin, spki))
        assertFalse(matchesPinnedSpki(pin, spki + 0))
        assertFalse(matchesPinnedSpki("not-base64url", spki))
    }
}
