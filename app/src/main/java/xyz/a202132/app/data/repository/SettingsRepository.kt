package xyz.a202132.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import xyz.a202132.app.data.model.IPv6RoutingMode
import xyz.a202132.app.data.model.PerAppProxyMode
import xyz.a202132.app.data.model.ProxyMode
import xyz.a202132.app.viewmodel.AutoTestLatencyMode
import xyz.a202132.app.viewmodel.BestNodePriority
import xyz.a202132.app.viewmodel.BUILTIN_PREFER_MODE_CHAT
import xyz.a202132.app.viewmodel.TestPreferMode
import xyz.a202132.app.viewmodel.UnlockPriorityMode
import xyz.a202132.app.viewmodel.normalizePreferTestModes

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
        private val AUTO_TEST_LATENCY_ENABLED = booleanPreferencesKey("auto_test_latency_enabled")
        private val AUTO_TEST_LATENCY_MODE = stringPreferencesKey("auto_test_latency_mode")
        private val AUTO_TEST_LATENCY_THRESHOLD = intPreferencesKey("auto_test_latency_threshold")
        private val AUTO_TEST_BANDWIDTH_ENABLED = booleanPreferencesKey("auto_test_bandwidth_enabled")
        private val AUTO_TEST_BANDWIDTH_DOWNLOAD_ENABLED = booleanPreferencesKey("auto_test_bandwidth_download_enabled")
        private val AUTO_TEST_BANDWIDTH_UPLOAD_ENABLED = booleanPreferencesKey("auto_test_bandwidth_upload_enabled")
        private val AUTO_TEST_BANDWIDTH_DOWNLOAD_THRESHOLD = intPreferencesKey("auto_test_bandwidth_download_threshold")
        private val AUTO_TEST_BANDWIDTH_UPLOAD_THRESHOLD = intPreferencesKey("auto_test_bandwidth_upload_threshold")
        private val AUTO_TEST_BANDWIDTH_THRESHOLD = intPreferencesKey("auto_test_bandwidth_threshold")
        private val AUTO_TEST_BANDWIDTH_WIFI_ONLY = booleanPreferencesKey("auto_test_bandwidth_wifi_only")
        private val AUTO_TEST_BANDWIDTH_DOWNLOAD_SIZE_MB = intPreferencesKey("auto_test_bandwidth_download_size_mb")
        private val AUTO_TEST_BANDWIDTH_UPLOAD_SIZE_MB = intPreferencesKey("auto_test_bandwidth_upload_size_mb")
        private val AUTO_TEST_BANDWIDTH_SIZE_MB = intPreferencesKey("auto_test_bandwidth_size_mb") // legacy fallback
        private val AUTO_TEST_UNLOCK_ENABLED = booleanPreferencesKey("auto_test_unlock_enabled")
        private val AUTO_TEST_BY_REGION = booleanPreferencesKey("auto_test_by_region")
        private val AUTO_TEST_NODE_LIMIT = intPreferencesKey("auto_test_node_limit")

        private val PREFER_TEST_MODES_JSON = stringPreferencesKey("prefer_test_modes_json")
        private val PREFER_TEST_SELECTED_MODE_ID = stringPreferencesKey("prefer_test_selected_mode_id")
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

    val autoTestLatencyEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AUTO_TEST_LATENCY_ENABLED] ?: true
    }

    suspend fun setAutoTestLatencyEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_TEST_LATENCY_ENABLED] = enabled
        }
    }

    val autoTestLatencyThresholdMs: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[AUTO_TEST_LATENCY_THRESHOLD] ?: 600
    }

    val autoTestLatencyMode: Flow<AutoTestLatencyMode> = context.dataStore.data.map { preferences ->
        val value = preferences[AUTO_TEST_LATENCY_MODE] ?: AutoTestLatencyMode.URL_TEST.name
        runCatching { AutoTestLatencyMode.valueOf(value) }.getOrDefault(AutoTestLatencyMode.URL_TEST)
    }

    suspend fun setAutoTestLatencyMode(mode: AutoTestLatencyMode) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_TEST_LATENCY_MODE] = mode.name
        }
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

    val autoTestBandwidthDownloadEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AUTO_TEST_BANDWIDTH_DOWNLOAD_ENABLED] ?: true
    }

    suspend fun setAutoTestBandwidthDownloadEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_TEST_BANDWIDTH_DOWNLOAD_ENABLED] = enabled
        }
    }

    val autoTestBandwidthUploadEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AUTO_TEST_BANDWIDTH_UPLOAD_ENABLED] ?: false
    }

    suspend fun setAutoTestBandwidthUploadEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_TEST_BANDWIDTH_UPLOAD_ENABLED] = enabled
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

    val autoTestBandwidthDownloadThresholdMbps: Flow<Int> = context.dataStore.data.map { preferences ->
        (preferences[AUTO_TEST_BANDWIDTH_DOWNLOAD_THRESHOLD] ?: preferences[AUTO_TEST_BANDWIDTH_THRESHOLD] ?: 10)
            .coerceAtLeast(1)
    }

    suspend fun setAutoTestBandwidthDownloadThresholdMbps(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_TEST_BANDWIDTH_DOWNLOAD_THRESHOLD] = value.coerceAtLeast(1)
        }
    }

    val autoTestBandwidthUploadThresholdMbps: Flow<Int> = context.dataStore.data.map { preferences ->
        (preferences[AUTO_TEST_BANDWIDTH_UPLOAD_THRESHOLD] ?: preferences[AUTO_TEST_BANDWIDTH_THRESHOLD] ?: 10)
            .coerceAtLeast(1)
    }

    suspend fun setAutoTestBandwidthUploadThresholdMbps(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_TEST_BANDWIDTH_UPLOAD_THRESHOLD] = value.coerceAtLeast(1)
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

    val autoTestBandwidthDownloadSizeMb: Flow<Int> = context.dataStore.data.map { preferences ->
        val value = preferences[AUTO_TEST_BANDWIDTH_DOWNLOAD_SIZE_MB]
            ?: preferences[AUTO_TEST_BANDWIDTH_SIZE_MB]
            ?: 10
        when (value) {
            1, 10, 25, 50 -> value
            else -> 10
        }
    }

    suspend fun setAutoTestBandwidthDownloadSizeMb(value: Int) {
        val normalized = when (value) {
            1, 10, 25, 50 -> value
            else -> 10
        }
        context.dataStore.edit { preferences ->
            preferences[AUTO_TEST_BANDWIDTH_DOWNLOAD_SIZE_MB] = normalized
            preferences[AUTO_TEST_BANDWIDTH_SIZE_MB] = normalized
        }
    }

    val autoTestBandwidthUploadSizeMb: Flow<Int> = context.dataStore.data.map { preferences ->
        val value = preferences[AUTO_TEST_BANDWIDTH_UPLOAD_SIZE_MB] ?: 10
        when (value) {
            1, 10, 25, 50 -> value
            else -> 10
        }
    }

    suspend fun setAutoTestBandwidthUploadSizeMb(value: Int) {
        val normalized = when (value) {
            1, 10, 25, 50 -> value
            else -> 10
        }
        context.dataStore.edit { preferences ->
            preferences[AUTO_TEST_BANDWIDTH_UPLOAD_SIZE_MB] = normalized
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

    val autoTestByRegion: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AUTO_TEST_BY_REGION] ?: false
    }

    suspend fun setAutoTestByRegion(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_TEST_BY_REGION] = enabled
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

    val preferTestModes: Flow<List<TestPreferMode>> = context.dataStore.data.map { preferences ->
        val rawJson = preferences[PREFER_TEST_MODES_JSON]
        if (rawJson.isNullOrBlank()) {
            return@map normalizePreferTestModes(emptyList())
        }
        runCatching {
            decodePreferTestModes(rawJson)
        }.getOrElse {
            normalizePreferTestModes(emptyList())
        }
    }

    suspend fun setPreferTestModes(modes: List<TestPreferMode>) {
        context.dataStore.edit { preferences ->
            preferences[PREFER_TEST_MODES_JSON] = encodePreferTestModes(normalizePreferTestModes(modes))
        }
    }

    val preferTestSelectedModeId: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[PREFER_TEST_SELECTED_MODE_ID] ?: BUILTIN_PREFER_MODE_CHAT
    }

    suspend fun setPreferTestSelectedModeId(modeId: String) {
        context.dataStore.edit { preferences ->
            preferences[PREFER_TEST_SELECTED_MODE_ID] = modeId
        }
    }

    private fun encodePreferTestModes(modes: List<TestPreferMode>): String {
        val array = JSONArray()
        modes.forEach { mode ->
            array.put(
                JSONObject().apply {
                    put("id", mode.id)
                    put("name", mode.name)
                    put("builtIn", mode.builtIn)
                    put("filterUnavailable", mode.filterUnavailable)
                    put("latencyEnabled", mode.latencyEnabled)
                    put("latencyMode", mode.latencyMode.name)
                    put("latencyThresholdMs", mode.latencyThresholdMs)
                    put("bandwidthEnabled", mode.bandwidthEnabled)
                    put("bandwidthDownloadEnabled", mode.bandwidthDownloadEnabled)
                    put("bandwidthUploadEnabled", mode.bandwidthUploadEnabled)
                    put("bandwidthDownloadThresholdMbps", mode.bandwidthDownloadThresholdMbps)
                    put("bandwidthUploadThresholdMbps", mode.bandwidthUploadThresholdMbps)
                    put("bandwidthWifiOnly", mode.bandwidthWifiOnly)
                    put("bandwidthDownloadSizeMb", mode.bandwidthDownloadSizeMb)
                    put("bandwidthUploadSizeMb", mode.bandwidthUploadSizeMb)
                    put("unlockEnabled", mode.unlockEnabled)
                    put("byRegion", mode.byRegion)
                    put("nodeLimit", mode.nodeLimit)
                    put("defaultPriority", mode.defaultPriority.name)
                    put("unlockPriorityMode", mode.unlockPriorityMode.name)
                    put("unlockPriorityTargetSiteIds", JSONArray(mode.unlockPriorityTargetSiteIds))
                }
            )
        }
        return array.toString()
    }

    private fun decodePreferTestModes(rawJson: String): List<TestPreferMode> {
        val array = JSONArray(rawJson)
        val list = mutableListOf<TestPreferMode>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString("id").trim()
            val name = obj.optString("name").trim()
            if (id.isBlank() || name.isBlank()) continue
            val latencyMode = runCatching {
                AutoTestLatencyMode.valueOf(obj.optString("latencyMode", AutoTestLatencyMode.URL_TEST.name))
            }.getOrDefault(AutoTestLatencyMode.URL_TEST)
            val defaultPriority = runCatching {
                BestNodePriority.valueOf(obj.optString("defaultPriority", BestNodePriority.LATENCY.name))
            }.getOrDefault(BestNodePriority.LATENCY)
            val unlockPriorityMode = runCatching {
                UnlockPriorityMode.valueOf(obj.optString("unlockPriorityMode", UnlockPriorityMode.COUNT.name))
            }.getOrDefault(UnlockPriorityMode.COUNT)
            val unlockPriorityTargetSiteIds = obj.optJSONArray("unlockPriorityTargetSiteIds")
                ?.let { arr ->
                    buildList {
                        for (j in 0 until arr.length()) {
                            arr.optString(j)?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
                        }
                    }
                }
                ?: emptyList()

            list += TestPreferMode(
                id = id,
                name = name,
                builtIn = obj.optBoolean("builtIn", false),
                filterUnavailable = obj.optBoolean("filterUnavailable", true),
                latencyEnabled = if (obj.has("latencyEnabled")) obj.optBoolean("latencyEnabled", true) else true,
                latencyMode = latencyMode,
                latencyThresholdMs = obj.optInt("latencyThresholdMs", 600).coerceAtLeast(50),
                bandwidthEnabled = obj.optBoolean("bandwidthEnabled", false),
                bandwidthDownloadEnabled = obj.optBoolean("bandwidthDownloadEnabled", true),
                bandwidthUploadEnabled = obj.optBoolean("bandwidthUploadEnabled", false),
                bandwidthDownloadThresholdMbps = obj.optInt("bandwidthDownloadThresholdMbps", 10).coerceAtLeast(1),
                bandwidthUploadThresholdMbps = obj.optInt("bandwidthUploadThresholdMbps", 10).coerceAtLeast(1),
                bandwidthWifiOnly = obj.optBoolean("bandwidthWifiOnly", true),
                bandwidthDownloadSizeMb = normalizeSize(obj.optInt("bandwidthDownloadSizeMb", 10)),
                bandwidthUploadSizeMb = normalizeSize(obj.optInt("bandwidthUploadSizeMb", 10)),
                unlockEnabled = obj.optBoolean("unlockEnabled", false),
                byRegion = obj.optBoolean("byRegion", false),
                nodeLimit = obj.optInt("nodeLimit", 20).coerceIn(1, 200),
                defaultPriority = defaultPriority,
                unlockPriorityMode = unlockPriorityMode,
                unlockPriorityTargetSiteIds = unlockPriorityTargetSiteIds
            )
        }
        return normalizePreferTestModes(list)
    }

    private fun normalizeSize(value: Int): Int = when (value) {
        1, 10, 25, 50 -> value
        else -> 10
    }
}

