package xyz.a202132.app.util.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdentityManagerTest {
    @Test
    fun `media drm identity takes priority and is canonicalized`() {
        val signals = signals(
            androidId = " A1B2C3 ",
            mediaDrmDeviceId = byteArrayOf(0x00, 0x7f, 0xff.toByte()),
            manufacturer = "ignored"
        )

        assertEquals("007fff:a1b2c3", AndroidDeviceIdentity.identityMaterial(signals))
    }

    @Test
    fun `hardware profile is used when media drm is unavailable`() {
        val signals = signals(
            androidId = " ABCDEF ",
            manufacturer = " Google ",
            model = " Pixel 8 ",
            device = " Husky ",
            board = " Board ",
            hardware = " Tensor "
        )

        assertEquals(
            "abcdef|google|pixel 8|husky|board|tensor",
            AndroidDeviceIdentity.identityMaterial(signals)
        )
    }

    @Test
    fun `missing and historically broken values use stable placeholders`() {
        val signals = signals(
            androidId = "9774d56d682e549c",
            manufacturer = null,
            model = " ",
            device = null,
            board = "BOARD",
            hardware = "HW"
        )

        assertEquals(
            "unknown|unknown|unknown|unknown|board|hw",
            AndroidDeviceIdentity.identityMaterial(signals)
        )
    }

    @Test
    fun `device id is deterministic lowercase sha256`() {
        val first = AndroidDeviceIdentity.deviceId(signals())
        val second = AndroidDeviceIdentity.deviceId(signals())
        val changed = AndroidDeviceIdentity.deviceId(signals(model = "another-model"))

        assertEquals(first, second)
        assertNotEquals(first, changed)
        assertEquals(64, first.length)
        assertTrue(first.all { it in '0'..'9' || it in 'a'..'f' })
        assertFalse(first.any(Char::isUpperCase))
    }

    private fun signals(
        androidId: String? = "android-id",
        mediaDrmDeviceId: ByteArray? = null,
        manufacturer: String? = "manufacturer",
        model: String? = "model",
        device: String? = "device",
        board: String? = "board",
        hardware: String? = "hardware"
    ) = AndroidDeviceIdentitySignals(
        androidId = androidId,
        mediaDrmDeviceId = mediaDrmDeviceId,
        manufacturer = manufacturer,
        model = model,
        device = device,
        board = board,
        hardware = hardware
    )
}
