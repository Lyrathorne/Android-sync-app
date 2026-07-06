package com.example.devicesync.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec

class AndroidKeystoreDeviceIdentityKeyProvider : DeviceIdentityKeyProvider {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override suspend fun getOrCreatePublicKey(): ByteArray {
        if (!keyStore.containsAlias(ALIAS)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
            generator.initialize(spec)
            generator.generateKeyPair()
        }
        return keyStore.getCertificate(ALIAS).publicKey.encoded
    }

    override suspend fun getPublicKeyFingerprint(): String {
        return SecurityEncoding.fingerprint(getOrCreatePublicKey())
    }

    override suspend fun sign(data: ByteArray): ByteArray {
        val privateKey = keyStore.getKey(ALIAS, null)
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        signature.initSign(privateKey as java.security.PrivateKey)
        signature.update(data)
        return signature.sign()
    }

    override suspend fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
        return runCatching {
            val parsed = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKey))
            val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
            verifier.initVerify(parsed)
            verifier.update(data)
            verifier.verify(signature)
        }.getOrDefault(false)
    }

    private companion object {
        const val ALIAS = "devicesync_identity_v1"
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    }
}
