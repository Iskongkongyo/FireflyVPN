package xyz.a202132.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import xyz.a202132.app.data.model.AppThemeMode
import xyz.a202132.app.data.model.IPv6RoutingMode
import xyz.a202132.app.data.model.NodeListCategory
import xyz.a202132.app.data.model.DEFAULT_SUBSCRIPTION_GROUP_ID
import xyz.a202132.app.data.model.FAVORITES_NODE_GROUP_ID
import xyz.a202132.app.data.model.PerAppProxyMode
import xyz.a202132.app.data.model.ProxyMode
import xyz.a202132.app.data.model.TunStackMode
import xyz.a202132.app.rules.model.RuleRoutingOptions
import xyz.a202132.app.viewmodel.AutoTestLatencyMode
import xyz.a202132.app.viewmodel.BestNodePriority
import xyz.a202132.app.viewmodel.BestNodeWeights
import xyz.a202132.app.viewmodel.BUILTIN_PREFER_MODE_CHAT
import xyz.a202132.app.viewmodel.StartupDefaultTestMode
import xyz.a202132.app.viewmodel.TestPreferMode
import xyz.a202132.app.viewmodel.PreferRankingMode
import xyz.a202132.app.viewmodel.UnlockPriorityMode
import xyz.a202132.app.viewmodel.normalizePreferTestModes
import xyz.a202132.app.viewmodel.normalizePriorityOrder

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    
    companion object {
        private val SELECTED_NODE_ID = stringPreferencesKey("selected_node_id")
        private val APP_THEME_MODE = stringPreferencesKey("app_theme_mode")
        private val PROXY_MODE = stringPreferencesKey("proxy_mode")
        private val LAST_NOTICE_ID = stringPreferencesKey("last_notice_id")
        private val IGNORED_UPDATE_VERSION_CODE = intPreferencesKey("ignored_update_version_code")
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
        private val TUN_STACK_MODE = stringPreferencesKey("tun_stack_mode")
        
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
        private val STARTUP_DEFAULT_TEST_MODE = stringPreferencesKey("startup_default_test_mode")
        private val STARTUP_DEFAULT_TEST_CHOICE_DONE = booleanPreferencesKey("startup_default_test_choice_done")
        private val REMEMBER_LAST_SELECTED_NODE_ENABLED = booleanPreferencesKey("remember_last_selected_node_enabled")
        private val LAST_SELECTED_NODE_ID = stringPreferencesKey("last_selected_node_id")
        private val NODE_LIST_CATEGORY = stringPreferencesKey("node_list_category")
        private val SELECTED_NODE_GROUP_ID = stringPreferencesKey("selected_node_group_id")
        private val FAVORITES_GROUP_SORT_ORDER = intPreferencesKey("favorites_group_sort_order")
        private val BUILT_IN_GROUP_IDS_INITIALIZED = booleanPreferencesKey("built_in_group_ids_initialized")
        private val KNOWN_BUILT_IN_GROUP_IDS = stringSetPreferencesKey("known_built_in_group_ids")
        private val UNREAD_BUILT_IN_GROUP_IDS = stringSetPreferencesKey("unread_built_in_group_ids")
        private val NODE_IP_INFO_TEST_ON_VPN_START = booleanPreferencesKey("node_ip_info_test_on_vpn_start")
        private val VPN_CONNECTIVITY_RECOVERY_ENABLED = booleanPreferencesKey("vpn_connectivity_recovery_enabled")
        private val TCPING_TEST_TIMEOUT_MS = longPreferencesKey("tcping_test_timeout_ms")
        private val URL_TEST_TIMEOUT_MS = longPreferencesKey("url_test_timeout_ms")
        private val NODE_IP_INFO_TIMEOUT_MS = longPreferencesKey("node_ip_info_timeout_ms")
        private val SPEED_TEST_DOWNLOAD_TIMEOUT_MS = longPreferencesKey("speed_test_download_timeout_ms")
        private val TCPING_CONCURRENCY = intPreferencesKey("tcping_concurrency")
        private val URL_TEST_CONCURRENCY = intPreferencesKey("url_test_concurrency")
        private val BANDWIDTH_TEST_CONCURRENCY = intPreferencesKey("bandwidth_test_concurrency")
        private val UNLOCK_TEST_CONCURRENCY = intPreferencesKey("unlock_test_concurrency")
        private val VPN_MTU = intPreferencesKey("vpn_mtu")
        private val HYSTERIA2_UPLOAD_MBPS = intPreferencesKey("hysteria2_upload_mbps")
        private val HYSTERIA2_DOWNLOAD_MBPS = intPreferencesKey("hysteria2_download_mbps")
        private val ANTI_AD_ENABLED = booleanPreferencesKey("anti_ad_enabled")
        private val AD_RULES_ENABLED = booleanPreferencesKey("ad_rules_enabled")
        private val BLOCK_QUIC_ENABLED = booleanPreferencesKey("block_quic_enabled")

        private val LAN_PROXY_ENABLED = booleanPreferencesKey("lan_proxy_enabled")
        private val LAN_PROXY_AUTO_PORT = booleanPreferencesKey("lan_proxy_auto_port")
        private val LAN_PROXY_PORT = intPreferencesKey("lan_proxy_port")
        private val LAN_PROXY_AUTH_ENABLED = booleanPreferencesKey("lan_proxy_auth_enabled")
        private val LAN_PROXY_USERNAME = stringPreferencesKey("lan_proxy_username")
        private val LAN_PROXY_PASSWORD = stringPreferencesKey("lan_proxy_password")
    }
    
    val selectedNodeId: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[SELECTED_NODE_ID]
    }

    val appThemeMode: Flow<AppThemeMode> = context.dataStore.data.map { preferences ->
        runCatching {
            AppThemeMode.valueOf(preferences[APP_THEME_MODE] ?: AppThemeMode.SYSTEM.name)
        }.getOrDefault(AppThemeMode.SYSTEM)
    }

    val nodeListCategory: Flow<NodeListCategory> = context.dataStore.data.map { preferences ->
        runCatching {
            NodeListCategory.valueOf(preferences[NODE_LIST_CATEGORY] ?: NodeListCategory.PRIMARY.name)
        }.getOrDefault(NodeListCategory.PRIMARY)
    }

    val selectedNodeGroupId: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[SELECTED_NODE_GROUP_ID] ?: if (
            preferences[NODE_LIST_CATEGORY] == NodeListCategory.FAVORITES.name
        ) FAVORITES_NODE_GROUP_ID else DEFAULT_SUBSCRIPTION_GROUP_ID
    }

    val favoritesGroupSortOrder: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[FAVORITES_GROUP_SORT_ORDER] ?: Int.MAX_VALUE
    }

    val unreadBuiltInGroupIds: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[UNREAD_BUILT_IN_GROUP_IDS] ?: emptySet()
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

    val ignoredUpdateVersionCode: Flow<Int?> = context.dataStore.data.map { preferences ->
        preferences[IGNORED_UPDATE_VERSION_CODE]
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
                if (preferences[REMEMBER_LAST_SELECTED_NODE_ENABLED] ?: true) {
                    preferences[LAST_SELECTED_NODE_ID] = nodeId
                }
            }
        }
    }

    suspend fun setAppThemeMode(mode: AppThemeMode) {
        context.dataStore.edit { preferences ->
            preferences[APP_THEME_MODE] = mode.name
        }
    }

    suspend fun setNodeListCategory(category: NodeListCategory) {
        context.dataStore.edit { preferences ->
            preferences[NODE_LIST_CATEGORY] = category.name
        }
    }

    suspend fun setSelectedNodeGroupId(groupId: String) {
        context.dataStore.edit { preferences ->
            preferences[SELECTED_NODE_GROUP_ID] = groupId
            preferences[NODE_LIST_CATEGORY] = if (groupId == FAVORITES_NODE_GROUP_ID) {
                NodeListCategory.FAVORITES.name
            } else {
                NodeListCategory.PRIMARY.name
            }
            preferences[UNREAD_BUILT_IN_GROUP_IDS] =
                (preferences[UNREAD_BUILT_IN_GROUP_IDS] ?: emptySet()) - groupId
        }
    }

    /**
     * 记录服务端已经返回过的内置分组 ID。第一次成功获取只建立基线，之后才返回新增 ID。
     */
    suspend fun recordBuiltInGroupIds(groupIds: Set<String>): Set<String> {
        var newlyAddedIds = emptySet<String>()
        context.dataStore.edit { preferences ->
            val initialized = preferences[BUILT_IN_GROUP_IDS_INITIALIZED] ?: false
            val knownIds = preferences[KNOWN_BUILT_IN_GROUP_IDS] ?: emptySet()
            if (initialized) {
                newlyAddedIds = groupIds - knownIds
                if (newlyAddedIds.isNotEmpty()) {
                    preferences[UNREAD_BUILT_IN_GROUP_IDS] =
                        (preferences[UNREAD_BUILT_IN_GROUP_IDS] ?: emptySet()) + newlyAddedIds
                }
            }
            preferences[KNOWN_BUILT_IN_GROUP_IDS] = knownIds + groupIds
            preferences[BUILT_IN_GROUP_IDS_INITIALIZED] = true
        }
        return newlyAddedIds
    }

    suspend fun setFavoritesGroupSortOrder(sortOrder: Int) {
        context.dataStore.edit { preferences ->
            preferences[FAVORITES_GROUP_SORT_ORDER] = sortOrder.coerceAtLeast(0)
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

    suspend fun setIgnoredUpdateVersionCode(versionCode: Int) {
        context.dataStore.edit { preferences ->
            preferences[IGNORED_UPDATE_VERSION_CODE] = versionCode
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

    val tunStackMode: Flow<TunStackMode> = context.dataStore.data.map { preferences ->
        runCatching {
            TunStackMode.valueOf(preferences[TUN_STACK_MODE] ?: TunStackMode.GVISOR.name)
        }.getOrDefault(TunStackMode.GVISOR)
    }

    suspend fun setTunStackMode(mode: TunStackMode) {
        context.dataStore.edit { preferences ->
            preferences[TUN_STACK_MODE] = mode.name
        }
    }
    
    val lanProxyEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[LAN_PROXY_ENABLED] ?: false
    }

    suspend fun setLanProxyEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[LAN_PROXY_ENABLED] = enabled
        }
    }

    val lanProxyAutoPort: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[LAN_PROXY_AUTO_PORT] ?: true
    }

    suspend fun setLanProxyAutoPort(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[LAN_PROXY_AUTO_PORT] = enabled
        }
    }

    val lanProxyPort: Flow<Int> = context.dataStore.data.map { preferences ->
        (preferences[LAN_PROXY_PORT] ?: xyz.a202132.app.AppConfig.LAN_PROXY_DEFAULT_PORT)
            .coerceIn(xyz.a202132.app.AppConfig.LAN_PROXY_MIN_PORT, xyz.a202132.app.AppConfig.LAN_PROXY_MAX_PORT)
    }

    suspend fun setLanProxyPort(port: Int) {
        context.dataStore.edit { preferences ->
            preferences[LAN_PROXY_PORT] = port.coerceIn(
                xyz.a202132.app.AppConfig.LAN_PROXY_MIN_PORT,
                xyz.a202132.app.AppConfig.LAN_PROXY_MAX_PORT
            )
        }
    }

    val lanProxyAuthEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[LAN_PROXY_AUTH_ENABLED] ?: false
    }

    suspend fun setLanProxyAuthEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[LAN_PROXY_AUTH_ENABLED] = enabled
        }
    }

    val lanProxyUsername: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[LAN_PROXY_USERNAME]?.takeIf { it.isNotBlank() } ?: "firefly"
    }

    suspend fun setLanProxyUsername(username: String) {
        context.dataStore.edit { preferences ->
            preferences[LAN_PROXY_USERNAME] = username.trim().ifBlank { "firefly" }
        }
    }

    val lanProxyPassword: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[LAN_PROXY_PASSWORD]?.takeIf { it.isNotBlank() } ?: "firefly"
    }

    suspend fun setLanProxyPassword(password: String) {
        context.dataStore.edit { preferences ->
            preferences[LAN_PROXY_PASSWORD] = password.trim().ifBlank { "firefly" }
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

    suspend fun applyPreferTestMode(mode: TestPreferMode) {
        context.dataStore.edit { preferences ->
            preferences[PREFER_TEST_SELECTED_MODE_ID] = mode.id
            preferences[AUTO_TEST_FILTER_UNAVAILABLE] = mode.filterUnavailable
            preferences[AUTO_TEST_LATENCY_ENABLED] = mode.latencyEnabled
            preferences[AUTO_TEST_LATENCY_MODE] = mode.latencyMode.name
            preferences[AUTO_TEST_LATENCY_THRESHOLD] = mode.latencyThresholdMs.coerceAtLeast(50)
            preferences[AUTO_TEST_BANDWIDTH_ENABLED] = mode.bandwidthEnabled
            preferences[AUTO_TEST_BANDWIDTH_DOWNLOAD_ENABLED] = mode.bandwidthDownloadEnabled
            preferences[AUTO_TEST_BANDWIDTH_UPLOAD_ENABLED] = mode.bandwidthUploadEnabled
            preferences[AUTO_TEST_BANDWIDTH_DOWNLOAD_THRESHOLD] =
                mode.bandwidthDownloadThresholdMbps.coerceAtLeast(1)
            preferences[AUTO_TEST_BANDWIDTH_UPLOAD_THRESHOLD] =
                mode.bandwidthUploadThresholdMbps.coerceAtLeast(1)
            preferences[AUTO_TEST_BANDWIDTH_WIFI_ONLY] = mode.bandwidthWifiOnly
            preferences[AUTO_TEST_BANDWIDTH_DOWNLOAD_SIZE_MB] = normalizeSize(mode.bandwidthDownloadSizeMb)
            preferences[AUTO_TEST_BANDWIDTH_UPLOAD_SIZE_MB] = normalizeSize(mode.bandwidthUploadSizeMb)
            preferences[AUTO_TEST_UNLOCK_ENABLED] = mode.unlockEnabled
            preferences[AUTO_TEST_BY_REGION] = mode.byRegion
            preferences[AUTO_TEST_NODE_LIMIT] = mode.nodeLimit.coerceIn(1, 200)
        }
    }

    val startupDefaultTestMode: Flow<StartupDefaultTestMode> = context.dataStore.data.map { preferences ->
        val value = preferences[STARTUP_DEFAULT_TEST_MODE] ?: StartupDefaultTestMode.NONE.name
        runCatching { StartupDefaultTestMode.valueOf(value) }.getOrDefault(StartupDefaultTestMode.NONE)
    }

    suspend fun setStartupDefaultTestMode(mode: StartupDefaultTestMode) {
        context.dataStore.edit { preferences ->
            preferences[STARTUP_DEFAULT_TEST_MODE] = mode.name
        }
    }

    val startupDefaultTestChoiceDone: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[STARTUP_DEFAULT_TEST_CHOICE_DONE] ?: false
    }

    suspend fun setStartupDefaultTestChoiceDone(done: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[STARTUP_DEFAULT_TEST_CHOICE_DONE] = done
        }
    }

    val rememberLastSelectedNodeEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[REMEMBER_LAST_SELECTED_NODE_ENABLED] ?: true
    }

    suspend fun setRememberLastSelectedNodeEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[REMEMBER_LAST_SELECTED_NODE_ENABLED] = enabled
            if (!enabled) {
                preferences.remove(LAST_SELECTED_NODE_ID)
            } else {
                preferences[SELECTED_NODE_ID]?.let { preferences[LAST_SELECTED_NODE_ID] = it }
            }
        }
    }

    val lastSelectedNodeId: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[LAST_SELECTED_NODE_ID]
    }

    suspend fun setLastSelectedNodeId(nodeId: String?) {
        context.dataStore.edit { preferences ->
            if (nodeId == null) {
                preferences.remove(LAST_SELECTED_NODE_ID)
            } else {
                preferences[LAST_SELECTED_NODE_ID] = nodeId
            }
        }
    }

    val nodeIpInfoTestOnVpnStart: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[NODE_IP_INFO_TEST_ON_VPN_START] ?: false
    }

    suspend fun setNodeIpInfoTestOnVpnStart(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[NODE_IP_INFO_TEST_ON_VPN_START] = enabled
        }
    }

    val vpnConnectivityRecoveryEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[VPN_CONNECTIVITY_RECOVERY_ENABLED]
            ?: xyz.a202132.app.AppConfig.VPN_CONNECTIVITY_RECOVERY_DEFAULT_ENABLED
    }

    suspend fun setVpnConnectivityRecoveryEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[VPN_CONNECTIVITY_RECOVERY_ENABLED] = enabled
        }
    }

    val hysteria2UploadMbps: Flow<Int> = context.dataStore.data.map { preferences ->
        (preferences[HYSTERIA2_UPLOAD_MBPS] ?: xyz.a202132.app.AppConfig.HYSTERIA2_DEFAULT_BANDWIDTH_MBPS)
            .coerceIn(
                xyz.a202132.app.AppConfig.HYSTERIA2_MIN_BANDWIDTH_MBPS,
                xyz.a202132.app.AppConfig.HYSTERIA2_MAX_BANDWIDTH_MBPS
            )
    }

    val hysteria2DownloadMbps: Flow<Int> = context.dataStore.data.map { preferences ->
        (preferences[HYSTERIA2_DOWNLOAD_MBPS] ?: xyz.a202132.app.AppConfig.HYSTERIA2_DEFAULT_BANDWIDTH_MBPS)
            .coerceIn(
                xyz.a202132.app.AppConfig.HYSTERIA2_MIN_BANDWIDTH_MBPS,
                xyz.a202132.app.AppConfig.HYSTERIA2_MAX_BANDWIDTH_MBPS
            )
    }

    suspend fun setHysteria2Bandwidth(uploadMbps: Int, downloadMbps: Int) {
        context.dataStore.edit { preferences ->
            preferences[HYSTERIA2_UPLOAD_MBPS] = uploadMbps.coerceIn(
                xyz.a202132.app.AppConfig.HYSTERIA2_MIN_BANDWIDTH_MBPS,
                xyz.a202132.app.AppConfig.HYSTERIA2_MAX_BANDWIDTH_MBPS
            )
            preferences[HYSTERIA2_DOWNLOAD_MBPS] = downloadMbps.coerceIn(
                xyz.a202132.app.AppConfig.HYSTERIA2_MIN_BANDWIDTH_MBPS,
                xyz.a202132.app.AppConfig.HYSTERIA2_MAX_BANDWIDTH_MBPS
            )
        }
    }

    val antiAdEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[ANTI_AD_ENABLED] ?: true
    }

    val adRulesEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AD_RULES_ENABLED] ?: false
    }

    val blockQuicEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[BLOCK_QUIC_ENABLED] ?: false
    }

    val ruleRoutingOptions: Flow<RuleRoutingOptions> = combine(
        antiAdEnabled,
        adRulesEnabled,
        blockQuicEnabled
    ) { antiAd, adRules, blockQuic ->
        RuleRoutingOptions(antiAd, adRules, blockQuic)
    }

    suspend fun setAntiAdEnabled(enabled: Boolean) {
        context.dataStore.edit { it[ANTI_AD_ENABLED] = enabled }
    }

    suspend fun setAdRulesEnabled(enabled: Boolean) {
        context.dataStore.edit { it[AD_RULES_ENABLED] = enabled }
    }

    suspend fun setBlockQuicEnabled(enabled: Boolean) {
        context.dataStore.edit { it[BLOCK_QUIC_ENABLED] = enabled }
    }

    val tcpingTestTimeoutMs: Flow<Long> = context.dataStore.data.map { preferences ->
        (preferences[TCPING_TEST_TIMEOUT_MS] ?: xyz.a202132.app.AppConfig.TCPING_TEST_TIMEOUT).coerceAtLeast(500L)
    }

    suspend fun setTcpingTestTimeoutMs(value: Long) {
        context.dataStore.edit { preferences ->
            preferences[TCPING_TEST_TIMEOUT_MS] = value.coerceAtLeast(500L)
        }
    }

    val urlTestTimeoutMs: Flow<Long> = context.dataStore.data.map { preferences ->
        (preferences[URL_TEST_TIMEOUT_MS] ?: xyz.a202132.app.AppConfig.URL_TEST_TIMEOUT).coerceAtLeast(500L)
    }

    suspend fun setUrlTestTimeoutMs(value: Long) {
        context.dataStore.edit { preferences ->
            preferences[URL_TEST_TIMEOUT_MS] = value.coerceAtLeast(500L)
        }
    }

    val nodeIpInfoTimeoutMs: Flow<Long> = context.dataStore.data.map { preferences ->
        (preferences[NODE_IP_INFO_TIMEOUT_MS] ?: xyz.a202132.app.AppConfig.NODE_IP_INFO_TIMEOUT_MS).coerceAtLeast(1000L)
    }

    suspend fun setNodeIpInfoTimeoutMs(value: Long) {
        context.dataStore.edit { preferences ->
            preferences[NODE_IP_INFO_TIMEOUT_MS] = value.coerceAtLeast(1000L)
        }
    }

    val speedTestDownloadTimeoutMs: Flow<Long> = context.dataStore.data.map { preferences ->
        (preferences[SPEED_TEST_DOWNLOAD_TIMEOUT_MS] ?: xyz.a202132.app.AppConfig.AUTO_TEST_BANDWIDTH_DOWNLOAD_TIMEOUT_MS)
            .coerceAtLeast(0L)
    }

    suspend fun setSpeedTestDownloadTimeoutMs(value: Long) {
        context.dataStore.edit { preferences ->
            preferences[SPEED_TEST_DOWNLOAD_TIMEOUT_MS] = value.coerceAtLeast(0L)
        }
    }

    val tcpingConcurrency: Flow<Int> = context.dataStore.data.map { preferences ->
        (preferences[TCPING_CONCURRENCY] ?: xyz.a202132.app.AppConfig.TCPING_CONCURRENCY).coerceIn(1, 128)
    }

    suspend fun setTcpingConcurrency(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[TCPING_CONCURRENCY] = value.coerceIn(1, 128)
        }
    }

    val urlTestConcurrency: Flow<Int> = context.dataStore.data.map { preferences ->
        (preferences[URL_TEST_CONCURRENCY] ?: xyz.a202132.app.AppConfig.URL_TEST_CONCURRENCY).coerceIn(1, 128)
    }

    suspend fun setUrlTestConcurrency(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[URL_TEST_CONCURRENCY] = value.coerceIn(1, 128)
        }
    }

    val bandwidthTestConcurrency: Flow<Int> = context.dataStore.data.map { preferences ->
        (preferences[BANDWIDTH_TEST_CONCURRENCY] ?: 1).coerceIn(1, 3)
    }

    suspend fun setBandwidthTestConcurrency(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[BANDWIDTH_TEST_CONCURRENCY] = value.coerceIn(1, 3)
        }
    }

    val unlockTestConcurrency: Flow<Int> = context.dataStore.data.map { preferences ->
        (preferences[UNLOCK_TEST_CONCURRENCY] ?: xyz.a202132.app.AppConfig.AUTO_TEST_UNLOCK_CONCURRENCY).coerceIn(1, 32)
    }

    suspend fun setUnlockTestConcurrency(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[UNLOCK_TEST_CONCURRENCY] = value.coerceIn(1, 32)
        }
    }

    val vpnMtu: Flow<Int> = context.dataStore.data.map { preferences ->
        (preferences[VPN_MTU] ?: xyz.a202132.app.AppConfig.VPN_MTU).coerceIn(576, 9000)
    }

    suspend fun setVpnMtu(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[VPN_MTU] = value.coerceIn(576, 9000)
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
                    put("priorityOrder", JSONArray(mode.priorityOrder.map { it.name }))
                    put("rankingMode", mode.rankingMode.name)
                    put("latencyWeight", mode.priorityWeights.latency)
                    put("uploadWeight", mode.priorityWeights.upload)
                    put("downloadWeight", mode.priorityWeights.download)
                    put("unlockWeight", mode.priorityWeights.unlock)
                    put("unlockPriorityMode", mode.unlockPriorityMode.name)
                    put("unlockPriorityTargetSiteIds", JSONArray(mode.unlockPriorityTargetSiteIds))
                    put("autoConnectBest", mode.autoConnectBest)
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
            val priorityOrder = obj.optJSONArray("priorityOrder")
                ?.let { arr ->
                    buildList {
                        for (j in 0 until arr.length()) {
                            runCatching { BestNodePriority.valueOf(arr.optString(j)) }
                                .getOrNull()
                                ?.let(::add)
                        }
                    }
                }
                ?.takeIf { it.isNotEmpty() }
                ?: listOf(defaultPriority)
            val rankingMode = runCatching {
                PreferRankingMode.valueOf(
                    obj.optString("rankingMode", PreferRankingMode.PRIORITY_ORDER.name)
                )
            }.getOrDefault(PreferRankingMode.PRIORITY_ORDER)
            val priorityWeights = BestNodeWeights(
                latency = obj.optInt("latencyWeight", 40),
                upload = obj.optInt("uploadWeight", 10),
                download = obj.optInt("downloadWeight", 30),
                unlock = obj.optInt("unlockWeight", 20)
            ).normalized()
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
                priorityOrder = priorityOrder,
                rankingMode = rankingMode,
                priorityWeights = priorityWeights,
                unlockPriorityMode = unlockPriorityMode,
                unlockPriorityTargetSiteIds = unlockPriorityTargetSiteIds,
                autoConnectBest = obj.optBoolean("autoConnectBest", false)
            ).normalizePriorityOrder()
        }
        return normalizePreferTestModes(list)
    }

    private fun normalizeSize(value: Int): Int = when (value) {
        1, 10, 25, 50 -> value
        else -> 10
    }
}

