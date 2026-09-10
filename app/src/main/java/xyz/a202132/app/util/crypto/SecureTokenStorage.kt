package xyz.a202132.app.util.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class KeystoreAesStorage(
    context: Context,
    preferencesName: String,
    private val keyAlias: String
) {
    private val preferences = context.applicationContext
        .getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    fun put(name: String, value: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value)
        val payload = listOf(
            FORMAT_VERSION,
            CryptoV2Encoding.encodeBase64Url(cipher.iv),
            CryptoV2Encoding.encodeBase64Url(encrypted)
        ).joinToString(".")
        preferences.edit().putString(name, payload).apply()
    }

    fun get(name: String): ByteArray? {
        val payload = preferences.getString(name, null) ?: return null
        return try {
            val parts = payload.split('.')
            require(parts.size == 3 && parts[0] == FORMAT_VERSION)
            val iv = CryptoV2Encoding.decodeBase64Url(parts[1])
            require(iv.size == GCM_IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(CryptoV2Encoding.decodeBase64Url(parts[2]))
        } catch (_: Exception) {
            preferences.edit().remove(name).apply()
            null
        }
    }

    fun remove(name: String) {
        preferences.edit().remove(name).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_VERSION = "v1"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}

class SecureTokenStorage(context: Context) {
    private val storage = KeystoreAesStorage(
        context = context,
        preferencesName = "firefly_crypto_v2_token",
        keyAlias = "fireflyvpn_crypto_v2_token_wrap"
    )

    fun read(): String? = storage.get(TOKEN_KEY)?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }

    fun write(token: String) {
        require(token.matches(Regex("^[A-Za-z0-9_-]{40,128}$"))) { "Invalid device token" }
        storage.put(TOKEN_KEY, token.toByteArray(Charsets.UTF_8))
    }

    fun clear() = storage.remove(TOKEN_KEY)

    private companion object {
        const val TOKEN_KEY = "device_token"
    }
}
