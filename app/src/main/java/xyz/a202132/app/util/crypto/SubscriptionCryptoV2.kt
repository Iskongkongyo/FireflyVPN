package xyz.a202132.app.util.crypto

import org.json.JSONObject
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SubscriptionCryptoV2 {
    fun decrypt(
        responseJson: String,
        privateKey: PrivateKey,
        expectedDeviceId: String,
        expectedRequestId: String,
        expectedSubscriptionId: String? = null,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000
    ): String {
        val response = JSONObject(responseJson)
        require(response.getInt("version") == CryptoV2Protocol.VERSION) { "Unsupported crypto version" }
        require(response.getString("algorithm") == CryptoV2Protocol.ALGORITHM) { "Unsupported algorithm" }

        val deviceId = response.getString("deviceId")
        val subscriptionId = response.getString("subscriptionId")
        val requestId = response.getString("requestId")
        val issuedAt = response.getLong("issuedAt")
        val expiresAt = response.getLong("expiresAt")
        require(deviceId == expectedDeviceId) { "Device mismatch" }
        require(requestId == expectedRequestId) { "Response challenge mismatch" }
        require(expectedSubscriptionId == null || subscriptionId == expectedSubscriptionId) {
            "Subscription mismatch"
        }
        require(issuedAt <= nowEpochSeconds + MAX_CLOCK_SKEW_SECONDS) { "Response issued in the future" }
        require(expiresAt >= nowEpochSeconds) { "Response expired" }
        require(expiresAt > issuedAt && expiresAt - issuedAt <= MAX_RESPONSE_LIFETIME_SECONDS) {
            "Invalid response lifetime"
        }
        require(CryptoV2Encoding.decodeBase64Url(requestId).size == CHALLENGE_BYTES) {
            "Invalid response challenge"
        }

        val salt = CryptoV2Encoding.decodeBase64Url(response.getString("salt"))
        val nonce = CryptoV2Encoding.decodeBase64Url(response.getString("nonce"))
        val ciphertext = CryptoV2Encoding.decodeBase64Url(response.getString("ciphertext"))
        require(salt.size == SALT_BYTES) { "Invalid HKDF salt" }
        require(nonce.size == NONCE_BYTES) { "Invalid AES-GCM nonce" }
        require(ciphertext.size >= GCM_TAG_BYTES) { "Invalid ciphertext" }

        val ephemeralPublicKey = importP256PublicKey(response.getString("ephemeralPublicKey"))
        val sharedSecret = KeyAgreement.getInstance("ECDH").run {
            init(privateKey)
            doPhase(ephemeralPublicKey, true)
            generateSecret()
        }
        val aesKey = hkdfSha256(
            inputKeyMaterial = sharedSecret,
            salt = salt,
            info = CryptoV2Protocol.HKDF_INFO.toByteArray(Charsets.UTF_8),
            outputLength = AES_KEY_BYTES
        )
        sharedSecret.fill(0)

        return try {
            val aad = canonicalAad(deviceId, subscriptionId, requestId, issuedAt, expiresAt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(aesKey, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, nonce)
            )
            cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } finally {
            aesKey.fill(0)
        }
    }

    private fun importP256PublicKey(value: String): ECPublicKey {
        val encoded = CryptoV2Encoding.decodeBase64Url(value)
        require(encoded.size in 80..120) { "Invalid ephemeral public key" }
        return KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(encoded)) as? ECPublicKey
            ?: throw IllegalArgumentException("Invalid EC public key")
    }

    private fun canonicalAad(
        deviceId: String,
        subscriptionId: String,
        requestId: String,
        issuedAt: Long,
        expiresAt: Long
    ): String = listOf(
        CryptoV2Protocol.VERSION.toString(),
        CryptoV2Protocol.ALGORITHM,
        deviceId,
        subscriptionId,
        requestId,
        issuedAt.toString(),
        expiresAt.toString()
    ).joinToString("\n")

    private fun hkdfSha256(
        inputKeyMaterial: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        outputLength: Int
    ): ByteArray {
        require(outputLength in 1..(255 * SHA256_BYTES))
        val extract = Mac.getInstance("HmacSHA256")
        extract.init(SecretKeySpec(salt, "HmacSHA256"))
        val pseudoRandomKey = extract.doFinal(inputKeyMaterial)
        return try {
            val result = ByteArray(outputLength)
            var previous = ByteArray(0)
            var offset = 0
            var counter = 1
            while (offset < outputLength) {
                val expand = Mac.getInstance("HmacSHA256")
                expand.init(SecretKeySpec(pseudoRandomKey, "HmacSHA256"))
                expand.update(previous)
                expand.update(info)
                expand.update(counter.toByte())
                previous = expand.doFinal()
                val count = minOf(previous.size, outputLength - offset)
                previous.copyInto(result, offset, 0, count)
                offset += count
                counter += 1
            }
            result
        } finally {
            pseudoRandomKey.fill(0)
        }
    }

    companion object {
        fun newChallenge(): String = CryptoV2Encoding.encodeBase64Url(
            CryptoV2Encoding.randomBytes(CHALLENGE_BYTES)
        )

        private const val CHALLENGE_BYTES = 16
        private const val SALT_BYTES = 32
        private const val NONCE_BYTES = 12
        private const val GCM_TAG_BYTES = 16
        private const val GCM_TAG_BITS = 128
        private const val AES_KEY_BYTES = 32
        private const val SHA256_BYTES = 32
        private const val MAX_CLOCK_SKEW_SECONDS = 60L
        private const val MAX_RESPONSE_LIFETIME_SECONDS = 600L
    }
}
