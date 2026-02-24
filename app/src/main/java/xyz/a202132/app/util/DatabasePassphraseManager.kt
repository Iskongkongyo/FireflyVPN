package xyz.a202132.app.util

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object DatabasePassphraseManager {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "firefly_sqlcipher_wrap_key_v1"
    private const val PREFS_NAME = "secure_db_prefs"
    private const val PREF_WRAPPED_DB_KEY = "wrapped_sqlcipher_db_key_v1"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val DB_KEY_LENGTH_BYTES = 32

    fun getOrCreatePassphrase(context: Context): ByteArray {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val wrapped = prefs.getString(PREF_WRAPPED_DB_KEY, null)
        if (!wrapped.isNullOrBlank()) {
            try {
                return unwrapPassphrase(wrapped)
            } catch (_: Exception) {
                // Keystore or wrapped blob may become invalid after system restore/reset.
                prefs.edit().remove(PREF_WRAPPED_DB_KEY).apply()
                deleteKeyIfExists()
            }
        }

        val dbKey = ByteArray(DB_KEY_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        val wrappedBlob = wrapPassphrase(dbKey)
        prefs.edit().putString(PREF_WRAPPED_DB_KEY, wrappedBlob).apply()
        return dbKey.copyOf()
    }

    private fun wrapPassphrase(passphrase: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrapKey())
        val encrypted = cipher.doFinal(passphrase)
        val payload = ByteArray(GCM_IV_LENGTH + encrypted.size)
        System.arraycopy(cipher.iv, 0, payload, 0, GCM_IV_LENGTH)
        System.arraycopy(encrypted, 0, payload, GCM_IV_LENGTH, encrypted.size)
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun unwrapPassphrase(base64Payload: String): ByteArray {
        val payload = Base64.decode(base64Payload, Base64.DEFAULT)
        require(payload.size > GCM_IV_LENGTH) { "Invalid wrapped DB key payload" }

        val iv = payload.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = payload.copyOfRange(GCM_IV_LENGTH, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateWrapKey(),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        )
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateWrapKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun deleteKeyIfExists() {
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
            }
        }
    }
}
