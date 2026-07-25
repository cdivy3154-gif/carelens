package com.carelens.app

import android.content.Context
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Stores only an encrypted, randomly generated vault key. Medical documents are not stored until
 * the document vault is implemented. The user secret is never written to disk.
 */
internal class VaultStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun createVault(secret: String) {
        require(VaultSecretPolicy.isValidPinOrPassword(secret)) { "Secret does not meet requirements." }

        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(GCM_IV_BYTES).also(random::nextBytes)
        val vaultKey = ByteArray(VAULT_KEY_BYTES).also(random::nextBytes)
        val wrappingKey = deriveWrappingKey(secret.toCharArray(), salt)
        val ciphertext = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, wrappingKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            doFinal(vaultKey)
        }

        preferences.edit()
            .putString(SALT_KEY, encode(salt))
            .putString(IV_KEY, encode(iv))
            .putString(ENCRYPTED_VAULT_KEY, encode(ciphertext))
            .commit()

        vaultKey.fill(0)
        salt.fill(0)
        iv.fill(0)
    }

    private fun deriveWrappingKey(secret: CharArray, salt: ByteArray): SecretKeySpec {
        val keySpec = PBEKeySpec(secret, salt, PBKDF2_ITERATIONS, WRAPPING_KEY_BITS)
        try {
            val derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(keySpec)
                .encoded
            return SecretKeySpec(derived, "AES")
        } finally {
            keySpec.clearPassword()
            secret.fill('\u0000')
        }
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private companion object {
        const val PREFERENCES_NAME = "carelens_vault"
        const val SALT_KEY = "salt"
        const val IV_KEY = "iv"
        const val ENCRYPTED_VAULT_KEY = "encrypted_vault_key"
        const val SALT_BYTES = 16
        const val GCM_IV_BYTES = 12
        const val VAULT_KEY_BYTES = 32
        const val GCM_TAG_BITS = 128
        const val WRAPPING_KEY_BITS = 256
        const val PBKDF2_ITERATIONS = 310_000
        val random = SecureRandom()
    }
}

internal object VaultSecretPolicy {
    fun isValid(method: LockMethod, secret: String): Boolean = when (method) {
        LockMethod.PIN -> secret.length >= 6 && secret.all(Char::isDigit)
        LockMethod.PASSWORD -> secret.length >= 10
    }

    fun isValidPinOrPassword(secret: String): Boolean =
        (secret.length >= 6 && secret.all(Char::isDigit)) || secret.length >= 10
}
