package com.example.devicesync.feature.add_device

data class ManualConnectionValidationResult(
    val ipError: ManualConnectionError? = null,
    val portError: ManualConnectionError? = null,
) {
    val isValid: Boolean = ipError == null && portError == null
}

object ManualConnectionValidator {
    fun validate(ipAddress: String, port: String): ManualConnectionValidationResult {
        val ipError = if (ipAddress.isBlank()) ManualConnectionError.EMPTY_IP else null
        val portNumber = port.toIntOrNull()
        val portError = when {
            portNumber == null -> ManualConnectionError.PORT_NOT_NUMBER
            portNumber !in 1..65535 -> ManualConnectionError.PORT_OUT_OF_RANGE
            else -> null
        }

        return ManualConnectionValidationResult(
            ipError = ipError,
            portError = portError,
        )
    }
}
