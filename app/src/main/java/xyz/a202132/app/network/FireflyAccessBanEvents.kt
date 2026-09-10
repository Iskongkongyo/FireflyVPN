package xyz.a202132.app.network

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.IOException

/** 服务端明确封禁匿名账号或当前设备。 */
class FireflyAccessBannedException internal constructor(
    val errorCode: String
) : IOException(
    if (errorCode == "account_banned") "此匿名账号已被停用" else "此设备已被停用"
)

data class FireflyAccessBanEvent(val errorCode: String)

/**
 * 网络请求可能来自页面、流量上报或 VPN 自动恢复，用进程内事件统一交给应用层处理。
 * 合并通道会保留尚未处理的最新事件，同时不会向新页面重复投递已处理事件。
 */
object FireflyAccessBanEvents {
    private val eventChannel = Channel<FireflyAccessBanEvent>(Channel.CONFLATED)
    val events = eventChannel.receiveAsFlow()

    internal fun publish(errorCode: String) {
        eventChannel.trySend(FireflyAccessBanEvent(errorCode))
    }
}

fun Throwable?.isFireflyAccessBanned(): Boolean {
    var current = this
    while (current != null) {
        if (current is FireflyAccessBannedException) return true
        current = current.cause
    }
    return false
}
