package com.example.devicesync.core.security

import java.util.Base64

object Base64Url {
    fun encode(bytes: ByteArray): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun decode(value: String): ByteArray {
        return Base64.getUrlDecoder().decode(value)
    }
}
