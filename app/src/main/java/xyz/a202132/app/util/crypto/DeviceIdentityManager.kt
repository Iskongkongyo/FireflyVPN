package xyz.a202132.app.util.crypto

import android.content.Context
import android.media.MediaDrm
import android.os.Build
import android.provider.Settings
import java.util.Locale
import java.util.UUID

/**
 * Builds the stable Android identity used by the existing Firefly V2 `deviceId` field.
 *
 * The public protocol intentionally remains unchanged: callers still receive a lowercase
 * SHA-256 value. Unlike the previous installation seed, the input is derived from device
 * signals so clearing app data or reinstalling the app does not normally create a new ID.
 */
class DeviceIdentityManager(context: Context) {
    private val appContext = context.applicationContext

    @Volatile
    private var cachedDeviceId: String? = null

    fun getDeviceId(): String = cachedDeviceId ?: synchronized(this) {
        cachedDeviceId ?: collectSignals()
            .let(AndroidDeviceIdentity::deviceId)
            .also { cachedDeviceId = it }
    }

    private fun collectSignals(): AndroidDeviceIdentitySignals = AndroidDeviceIdentitySignals(
        androidId = runCatching {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull(),
        mediaDrmDeviceId = readWidevineDeviceId(),
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        device = Build.DEVICE,
        board = Build.BOARD,
        hardware = Build.HARDWARE
    )

    private fun readWidevineDeviceId(): ByteArray? = runCatching {
        val mediaDrm = MediaDrm(WIDEVINE_UUID)
        try {
            mediaDrm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)
                .takeIf(ByteArray::isNotEmpty)
        } finally {
            closeMediaDrm(mediaDrm)
        }
    }.getOrNull()

    private fun closeMediaDrm(mediaDrm: MediaDrm) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            mediaDrm.close()
        } else {
            releaseLegacyMediaDrm(mediaDrm)
        }
    }

    @Suppress("DEPRECATION")
    private fun releaseLegacyMediaDrm(mediaDrm: MediaDrm) {
        mediaDrm.release()
    }

    private companion object {
        val WIDEVINE_UUID: UUID = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")
    }
}

internal data class AndroidDeviceIdentitySignals(
    val androidId: String?,
    val mediaDrmDeviceId: ByteArray?,
    val manufacturer: String?,
    val model: String?,
    val device: String?,
    val board: String?,
    val hardware: String?
)

internal object AndroidDeviceIdentity {
    fun deviceId(signals: AndroidDeviceIdentitySignals): String =
        CryptoV2Encoding.sha256Hex(identityMaterial(signals).toByteArray(Charsets.UTF_8))

    fun identityMaterial(signals: AndroidDeviceIdentitySignals): String {
        val androidId = normalizeAndroidId(signals.androidId)
        val mediaDrmId = signals.mediaDrmDeviceId
            ?.takeIf(ByteArray::isNotEmpty)
            ?.toLowerHex()

        return if (mediaDrmId != null) {
            "$mediaDrmId:$androidId"
        } else {
            listOf(
                androidId,
                normalizeBuildValue(signals.manufacturer),
                normalizeBuildValue(signals.model),
                normalizeBuildValue(signals.device),
                normalizeBuildValue(signals.board),
                normalizeBuildValue(signals.hardware)
            ).joinToString("|")
        }
    }

    private fun normalizeAndroidId(value: String?): String = value
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.takeUnless { it.isEmpty() || it == LEGACY_BROKEN_ANDROID_ID }
        ?: MISSING_VALUE

    private fun normalizeBuildValue(value: String?): String = value
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.takeIf(String::isNotEmpty)
        ?: MISSING_VALUE

    private fun ByteArray.toLowerHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private const val MISSING_VALUE = "unknown"
    private const val LEGACY_BROKEN_ANDROID_ID = "9774d56d682e549c"
}
