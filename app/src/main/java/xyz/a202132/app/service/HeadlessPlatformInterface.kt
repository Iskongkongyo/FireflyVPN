package xyz.a202132.app.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions

/**
 * 无头平台接口 - 用于 URL Test 等不需要 VPN/TUN 的场景
 * 需要 Context 来检测当前活动网络接口
 */
class HeadlessPlatformInterface(private val context: Context) : PlatformInterface {
    
    companion object {
        private const val TAG = "HeadlessPlatform"
    }
    
    // 返回 false: 不使用平台接口控制 socket 绑定
    // 改为在配置中使用 default_interface 让 sing-box 自己绑定接口
    override fun usePlatformAutoDetectInterfaceControl(): Boolean = false
    
    override fun autoDetectInterfaceControl(fd: Int) {
        // 不会被调用 (usePlatformAutoDetectInterfaceControl 返回 false)
    }
    
    override fun openTun(options: TunOptions?): Int {
        throw UnsupportedOperationException("Headless mode does not support TUN")
    }
    
    override fun useProcFS(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    }
    
    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String?,
        sourcePort: Int,
        destinationAddress: String?,
        destinationPort: Int
    ): io.nekohasekai.libbox.ConnectionOwner {
        return io.nekohasekai.libbox.ConnectionOwner()
    }
    
    override fun clearDNSCache() {}
    
    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        Log.d(TAG, "startDefaultInterfaceMonitor")
        // 检测当前活动网络接口并通知 sing-box
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetwork
            if (activeNetwork != null) {
                val linkProperties = cm.getLinkProperties(activeNetwork)
                val interfaceName = linkProperties?.interfaceName
                if (interfaceName != null) {
                    val netIf = java.net.NetworkInterface.getByName(interfaceName)
                    val index = netIf?.index ?: 0
                    Log.d(TAG, "Default interface: $interfaceName (index=$index)")
                    listener?.updateDefaultInterface(interfaceName, index, false, false)
                } else {
                    Log.w(TAG, "No interface name found for active network")
                }
            } else {
                Log.w(TAG, "No active network")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to detect default interface", e)
        }
    }
    
    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        Log.d(TAG, "closeDefaultInterfaceMonitor")
    }
    
    override fun getInterfaces(): NetworkInterfaceIterator {
        return object : NetworkInterfaceIterator {
            override fun hasNext(): Boolean = false
            override fun next(): io.nekohasekai.libbox.NetworkInterface = throw NoSuchElementException()
        }
    }
    
    override fun sendNotification(notification: io.nekohasekai.libbox.Notification?) {}
    
    override fun readWIFIState(): io.nekohasekai.libbox.WIFIState? = null
    
    override fun includeAllNetworks(): Boolean = false
    
    override fun localDNSTransport(): io.nekohasekai.libbox.LocalDNSTransport? = null
    
    override fun systemCertificates(): StringIterator? = null
    
    override fun underNetworkExtension(): Boolean = false
}
