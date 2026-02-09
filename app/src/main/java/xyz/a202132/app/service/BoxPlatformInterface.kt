package xyz.a202132.app.service

import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import kotlinx.coroutines.flow.first
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface

/**
 * libbox 平台接口实现
 * 参考官方 sing-box-for-android 实现
 * 适配 libbox 1.12.16 API
 */
class BoxPlatformInterface(
    private val service: BoxVpnService
) : PlatformInterface {
    
    companion object {
        private const val TAG = "BoxPlatformInterface"
    }
    
    private var tunFd: ParcelFileDescriptor? = null
    private var defaultNetwork: Network? = null
    private var interfaceUpdateListener: InterfaceUpdateListener? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNotifiedNetworkId: Long = -1
    
    private val connectivityManager by lazy {
        service.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    
    private val availableNetworks = java.util.concurrent.ConcurrentHashMap<Network, NetworkCapabilities>()
    
    /**
     * 启动网络监控 - 必须在 Libbox.newService 之前调用
     */
    fun startNetworkMonitor() {
        Log.d(TAG, "Starting network monitor")
        
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // 获取 capabilities，如果获取不到则忽略
                val caps = connectivityManager.getNetworkCapabilities(network) ?: return
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return
                
                availableNetworks[network] = caps
                evaluateBestNetwork()
            }
            
            override fun onLost(network: Network) {
                availableNetworks.remove(network)
                evaluateBestNetwork()
            }
            
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return
                
                availableNetworks[network] = capabilities
                evaluateBestNetwork()
            }
        }
        
        networkCallback = callback
        
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        
        try {
            connectivityManager.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }
    
    private fun evaluateBestNetwork() {
        var bestNetwork: Network? = null
        var bestCaps: NetworkCapabilities? = null
        var bestScore = -1
        
        // 优先级: Ethernet (3) > WiFi (2) > Cellular (1)
        for ((network, caps) in availableNetworks) {
            val score = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 3
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 2
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
                else -> 0
            }
            
            if (score > bestScore) {
                bestScore = score
                bestNetwork = network
                bestCaps = caps
            }
        }
        
        // 如果最佳网络发生变化
        if (bestNetwork != defaultNetwork) {
            Log.d(TAG, "Network switched to: $bestNetwork (Score: $bestScore)")
            defaultNetwork = bestNetwork
            
            updateUnderlyingNetwork(bestNetwork)
            notifyInterfaceUpdate(bestNetwork)
            
            if (bestNetwork != null && bestCaps != null) {
                checkAndNotifyCellularNetwork(bestNetwork, bestCaps)
            }
        }
    }
    
    private fun notifyInterfaceUpdate(network: Network?) {
        val listener = interfaceUpdateListener ?: return
        if (network != null) {
            val linkProperties = connectivityManager.getLinkProperties(network) ?: return
            val interfaceName = linkProperties.interfaceName ?: return
            
            // 重试获取接口索引
            for (times in 0 until 10) {
                try {
                    val networkInterface = java.net.NetworkInterface.getByName(interfaceName)
                    if (networkInterface != null) {
                        val interfaceIndex = networkInterface.index
                        Log.d(TAG, "Default interface: $interfaceName (index: $interfaceIndex)")
                        listener.updateDefaultInterface(interfaceName, interfaceIndex, false, false)
                        return
                    }
                } catch (e: Exception) {
                    Thread.sleep(100)
                }
            }
        } else {
            listener.updateDefaultInterface("", -1, false, false)
        }
    }
    
    /**
     * 更新 VPN 底层网络
     * 当网络切换（WiFi <-> 蜂窝网络）时调用，确保 VPN 流量走新的物理网络
     */
    private fun updateUnderlyingNetwork(network: Network?) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val networks = if (network != null) arrayOf(network) else null
                service.setUnderlyingNetworks(networks)
                Log.d(TAG, "Updated underlying network: ${network ?: "null"}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update underlying network", e)
        }
    }
    
    /**
     * 检测是否为蜂窝网络并通知用户
     */
    private fun checkAndNotifyCellularNetwork(network: Network, capabilities: NetworkCapabilities) {
        try {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                // 防止重复提醒同一网络
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    if (network.networkHandle == lastNotifiedNetworkId) {
                        return
                    }
                }
                
                Log.d(TAG, "Switched to cellular network, notifying user")
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    lastNotifiedNetworkId = network.networkHandle
                }
                
                ServiceManager.notifyCellularNetwork()
            } else {
                // 如果切换到了非蜂窝网络，重置提醒 ID
                // 这样下次切换回蜂窝网络时可以再次提醒
                lastNotifiedNetworkId = -1
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check network type", e)
        }
    }
    
    /**
     * 停止网络监控
     */
    fun stopNetworkMonitor() {
        Log.d(TAG, "Stopping network monitor")
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister network callback", e)
            }
        }
        networkCallback = null
        defaultNetwork = null
    }
    
    /**
     * 打开 TUN 接口 - 使用 libbox 传来的选项
     */
    override fun openTun(options: TunOptions): Int {
        Log.d(TAG, "Opening TUN interface with MTU: ${options.mtu}")
        
        val builder = service.createVpnBuilder()
            .setSession("空空加速器")
            .setMtu(options.mtu)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        
        // IPv4 地址
        val inet4Address = options.inet4Address
        while (inet4Address.hasNext()) {
            val address = inet4Address.next()
            Log.d(TAG, "Adding IPv4 address: ${address.address()}/${address.prefix()}")
            builder.addAddress(address.address(), address.prefix())
        }
        
        // IPv6 地址
        val inet6Address = options.inet6Address
        while (inet6Address.hasNext()) {
            val address = inet6Address.next()
            Log.d(TAG, "Adding IPv6 address: ${address.address()}/${address.prefix()}")
            builder.addAddress(address.address(), address.prefix())
        }
        
        // 自动路由
        if (options.autoRoute) {
            // DNS 服务器
            builder.addDnsServer(options.dnsServerAddress.value)
            
            // 路由
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
        }
        
        // 分应用代理 - 返回是否使用了白名单模式
        val usedWhitelistMode = applyPerAppProxy(builder)
        
        // 排除自身应用（仅在非白名单模式时）
        // Android 限制：addAllowedApplication 和 addDisallowedApplication 不能混用
        if (!usedWhitelistMode) {
            try {
                builder.addDisallowedApplication(service.packageName)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to disallow self package", e)
            }
        }
        
        tunFd = builder.establish()
        
        val fd = tunFd?.fd ?: -1
        Log.d(TAG, "TUN interface opened with fd: $fd")
        return fd
    }
    
    /**
     * 应用分应用代理规则
     * @return 是否使用了白名单模式（addAllowedApplication）
     */
    private fun applyPerAppProxy(builder: android.net.VpnService.Builder): Boolean {
        val settingsRepo = xyz.a202132.app.data.repository.SettingsRepository(service)
        
        // 使用 runBlocking 读取设置（在 openTun 调用时需要同步获取）
        val isPerAppEnabled = kotlinx.coroutines.runBlocking { 
            settingsRepo.perAppProxyEnabled.first() 
        }
        
        if (!isPerAppEnabled) {
            Log.d(TAG, "Per-app proxy disabled, all apps will use VPN")
            return false
        }
        
        val mode = kotlinx.coroutines.runBlocking { 
            settingsRepo.perAppProxyMode.first() 
        }
        val selectedPackages = kotlinx.coroutines.runBlocking { 
            settingsRepo.selectedPackages.first() 
        }
        
        Log.d(TAG, "Per-app proxy enabled, mode: $mode, selected: ${selectedPackages.size} apps")
        
        when (mode) {
            xyz.a202132.app.data.model.PerAppProxyMode.WHITELIST -> {
                // 代理模式：只允许选中的应用使用 VPN
                if (selectedPackages.isEmpty()) {
                    Log.w(TAG, "Whitelist mode but no apps selected - no apps will use VPN")
                }
                selectedPackages.forEach { pkg ->
                    try {
                        builder.addAllowedApplication(pkg)
                        Log.d(TAG, "Allowed app: $pkg")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to allow app: $pkg", e)
                    }
                }
                return true // 使用了白名单模式
            }
            xyz.a202132.app.data.model.PerAppProxyMode.BLACKLIST -> {
                // 绕过模式：排除选中的应用
                selectedPackages.forEach { pkg ->
                    try {
                        builder.addDisallowedApplication(pkg)
                        Log.d(TAG, "Disallowed app: $pkg")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to disallow app: $pkg", e)
                    }
                }
                return false // 未使用白名单模式
            }
        }
        return false
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
    ): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return connectivityManager.getConnectionOwnerUid(
                    ipProtocol,
                    InetSocketAddress(sourceAddress, sourcePort),
                    InetSocketAddress(destinationAddress, destinationPort)
                )
            } catch (e: Exception) {
                Log.e(TAG, "getConnectionOwnerUid failed", e)
            }
        }
        return -1
    }
    
    override fun packageNameByUid(uid: Int): String {
        return try {
            val packages = service.packageManager.getPackagesForUid(uid)
            packages?.firstOrNull() ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get package name for uid $uid", e)
            ""
        }
    }
    
    override fun uidByPackageName(packageName: String?): Int {
        if (packageName.isNullOrEmpty()) return -1
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                service.packageManager.getPackageUid(packageName, 0)
            } else {
                @Suppress("DEPRECATION")
                service.packageManager.getApplicationInfo(packageName, 0).uid
            }
        } catch (e: PackageManager.NameNotFoundException) {
            -1
        }
    }
    
    override fun usePlatformAutoDetectInterfaceControl(): Boolean {
        return true
    }
    
    override fun autoDetectInterfaceControl(fd: Int) {
        Log.d(TAG, "Protecting socket fd: $fd")
        service.protect(fd)
    }
    
    override fun clearDNSCache() {
        Log.d(TAG, "Clear DNS cache")
    }
    
    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        Log.d(TAG, "Start default interface monitor")
        interfaceUpdateListener = listener
        // 立即通知当前网络
        defaultNetwork?.let { notifyInterfaceUpdate(it) }
    }
    
    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        Log.d(TAG, "Close default interface monitor")
        interfaceUpdateListener = null
    }
    
    override fun getInterfaces(): NetworkInterfaceIterator {
        val networks = connectivityManager.allNetworks
        val networkInterfaces = java.net.NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
        val interfaces = mutableListOf<LibboxNetworkInterface>()
        
        for (network in networks) {
            try {
                val linkProperties = connectivityManager.getLinkProperties(network) ?: continue
                val networkCapabilities = connectivityManager.getNetworkCapabilities(network) ?: continue
                
                val boxInterface = LibboxNetworkInterface()
                boxInterface.name = linkProperties.interfaceName ?: continue
                
                val networkInterface = networkInterfaces.find { it.name == boxInterface.name } ?: continue
                
                boxInterface.dnsServer = StringArray(
                    linkProperties.dnsServers.mapNotNull { it.hostAddress }.iterator()
                )
                
                boxInterface.type = when {
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                
                boxInterface.index = networkInterface.index
                
                try {
                    boxInterface.mtu = networkInterface.mtu
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to get MTU for ${boxInterface.name}")
                }
                
                boxInterface.addresses = StringArray(
                    networkInterface.interfaceAddresses.map { it.toPrefix() }.iterator()
                )
                
                var flags = 0
                if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    flags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                }
                if (networkInterface.isLoopback) {
                    flags = flags or OsConstants.IFF_LOOPBACK
                }
                if (networkInterface.isPointToPoint) {
                    flags = flags or OsConstants.IFF_POINTOPOINT
                }
                if (networkInterface.supportsMulticast()) {
                    flags = flags or OsConstants.IFF_MULTICAST
                }
                boxInterface.flags = flags
                
                boxInterface.metered = !networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                
                interfaces.add(boxInterface)
            } catch (e: Exception) {
                Log.e(TAG, "Error getting interface info", e)
            }
        }
        
        return InterfaceArray(interfaces.iterator())
    }
    
    override fun sendNotification(notification: io.nekohasekai.libbox.Notification?) {
        notification?.let {
            Log.d(TAG, "Notification received")
        }
    }
    
    override fun readWIFIState(): io.nekohasekai.libbox.WIFIState? {
        return null
    }
    
    override fun includeAllNetworks(): Boolean {
        return false
    }
    
    override fun localDNSTransport(): io.nekohasekai.libbox.LocalDNSTransport? {
        return null
    }
    
    override fun systemCertificates(): StringIterator? {
        return null
    }
    
    override fun underNetworkExtension(): Boolean {
        return false
    }
    
    override fun writeLog(message: String?) {
        message?.let {
            Log.d(TAG, "libbox: $it")
        }
    }
    
    fun closeTun() {
        val startTime = System.currentTimeMillis()
        val hadFd = tunFd != null
        try {
            tunFd?.close()
            tunFd = null
            val elapsed = System.currentTimeMillis() - startTime
            Log.d(TAG, "TUN interface closed (hadFd=$hadFd, took ${elapsed}ms)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close TUN", e)
        }
    }
    
    // 辅助类
    private class InterfaceArray(private val iterator: Iterator<LibboxNetworkInterface>) : NetworkInterfaceIterator {
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): LibboxNetworkInterface = iterator.next()
    }
    
    private class StringArray(private val iterator: Iterator<String>) : StringIterator {
        override fun len(): Int = 0
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): String = iterator.next()
    }
    
    private fun InterfaceAddress.toPrefix(): String {
        return if (address is Inet6Address) {
            "${Inet6Address.getByAddress(address.address).hostAddress}/${networkPrefixLength}"
        } else {
            "${address.hostAddress}/${networkPrefixLength}"
        }
    }
}
