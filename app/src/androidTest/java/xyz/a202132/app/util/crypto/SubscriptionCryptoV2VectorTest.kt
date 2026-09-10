package xyz.a202132.app.util.crypto

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec

class SubscriptionCryptoV2VectorTest {
    @Test
    fun decryptsCrossPlatformVectorAndRejectsWrongChallenge() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val vector = context.assets.open("crypto-v2-test-vector.json")
            .bufferedReader(Charsets.UTF_8)
            .use { JSONObject(it.readText()) }
        val input = vector.getJSONObject("input")
        val expected = vector.getJSONObject("expected")
        val privateKey = KeyFactory.getInstance("EC").generatePrivate(
            PKCS8EncodedKeySpec(CryptoV2Encoding.decodeBase64Url(input.getString("devicePrivateKeyPkcs8")))
        )
        val response = JSONObject()
            .put("version", 2)
            .put("algorithm", "P256-HKDF-SHA256-A256GCM")
            .put("deviceId", input.getString("deviceId"))
            .put("subscriptionId", input.getString("subscriptionId"))
            .put("ephemeralPublicKey", input.getString("ephemeralPublicKeySpki"))
            .put("salt", input.getString("salt"))
            .put("nonce", input.getString("nonce"))
            .put("ciphertext", expected.getString("ciphertextAndTag"))
            .put("requestId", input.getString("requestId"))
            .put("issuedAt", input.getLong("issuedAt"))
            .put("expiresAt", input.getLong("expiresAt"))
            .toString()

        val crypto = SubscriptionCryptoV2()
        val plaintext = crypto.decrypt(
            responseJson = response,
            privateKey = privateKey,
            expectedDeviceId = input.getString("deviceId"),
            expectedRequestId = input.getString("requestId"),
            expectedSubscriptionId = input.getString("subscriptionId"),
            nowEpochSeconds = input.getLong("issuedAt")
        )
        assertEquals(input.getString("plaintext"), plaintext)

        try {
            crypto.decrypt(
                responseJson = response,
                privateKey = privateKey,
                expectedDeviceId = input.getString("deviceId"),
                expectedRequestId = "AAAAAAAAAAAAAAAAAAAAAA",
                expectedSubscriptionId = input.getString("subscriptionId"),
                nowEpochSeconds = input.getLong("issuedAt")
            )
            fail("Wrong challenge must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
