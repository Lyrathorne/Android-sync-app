package com.example.devicesync.core.settings

interface DeviceIdentityRepository {
    suspend fun getOrCreateDeviceId(): String
    suspend fun getDeviceName(): String
    suspend fun updateDeviceName(name: String)
}
