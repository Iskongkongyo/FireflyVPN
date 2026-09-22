package xyz.a202132.app.ui.dialogs

import xyz.a202132.app.data.model.BandwidthTestStatus

internal object BandwidthResultUiMapper {
    fun summary(mbps: Float, status: BandwidthTestStatus): String {
        if (mbps > 0f) return "%.1f Mbps".format(mbps)
        return when (status) {
            BandwidthTestStatus.NOT_TESTED -> "未测试"
            BandwidthTestStatus.SUCCESS -> "无有效数据"
            BandwidthTestStatus.SKIPPED -> "已跳过"
            BandwidthTestStatus.PROXY_START_FAILED -> "代理启动失败"
            BandwidthTestStatus.TIMEOUT -> "测试超时"
            BandwidthTestStatus.REQUEST_FAILED -> "请求失败"
        }
    }

    fun detail(mbps: Float, status: BandwidthTestStatus, message: String?): String {
        val summary = summary(mbps, status)
        val reason = message?.trim()?.takeIf(String::isNotEmpty)
        return if (reason == null || mbps > 0f) summary else "$summary（$reason）"
    }
}
