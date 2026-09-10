package xyz.a202132.app.data.model

/** sing-box TUN TCP/IP 栈实现。 */
enum class TunStackMode(val configValue: String) {
    /** 系统 TCP + gVisor UDP，兼顾性能与 UDP 兼容性。 */
    MIXED("mixed"),

    /** 完整虚拟网络栈，作为 Android 兼容优先的默认选项。 */
    GVISOR("gvisor"),

    /** 使用系统网络栈，通常开销较低，但依赖设备系统实现。 */
    SYSTEM("system")
}
