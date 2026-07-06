package com.example.devicesync.core.discovery

sealed interface DiscoveryState {
    data object Idle : DiscoveryState
    data object Starting : DiscoveryState
    data object Searching : DiscoveryState
    data object Stopping : DiscoveryState
    data class PermissionRequired(val permissionName: String) : DiscoveryState
    data class Failed(val userMessage: String, val technicalCode: Int? = null) : DiscoveryState
}
