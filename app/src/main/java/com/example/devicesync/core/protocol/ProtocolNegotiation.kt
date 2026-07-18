package com.example.devicesync.core.protocol

import com.example.devicesync.core.network.PROTOCOL_MAX_VERSION
import com.example.devicesync.core.network.PROTOCOL_MIN_VERSION
import com.example.devicesync.core.network.SupportedCapabilities

object ProtocolVersionNegotiator {
    fun negotiate(remoteLegacyVersion: Int, remoteMin: Int? = null, remoteMax: Int? = null): Int? {
        val min = remoteMin ?: remoteLegacyVersion
        val max = remoteMax ?: remoteLegacyVersion
        if (min <= 0 || max < min) return null
        val commonMin = maxOf(PROTOCOL_MIN_VERSION, min)
        val commonMax = minOf(PROTOCOL_MAX_VERSION, max)
        return commonMax.takeIf { it >= commonMin }
    }
}

object ProtocolErrorCodes {
    const val UNSUPPORTED_PROTOCOL_VERSION = "UNSUPPORTED_PROTOCOL_VERSION"
    const val CAPABILITY_REQUIRED = "CAPABILITY_REQUIRED"
    const val PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE"
    const val INVALID_MESSAGE = "INVALID_MESSAGE"
    const val AUTHENTICATION_REQUIRED = "AUTHENTICATION_REQUIRED"
}

object CapabilityNegotiator {
    fun intersect(remoteCapabilities: Collection<String>): List<String> =
        SupportedCapabilities.values.filter(remoteCapabilities::contains)

    fun require(remoteCapabilities: Collection<String>, capability: String) {
        if (capability !in remoteCapabilities) {
            throw IllegalStateException("${ProtocolErrorCodes.CAPABILITY_REQUIRED}:$capability")
        }
    }
}
