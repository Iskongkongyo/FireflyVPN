package xyz.a202132.app.util

import android.util.Base64
import android.util.Log
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {
    private const val TAG = "CryptoUtils"
    private const val GCM_IV_LENGTH = 12  // GCM 推荐 12 字节 IV
    private const val GCM_TAG_LENGTH = 128 // 认证标签 128 位

    init {
        try {
            System.loadLibrary("native-lib")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library", e)
        }
    }

    // 从 Native 层获取混淆后的密钥 (C++ 实现)
    external fun getNativeKey(): String

    /**
     * 使用 AES-128-GCM 解密 Base64 字符串
     * 格式: Base64(IV + 密文 + AuthTag)
     * - IV: 前 12 字节
     * - 密文 + AuthTag: 剩余部分
     */
    fun decryptNodes(encryptedBase64: String): String {
        return try {
            val key = getNativeKey()
            Log.d(TAG, "Got key from native, length: ${key.length}")

            // 确保 key 是 16 字节
            val keyBytes = key.toByteArray(Charsets.UTF_8).copyOf(16)

            // 1. Base64 解码
            val combined = Base64.decode(encryptedBase64, Base64.DEFAULT)
            Log.d(TAG, "Base64 decoded bytes: ${combined.size}")

            if (combined.size < GCM_IV_LENGTH) {
                throw IllegalArgumentException("Invalid encrypted data: too short")
            }

            // 2. 提取 IV (前 12 字节)
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            
            // 3. 提取密文 + AuthTag (剩余部分)
            val cipherText = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

            // 4. AES-128-GCM 解密
            val secretKey = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

            val decryptedBytes = cipher.doFinal(cipherText)
            Log.d(TAG, "Decryption successful, output bytes: ${decryptedBytes.size}")

            // 5. 转成字符串
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed: ${e.message}", e)
            "Error: ${e.message}"
        }
    }
}
