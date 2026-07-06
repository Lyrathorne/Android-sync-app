package com.example.devicesync.core.discovery

import java.time.Instant

data class DiscoveryDiagnostics(
    val foundServices: Int = 0,
    val resolvedServices: Int = 0,
    val lastError: String? = null,
    val lastCallback: String? = null,
    val activeServiceType: String = DEVICESYNC_SERVICE_TYPE,
    val startedAt: Instant? = null,
)
