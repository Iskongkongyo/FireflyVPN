package xyz.a202132.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import xyz.a202132.app.AppConfig

class Hysteria2BandwidthAdvisorTest {
    @Test
    fun `keeps twenty percent headroom`() {
        assertEquals(80, Hysteria2BandwidthAdvisor.recommend(100f))
    }

    @Test
    fun `clamps very low and high measurements`() {
        assertEquals(
            AppConfig.HYSTERIA2_MIN_BANDWIDTH_MBPS,
            Hysteria2BandwidthAdvisor.recommend(0.5f)
        )
        assertEquals(
            AppConfig.HYSTERIA2_MIN_BANDWIDTH_MBPS,
            Hysteria2BandwidthAdvisor.recommend(8f)
        )
        assertEquals(10_000, Hysteria2BandwidthAdvisor.recommend(20_000f))
    }

    @Test
    fun `rejects invalid measurements`() {
        assertThrows(IllegalArgumentException::class.java) {
            Hysteria2BandwidthAdvisor.recommend(0f)
        }
    }
}
