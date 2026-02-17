package xyz.a202132.app.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.ProxyMode
import xyz.a202132.app.data.model.VpnState

/**
 * VPN服务管理器 - 单例
 */
object ServiceManager {
    
    private val _vpnState = MutableStateFlow(VpnState.DISCONNECTED)
    val vpnState: StateFlow<VpnState> = _vpnState.asStateFlow()
    
    private val _currentNode = MutableStateFlow<Node?>(null)
    val currentNode: StateFlow<Node?> = _currentNode.asStateFlow()
    
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    
    // 流量统计
    private val _uploadSpeed = MutableStateFlow(0L)
    val uploadSpeed: StateFlow<Long> = _uploadSpeed.asStateFlow()
    
    private val _downloadSpeed = MutableStateFlow(0L)
    val downloadSpeed: StateFlow<Long> = _downloadSpeed.asStateFlow()
    
    private val _uploadTotal = MutableStateFlow(0L)
    val uploadTotal: StateFlow<Long> = _uploadTotal.asStateFlow()
    
    private val _downloadTotal = MutableStateFlow(0L)
    val downloadTotal: StateFlow<Long> = _downloadTotal.asStateFlow()
    
    // 服务运行状态
    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()
    
    // 移动网络提醒
    private val _cellularWarning = MutableStateFlow<String?>(null)
    val cellularWarning: StateFlow<String?> = _cellularWarning.asStateFlow()
    
    const val VPN_PERMISSION_REQUEST_CODE = 1001
    
    /**
     * 准备VPN权限
     * @return true if needs permission request, false if ready
     */
    fun prepareVpn(activity: Activity): Boolean {
        val intent = VpnService.prepare(activity)
        return if (intent != null) {
            activity.startActivityForResult(intent, VPN_PERMISSION_REQUEST_CODE)
            true
        } else {
            false
        }
    }
    
    /**
     * 启动VPN连接
     */
    fun startVpn(context: Context, node: Node, proxyMode: ProxyMode) {
        _vpnState.value = VpnState.CONNECTING
        _currentNode.value = node
        _errorMessage.value = null
        
        val intent = Intent(context, BoxVpnService::class.java).apply {
            action = BoxVpnService.ACTION_START
            putExtra(BoxVpnService.EXTRA_NODE_RAW_LINK, node.getRawLinkPlain())
            putExtra(BoxVpnService.EXTRA_NODE_NAME, node.name)
            putExtra(BoxVpnService.EXTRA_PROXY_MODE, proxyMode.name)
        }
        
        // 使用 startForegroundService 启动前台服务
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
    
    /**
     * 停止VPN连接
     */
    fun stopVpn(context: Context) {
        _vpnState.value = VpnState.DISCONNECTING
        
        val intent = Intent(context, BoxVpnService::class.java).apply {
            action = BoxVpnService.ACTION_STOP
        }
        
        context.startService(intent)
    }
    
    /**
     * 通知状态变化（由BoxVpnService调用）
     */
    fun notifyStateChange() {
        if (BoxVpnService.isRunning) {
            _vpnState.value = VpnState.CONNECTED
            // 更新流量统计
            _uploadSpeed.value = BoxVpnService.uploadSpeed
            _downloadSpeed.value = BoxVpnService.downloadSpeed
            _uploadTotal.value = BoxVpnService.uploadTotal
            _downloadTotal.value = BoxVpnService.downloadTotal
            _isServiceRunning.value = true
        } else {
            _vpnState.value = VpnState.DISCONNECTED
            _currentNode.value = null
            _uploadSpeed.value = 0L
            _downloadSpeed.value = 0L
            _isServiceRunning.value = false
        }
    }
    
    /**
     * 通知错误（由BoxVpnService调用）
     */
    fun notifyError(message: String) {
        _errorMessage.value = message
        _vpnState.value = VpnState.DISCONNECTED
        _currentNode.value = null
    }
    
    /**
     * 清除错误消息
     */
    fun clearError() {
        _errorMessage.value = null
    }
    
    /**
     * 通知切换到移动网络（由 BoxPlatformInterface 调用）
     */
    fun notifyCellularNetwork() {
        if (_isServiceRunning.value || _vpnState.value == VpnState.CONNECTING) {
            _cellularWarning.value = "使用移动网络中，请注意流量消耗"
        }
    }
    
    /**
     * 清除移动网络提醒
     */
    fun clearCellularWarning() {
        _cellularWarning.value = null
    }
    
    /**
     * 检查VPN是否正在运行
     */
    fun isRunning(): Boolean = BoxVpnService.isRunning
    
    /**
     * 获取当前连接的节点名称
     */
    fun getCurrentNodeName(): String? = BoxVpnService.currentNodeName
    
    /**
     * 格式化速度显示
     */
    fun formatSpeed(bytesPerSecond: Long): String {
        return when {
            bytesPerSecond < 1024 -> "${bytesPerSecond} B/s"
            bytesPerSecond < 1024 * 1024 -> "${bytesPerSecond / 1024} KB/s"
            bytesPerSecond < 1024 * 1024 * 1024 -> "${bytesPerSecond / (1024 * 1024)} MB/s"
            else -> "${bytesPerSecond / (1024 * 1024 * 1024)} GB/s"
        }
    }
    
    /**
     * 格式化流量显示
     */
    fun formatTraffic(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes} B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
            else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
        }
    }
}
