package xyz.a202132.app.util.crypto

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

internal object CryptoV2Encoding {
    fun encodeBase64Url(value: ByteArray): String =
        Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    fun decodeBase64Url(value: String): ByteArray {
        require(value.isNotBlank() && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "Invalid Base64URL value"
        }
        return Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(SecureRandom()::nextBytes)

    fun sha256Hex(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { byte -> "%02x".format(byte) }
}

internal object CryptoV2Protocol {
    const val VERSION = 2
    const val ALGORITHM = "P256-HKDF-SHA256-A256GCM"
    const val HKDF_INFO = "FireflyVPN-Subscription-V2"
    const val DEVICE_ID_HEADER = "X-Firefly-Device-ID"
    const val VERSION_HEADER = "X-Firefly-Crypto-Version"
    const val CHALLENGE_HEADER = "X-Firefly-Challenge"
}
