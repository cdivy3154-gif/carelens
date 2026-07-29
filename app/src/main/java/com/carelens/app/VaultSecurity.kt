package com.carelens.app

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal enum class LockMethod { PIN, PASSWORD }

internal class VaultSession internal constructor(rawKey: ByteArray) {
    private val rawKey = rawKey.copyOf()
    internal val key: SecretKey get() = SecretKeySpec(rawKey, "AES")

    fun encrypt(plain: ByteArray): EncryptedPayload = encryptWith(key, plain)
    fun decrypt(payload: EncryptedPayload): ByteArray = decryptWith(key, payload)
    fun clear() = rawKey.fill(0)
}

internal data class EncryptedPayload(val iv: ByteArray, val ciphertext: ByteArray)

/** Local-only key hierarchy. No user secret or recovery phrase is stored. */
internal class VaultStore(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun hasVault(): Boolean = preferences.contains(APP_WRAPPED_KEY)

    fun createVault(appSecret: String, recoveryPhrase: String): VaultSession {
        require(VaultSecretPolicy.isValidPinOrPassword(appSecret))
        require(VaultSecretPolicy.isValidRecoveryPhrase(recoveryPhrase))
        val rawKey = randomBytes(VAULT_KEY_BYTES)
        val session = VaultSession(rawKey)
        rawKey.fill(0)
        storeWrapped(APP, appSecret, session.key.encoded)
        storeWrapped(RECOVERY, recoveryPhrase.trim().lowercase(), session.key.encoded)
        return session
    }

    fun unlock(appSecret: String): VaultSession? = unwrap(APP, appSecret)

    fun recover(recoveryPhrase: String, newAppSecret: String): VaultSession? {
        if (!VaultSecretPolicy.isValidPinOrPassword(newAppSecret)) return null
        val session = unwrap(RECOVERY, recoveryPhrase.trim().lowercase()) ?: return null
        storeWrapped(APP, newAppSecret, session.key.encoded)
        return session
    }

    fun enableBiometric(session: VaultSession): Boolean = runCatching {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        val builder = KeyGenParameterSpec.Builder(
            BIOMETRIC_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG,
            )
        }
        keyGenerator.init(builder.build())
        val key = keyGenerator.generateKey()
        val payload = encryptWith(key, session.key.encoded)
        preferences.edit()
            .putString(BIOMETRIC_IV, encode(payload.iv))
            .putString(BIOMETRIC_WRAPPED_KEY, encode(payload.ciphertext))
            .apply()
        true
    }.getOrDefault(false)

    fun biometricCipherForUnlock(): Cipher? = runCatching {
        val iv = decode(preferences.getString(BIOMETRIC_IV, null) ?: return null)
        val key = keyStore().getKey(BIOMETRIC_KEY_ALIAS, null) as? SecretKey ?: return null
        Cipher.getInstance(AES_GCM).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
    }.getOrNull()

    fun completeBiometricUnlock(cipher: Cipher): VaultSession? = runCatching {
        val encrypted = decode(preferences.getString(BIOMETRIC_WRAPPED_KEY, null) ?: return null)
        val raw = cipher.doFinal(encrypted)
        VaultSession(raw).also { raw.fill(0) }
    }.getOrNull()

    fun biometricIsEnabled(): Boolean = preferences.contains(BIOMETRIC_WRAPPED_KEY)

    fun wipeVault() {
        runCatching { keyStore().deleteEntry(BIOMETRIC_KEY_ALIAS) }
        preferences.edit().clear().commit()
        // Vault contents are encrypted with a key that has just been destroyed. Removing the
        // ciphertext afterwards keeps the app-private storage tidy without relying on insecure
        // flash "secure deletion" claims.
        File(context.filesDir, "carelens_vault").deleteRecursively()
    }

    private fun storeWrapped(slot: String, secret: String, rawKey: ByteArray) {
        val salt = randomBytes(SALT_BYTES)
        val derived = deriveKey(secret.toCharArray(), salt)
        val payload = encryptWith(derived, rawKey)
        derived.encoded.fill(0)
        preferences.edit()
            .putString("${slot}_salt", encode(salt))
            .putString("${slot}_iv", encode(payload.iv))
            .putString("${slot}_key", encode(payload.ciphertext))
            .commit()
        salt.fill(0)
    }

    private fun unwrap(slot: String, secret: String): VaultSession? = runCatching {
        val salt = decode(preferences.getString("${slot}_salt", null) ?: return null)
        val iv = decode(preferences.getString("${slot}_iv", null) ?: return null)
        val cipherText = decode(preferences.getString("${slot}_key", null) ?: return null)
        val derived = deriveKey(secret.toCharArray(), salt)
        val raw = decryptWith(derived, EncryptedPayload(iv, cipherText))
        derived.encoded.fill(0)
        VaultSession(raw).also { raw.fill(0) }
    }.getOrNull()

    private fun deriveKey(secret: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(secret, salt, PBKDF2_ITERATIONS, KEY_BITS)
        return try {
            val bytes = SecretKeyFactory.getInstance(PBKDF2)
                .generateSecret(spec)
                .encoded
            SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
            secret.fill('\u0000')
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private companion object {
        const val PREFERENCES_NAME = "carelens_vault"
        const val APP = "app"
        const val RECOVERY = "recovery"
        const val APP_WRAPPED_KEY = "app_key"
        const val BIOMETRIC_KEY_ALIAS = "carelens_biometric_key"
        const val BIOMETRIC_IV = "biometric_iv"
        const val BIOMETRIC_WRAPPED_KEY = "biometric_wrapped_key"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val AES_GCM = "AES/GCM/NoPadding"
        const val PBKDF2 = "PBKDF2WithHmacSHA256"
        const val SALT_BYTES = 16
        const val VAULT_KEY_BYTES = 32
        const val KEY_BITS = 256
        const val GCM_TAG_BITS = 128
        const val PBKDF2_ITERATIONS = 310_000
    }
}

internal object VaultSecretPolicy {
    private val recoveryWords = listOf(
        "amber", "anchor", "apple", "bamboo", "breeze", "candle", "cedar", "coral",
        "dawn", "delta", "ember", "fern", "globe", "harbor", "indigo", "jasmine",
        "lotus", "maple", "meadow", "meteor", "mango", "nectar", "ocean", "orchid",
        "pearl", "quartz", "river", "saffron", "sandal", "sunrise", "tulip", "violet",
    )

    fun isValid(method: LockMethod, secret: String): Boolean = when (method) {
        LockMethod.PIN -> secret.length >= 6 && secret.all(Char::isDigit)
        LockMethod.PASSWORD -> secret.length >= 10
    }

    fun isValidPinOrPassword(secret: String): Boolean =
        (secret.length >= 6 && secret.all(Char::isDigit)) || secret.length >= 10

    fun generateRecoveryPhrase(): String = List(12) { recoveryWords[randomIndex()] }.joinToString(" ")

    fun isValidRecoveryPhrase(phrase: String): Boolean =
        phrase.trim().lowercase().split(Regex("\\s+")).let { words ->
            words.size == 12 && words.all(recoveryWords::contains)
        }
}

private val secureRandom = SecureRandom()

private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(secureRandom::nextBytes)

private fun randomIndex(): Int = secureRandom.nextInt(32)

private fun encryptWith(key: SecretKey, plain: ByteArray): EncryptedPayload {
    val iv = randomBytes(12)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
    }
    return EncryptedPayload(iv, cipher.doFinal(plain))
}

private fun decryptWith(key: SecretKey, payload: EncryptedPayload): ByteArray =
    Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, payload.iv))
        doFinal(payload.ciphertext)
    }

private fun encode(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)

private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
