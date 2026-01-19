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
    
    private val connectivityManager by lazy {
        service.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    
    /**
     * 启动网络监控 - 必须在 Libbox.newService 之前调用
     */
    fun startNetworkMonitor() {
        Log.d(TAG, "Starting network monitor")
        defaultNetwork = connectivityManager.activeNetwork
        
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network available: $network")
                defaultNetwork = network
                notifyInterfaceUpdate(network)
            }
            
            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost: $network")
                if (defaultNetwork == network) {
                    defaultNetwork = null
                    interfaceUpdateListener?.updateDefaultInterface("", -1, false, false)
                }
            }
            
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (defaultNetwork == network) {
                    notifyInterfaceUpdate(network)
                }
            }
        }
        
        networkCallback = callback
        
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager.registerDefaultNetworkCallback(callback)
            } else {
                connectivityManager.requestNetwork(request, callback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
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
        
        // 排除自身应用
        try {
            builder.addDisallowedApplication(service.packageName)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to disallow self package", e)
        }
        
        tunFd = builder.establish()
        
        val fd = tunFd?.fd ?: -1
        Log.d(TAG, "TUN interface opened with fd: $fd")
        return fd
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
        try {
            tunFd?.close()
            tunFd = null
            Log.d(TAG, "TUN interface closed")
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
