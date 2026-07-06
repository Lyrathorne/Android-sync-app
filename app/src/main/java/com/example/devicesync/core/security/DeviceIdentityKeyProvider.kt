package com.example.devicesync.core.security

interface DeviceIdentityKeyProvider {
    suspend fun getOrCreatePublicKey(): ByteArray
    suspend fun getPublicKeyFingerprint(): String
    suspend fun sign(data: ByteArray): ByteArray
    suspend fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean
}
