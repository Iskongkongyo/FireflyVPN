package xyz.a202132.app.util

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * One-release compatibility bridge for rawLink values written by the removed native static key.
 * New values are stored directly inside the SQLCipher database and never use this key.
 */
object LegacyRawLinkMigrationCrypto {
    private const val PREFIX = "enc:gcm:"
    private const val IV_BYTES = 12

    fun isLegacyValue(value: String): Boolean = value.startsWith(PREFIX)

    fun decryptOrNull(value: String): String? {
        if (!isLegacyValue(value)) return null
        val key = legacyKey()
        return try {
            val payload = Base64.decode(value.removePrefix(PREFIX), Base64.DEFAULT)
            require(payload.size > IV_BYTES + 16)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, payload.copyOfRange(0, IV_BYTES))
            )
            String(cipher.doFinal(payload.copyOfRange(IV_BYTES, payload.size)), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        } finally {
            key.fill(0)
        }
    }

    private fun legacyKey(): ByteArray {
        val partA = intArrayOf(
            0x99, 0x2B, 0xC5, 0xCE, 0xED, 0xB0, 0x64, 0x15,
            0x3B, 0x12, 0x4B, 0xCB, 0xDB, 0xDF, 0x8E, 0x6B
        )
        val partB = intArrayOf(
            0x57, 0xE0, 0x0D, 0x1F, 0x2F, 0x71, 0xAA, 0xC0,
            0xF9, 0xD3, 0x80, 0x15, 0x4D, 0x4B, 0x18, 0xF8
        )
        return ByteArray(16) { index -> (partA[index] xor partB[index] xor 0xA7).toByte() }
    }
}
