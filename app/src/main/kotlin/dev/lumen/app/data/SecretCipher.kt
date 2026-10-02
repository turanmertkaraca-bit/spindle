package dev.lumen.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts small secrets at rest. The primary key lives in the Android Keystore
 * (hardware-backed where available) and never leaves it; if the platform
 * keystore is unavailable (e.g. some test hosts) it falls back to a device-local
 * AES key. Either way the stored blob is random-IV AES/GCM, never the cleartext.
 *
 * Payloads are base64(`iv || ciphertext`) prefixed with which key sealed them,
 * so a device whose keystore availability changes can still read old blobs when
 * possible.
 */
internal class SecretCipher(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("lumen.secret", Context.MODE_PRIVATE)

    /** Encrypt [plaintext], or null when neither key can be used. */
    fun encrypt(plaintext: String): String? {
        val hardware = runCatching { hardwareKey() }.getOrNull()
        if (hardware != null) {
            runCatching { seal(hardware, plaintext) }.getOrNull()?.let { return HW_PREFIX + it }
        }
        return runCatching { SW_PREFIX + seal(softwareKey(), plaintext) }.getOrNull()
    }

    /** Decrypt a blob produced by [encrypt], or null when it cannot be read. */
    fun decrypt(payload: String): String? = when {
        payload.startsWith(HW_PREFIX) ->
            runCatching { open(hardwareKey(), payload.substring(HW_PREFIX.length)) }.getOrNull()
        payload.startsWith(SW_PREFIX) ->
            runCatching { open(softwareKey(), payload.substring(SW_PREFIX.length)) }.getOrNull()
        else -> null
    }

    private fun seal(key: SecretKey, plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val bytes = cipher.iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun open(key: SecretKey, payload: String): String {
        val bytes = Base64.decode(payload, Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, IV_BYTES)
        val body = bytes.copyOfRange(IV_BYTES, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    private fun hardwareKey(): SecretKey {
        val keystore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keystore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    /** Fallback key persisted beside the secret; used only when keystore is broken. */
    private fun softwareKey(): SecretKey {
        prefs.getString(SW_KEY, null)?.let { stored ->
            return SecretKeySpec(Base64.decode(stored, Base64.NO_WRAP), "AES")
        }
        val generator = KeyGenerator.getInstance("AES")
        generator.init(256)
        val key = generator.generateKey()
        prefs.edit().putString(SW_KEY, Base64.encodeToString(key.encoded, Base64.NO_WRAP)).apply()
        return key
    }

    private companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "lumen.key.v1"
        const val HW_PREFIX = "k1:"
        const val SW_PREFIX = "s1:"
        const val SW_KEY = "fallbackKey"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
