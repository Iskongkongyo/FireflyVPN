package xyz.a202132.app.ui.dialogs

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.a202132.app.data.model.BandwidthTestStatus

class BandwidthResultUiMapperTest {
    @Test
    fun `successful measurement displays speed`() {
        assertEquals(
            "12.3 Mbps",
            BandwidthResultUiMapper.summary(12.34f, BandwidthTestStatus.SUCCESS)
        )
    }

    @Test
    fun `zero result displays its actual failure status`() {
        assertEquals(
            "代理启动失败",
            BandwidthResultUiMapper.summary(0f, BandwidthTestStatus.PROXY_START_FAILED)
        )
        assertEquals(
            "测试超时",
            BandwidthResultUiMapper.summary(0f, BandwidthTestStatus.TIMEOUT)
        )
        assertEquals(
            "请求失败",
            BandwidthResultUiMapper.summary(0f, BandwidthTestStatus.REQUEST_FAILED)
        )
    }

    @Test
    fun `detail includes the captured reason`() {
        assertEquals(
            "测试超时（timeout）",
            BandwidthResultUiMapper.detail(0f, BandwidthTestStatus.TIMEOUT, "timeout")
        )
    }
}
