package xyz.a202132.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import xyz.a202132.app.data.model.ProxyMode
import xyz.a202132.app.data.model.PerAppProxyMode
import xyz.a202132.app.data.model.IPv6RoutingMode

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    
    companion object {
        private val SELECTED_NODE_ID = stringPreferencesKey("selected_node_id")
        private val PROXY_MODE = stringPreferencesKey("proxy_mode")
        private val LAST_NOTICE_ID = stringPreferencesKey("last_notice_id")
        private val AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        private val USER_AGREEMENT_ACCEPTED = booleanPreferencesKey("user_agreement_accepted")
        
        // 分应用代理设置
        private val PER_APP_PROXY_ENABLED = booleanPreferencesKey("per_app_proxy_enabled")
        private val PER_APP_PROXY_MODE = stringPreferencesKey("per_app_proxy_mode")
        private val SELECTED_PACKAGES = stringSetPreferencesKey("selected_packages")
        
        // 绕过局域网设置
        private val BYPASS_LAN = booleanPreferencesKey("bypass_lan")
        
        // IPv6 路由设置
        private val IPV6_ROUTING_MODE = stringPreferencesKey("ipv6_routing_mode")
        
        // 备用节点
        private val BACKUP_NODE_ENABLED = booleanPreferencesKey("backup_node_enabled")
        private val BACKUP_NODE_URL = stringPreferencesKey("backup_node_url")

        // 自动化测试设置
        private val AUTO_TEST_ENABLED = booleanPreferencesKey("auto_test_enabled")
        private val AUTO_TEST_FILTER_UNAVAILABLE = booleanPreferencesKey("auto_test_filter_unavailable")
        private val AUTO_TEST_LATENCY_THRESHOLD = intPreferencesKey("auto_test_latency_threshold")
        private val AUTO_TEST_BANDWIDTH_ENABLED = booleanPreferencesKey("auto_test_bandwidth_enabled")
        private val AUTO_TEST_BANDWIDTH_THRESHOLD = intPreferencesKey("auto_test_bandwidth_threshold")
        private val AUTO_TEST_BANDWIDTH_WIFI_ONLY = booleanPreferencesKey("auto_test_bandwidth_wifi_only")
        private val AUTO_TEST_BANDWIDTH_SIZE_MB = intPreferencesKey("auto_test_bandwidth_size_mb")
        private val AUTO_TEST_UNLOCK_ENABLED = booleanPreferencesKey("auto_test_unlock_enabled")
        private val AUTO_TEST_NODE_LIMIT = intPreferencesKey("auto_test_node_limit")
    }
    
    val selectedNodeId: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[SELECTED_NODE_ID]
    }
    
    val proxyMode: Flow<ProxyMode> = context.dataStore.data.map { preferences ->
        val mode = preferences[PROXY_MODE] ?: ProxyMode.SMART.name
        try {
            ProxyMode.valueOf(mode)
        } catch (e: Exception) {
            ProxyMode.SMART
        }
    }
    
    val lastNoticeId: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[LAST_NOTICE_ID]
    }
    
    val autoConnect: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AUTO_CONNECT] ?: false
    }
    
    // 分应用代理 - 是否启用
    val perAppProxyEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PER_APP_PROXY_ENABLED] ?: false
    }
    
    val perAppProxyMode: Flow<PerAppProxyMode> = context.dataStore.data.map { preferences ->
        val mode = preferences[PER_APP_PROXY_MODE] ?: PerAppProxyMode.WHITELIST.name
        try {
            PerAppProxyMode.valueOf(mode)
        } catch (e: Exception) {
            PerAppProxyMode.WHITELIST
        }
    }
    
    // 分应用代理 - 选中的应用包名列表
    val selectedPackages: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[SELECTED_PACKAGES] ?: emptySet()
    }
    
    // 绕过局域网
    val bypassLan: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[BYPASS_LAN] ?: true // 默认开启
    }
    
    suspend fun setSelectedNodeId(nodeId: String?) {
        context.dataStore.edit { preferences ->
            if (nodeId == null) {
                preferences.remove(SELECTED_NODE_ID)
            } else {
                preferences[SELECTED_NODE_ID] = nodeId
            }
        }
    }
    
    suspend fun setProxyMode(mode: ProxyMode) {
        context.dataStore.edit { preferences ->
            preferences[PROXY_MODE] = mode.name
        }
    }
    
    suspend fun setLastNoticeId(noticeId: String) {
        context.dataStore.edit { preferences ->
            preferences[LAST_NOTICE_ID] = noticeId
        }
    }
    
    suspend fun setAutoConnect(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_CONNECT] = enabled
        }
    }
    
    val isUserAgreementAccepted: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[USER_AGREEMENT_ACCEPTED] ?: false
    }
    
    suspend fun setUserAgreementAccepted(accepted: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[USER_AGREEMENT_ACCEPTED] = accepted
        }
    }
    
    // 分应用代理设置方法
    suspend fun setPerAppProxyEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PER_APP_PROXY_ENABLED] = enabled
        }
    }
    
    suspend fun setPerAppProxyMode(mode: PerAppProxyMode) {
        context.dataStore.edit { preferences ->
            preferences[PER_APP_PROXY_MODE] = mode.name
        }
    }
    
    suspend fun setSelectedPackages(packages: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[SELECTED_PACKAGES] = packages
        }
    }
    
    suspend fun setBypassLan(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[BYPASS_LAN] = enabled
        }
    }
    
    // IPv6 路由模式
    val ipv6RoutingMode: Flow<IPv6RoutingMode> = context.dataStore.data.map { preferences ->
        val mode = preferences[IPV6_ROUTING_MODE] ?: IPv6RoutingMode.DISABLED.name
        try {
            IPv6RoutingMode.valueOf(mode)
        } catch (e: Exception) {
            IPv6RoutingMode.DISABLED
        }
    }
    
    suspend fun setIPv6RoutingMode(mode: IPv6RoutingMode) {
        context.dataStore.edit { preferences ->
            preferences[IPV6_ROUTING_MODE] = mode.name
        }
    }
    
    // 备用节点
    val backupNodeEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[BACKUP_NODE_ENABLED] ?: false
    }
    
    suspend fun setBackupNodeEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[BACKUP_NODE_ENABLED] = enabled
        }
    }
    
    val backupNodeUrl: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[BACKUP_NODE_URL]
    }
    
    suspend fun setBackupNodeUrl(url: String?) {
        context.dataStore.edit { preferences ->
            if (url == null) {
                preferences.remove(BACKUP_NODE_URL)
            } else {
                preferences[BACKUP_NODE_URL] = url
            }
        }
    }

    // 自动化测试设置
    val autoTestEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AUTO_TEST_ENABLED] ?: false
    }

    suspend fun setAutoTestEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_TEST_ENABLED] = enabled
        }
    }

    val autoTestFilterUnavailable: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AUTO_TEST_FILTER_UNAVAILABLE] ?: true
    }

    suspend fun setAutoTestFilterUnavailable(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_TEST_FILTER_UNAVAILABLE] = enabled
        }
    }

    val autoTestLatencyThresholdMs: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[AUTO_TEST_LATENCY_THRESHOLD] ?: 600
    }

    suspend fun setAutoTestLatencyThresholdMs(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_TEST_LATENCY_THRESHOLD] = value.coerceAtLeast(50)
        }
    }

    val autoTestBandwidthEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AUTO_TEST_BANDWIDTH_ENABLED] ?: false
    }

    suspend fun setAutoTestBandwidthEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_TEST_BANDWIDTH_ENABLED] = enabled
        }
    }

    val autoTestBandwidthThresholdMbps: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[AUTO_TEST_BANDWIDTH_THRESHOLD] ?: 10
    }

    suspend fun setAutoTestBandwidthThresholdMbps(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_TEST_BANDWIDTH_THRESHOLD] = value.coerceAtLeast(1)
        }
    }

    val autoTestBandwidthWifiOnly: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AUTO_TEST_BANDWIDTH_WIFI_ONLY] ?: true
    }

    suspend fun setAutoTestBandwidthWifiOnly(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_TEST_BANDWIDTH_WIFI_ONLY] = enabled
        }
    }

    val autoTestBandwidthSizeMb: Flow<Int> = context.dataStore.data.map { preferences ->
        val value = preferences[AUTO_TEST_BANDWIDTH_SIZE_MB] ?: 10
        when (value) {
            1, 10, 25, 50 -> value
            else -> 10
        }
    }

    suspend fun setAutoTestBandwidthSizeMb(value: Int) {
        val normalized = when (value) {
            1, 10, 25, 50 -> value
            else -> 10
        }
        context.dataStore.edit { preferences ->
            preferences[AUTO_TEST_BANDWIDTH_SIZE_MB] = normalized
        }
    }

    val autoTestUnlockEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AUTO_TEST_UNLOCK_ENABLED] ?: false
    }

    suspend fun setAutoTestUnlockEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_TEST_UNLOCK_ENABLED] = enabled
        }
    }

    val autoTestNodeLimit: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[AUTO_TEST_NODE_LIMIT] ?: 20
    }

    suspend fun setAutoTestNodeLimit(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_TEST_NODE_LIMIT] = value.coerceIn(1, 200)
        }
    }
}

