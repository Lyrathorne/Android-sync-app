package com.example.devicesync.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityEncodingTest {
    @Test
    fun base64Url_roundTripsWithoutPadding() {
        val encoded = Base64Url.encode(byteArrayOf(0, 1, 2, 3))

        assertFalse(encoded.contains("="))
        assertEquals(listOf<Byte>(0, 1, 2, 3), Base64Url.decode(encoded).toList())
    }

    @Test
    fun transcriptAndHmac_matchSharedVector() {
        val secret = Base64Url.decode("QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl8")
        val transcript = TranscriptBuilder.pairingRequest(
            sessionId = "pair-test",
            windowsDeviceId = "windows-test",
            androidDeviceId = "android-test",
            windowsFingerprint = "YvXFIt0M1gFOAKkY-_p23sih4W_t9CcrvjMIAEzgBIs",
            androidFingerprint = "alipEjFnUgu58tJFM3B0sakZlgxX3STzwQHbslRboxU",
            androidNonce = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
        )

        assertEquals(
            "AAAAE0RldmljZVN5bmNQYWlyaW5nVjEAAAAJcGFpci10ZXN0AAAADHdpbmRvd3MtdGVzdAAAAAxhbmRyb2lkLXRlc3QAAAArWXZYRkl0ME0xZ0ZPQUtrWS1fcDIzc2loNFdfdDlDY3J2ak1JQUV6Z0JJcwAAACthbGlwRWpGblVndTU4dEpGTTNCMHNha1psZ3hYM1NUendRSGJzbFJib3hVAAAAK0FBRUNBd1FGQmdjSUNRb0xEQTBPRHhBUkVoTVVGUllYR0JrYUd4d2RIaDg",
            Base64Url.encode(transcript),
        )
        assertEquals(
            "WRaXaNsmNgqpZCKuGh0NX5_qWD0958ZO-V4q5w7YGV0",
            Base64Url.encode(SecurityEncoding.hmacSha256(secret, transcript)),
        )
    }

    @Test
    fun verificationCode_hasSixDigitsAndMatchesVector() {
        val code = SecurityEncoding.verificationCode(
            "DeviceSyncPairingChallengeV1",
            "pair-test",
            "windows-test",
            "android-test",
            "YvXFIt0M1gFOAKkY-_p23sih4W_t9CcrvjMIAEzgBIs",
            "alipEjFnUgu58tJFM3B0sakZlgxX3STzwQHbslRboxU",
            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
            "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8",
        )

        assertEquals("757216", code)
        assertEquals(6, code.length)
        assertTrue(code.all { it.isDigit() })
    }
}
