package xyz.a202132.app.util.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

class DeviceKeyManager(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val wrappedKeyStorage = KeystoreAesStorage(
        context = appContext,
        preferencesName = WRAPPED_KEY_PREFERENCES,
        keyAlias = WRAPPING_KEY_ALIAS
    )

    @Synchronized
    fun ensureKeyPair(): KeyPair {
        loadSoftwareKeyPair()?.let { return it }
        loadHardwareKeyPair()?.let { return it }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            generateHardwareKeyPair()
        } else {
            generateWrappedSoftwareKeyPair()
        }
    }

    fun hasKey(): Boolean = loadSoftwareKeyPair() != null || loadHardwareKeyPair() != null

    fun getPrivateKey(): PrivateKey = ensureKeyPair().private

    fun getPublicKeySpki(): ByteArray = ensureKeyPair().public.encoded

    fun getPublicKeySpkiBase64Url(): String = CryptoV2Encoding.encodeBase64Url(getPublicKeySpki())

    @Synchronized
    fun deleteKey() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(ECDH_KEY_ALIAS)) keyStore.deleteEntry(ECDH_KEY_ALIAS)
        wrappedKeyStorage.remove(SOFTWARE_PRIVATE_KEY)
        preferences.edit().remove(SOFTWARE_PUBLIC_KEY).apply()
    }

    private fun loadHardwareKeyPair(): KeyPair? = runCatching {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val privateKey = keyStore.getKey(ECDH_KEY_ALIAS, null) as? PrivateKey ?: return null
        val publicKey = keyStore.getCertificate(ECDH_KEY_ALIAS)?.publicKey ?: return null
        KeyPair(publicKey, privateKey)
    }.getOrNull()

    private fun loadSoftwareKeyPair(): KeyPair? = runCatching {
        val publicEncoded = preferences.getString(SOFTWARE_PUBLIC_KEY, null)
            ?.let(CryptoV2Encoding::decodeBase64Url) ?: return null
        val privateEncoded = wrappedKeyStorage.get(SOFTWARE_PRIVATE_KEY) ?: return null
        val factory = KeyFactory.getInstance("EC")
        val publicKey = factory.generatePublic(X509EncodedKeySpec(publicEncoded))
        val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(privateEncoded))
        KeyPair(publicKey, privateKey)
    }.getOrNull()

    private fun generateWrappedSoftwareKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = generator.generateKeyPair()
        wrappedKeyStorage.put(SOFTWARE_PRIVATE_KEY, keyPair.private.encoded)
        preferences.edit()
            .putString(SOFTWARE_PUBLIC_KEY, CryptoV2Encoding.encodeBase64Url(keyPair.public.encoded))
            .apply()
        return keyPair
    }

    private fun generateHardwareKeyPair(): KeyPair {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(ECDH_KEY_ALIAS, KeyProperties.PURPOSE_AGREE_KEY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        return generator.generateKeyPair()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ECDH_KEY_ALIAS = "fireflyvpn_subscription_p256_v2"
        const val WRAPPING_KEY_ALIAS = "fireflyvpn_subscription_p256_wrap"
        const val PREFERENCES_NAME = "firefly_crypto_v2_public"
        const val WRAPPED_KEY_PREFERENCES = "firefly_crypto_v2_private"
        const val SOFTWARE_PUBLIC_KEY = "software_public_spki"
        const val SOFTWARE_PRIVATE_KEY = "software_private_pkcs8"
    }
}
