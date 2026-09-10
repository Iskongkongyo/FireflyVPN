package xyz.a202132.app.service

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import xyz.a202132.app.AppConfig
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.VpnState
import xyz.a202132.app.data.repository.SettingsRepository
import xyz.a202132.app.network.NetworkClient
import xyz.a202132.app.util.RuntimeLog
import xyz.a202132.app.viewmodel.GlobalTestExecution
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 监测实际 VPN 出口，并在确认故障后刷新节点和恢复连接。 */
class VpnConnectivityRecoveryManager(
    context: Context,
    private val scope: CoroutineScope
) {
    private val appContext = context.applicationContext
    private val settingsRepository = SettingsRepository(appContext)
    private val refresher = NodeRecoveryRefresher(appContext)
    private val started = AtomicBoolean(false)
    private var nextAutomaticRefreshAllowedAt = 0L
    private var nextHealthCheckUrlIndex = 0

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            combine(
                settingsRepository.vpnConnectivityRecoveryEnabled,
                ServiceManager.vpnState,
                ServiceManager.currentNode
            ) { enabled, state, node -> MonitorState(enabled, state, node) }
                .collectLatest { monitorState ->
                    if (!monitorState.enabled ||
                        monitorState.vpnState != VpnState.CONNECTED ||
                        monitorState.node == null
                    ) {
                        return@collectLatest
                    }
                    monitorConnectedNode(monitorState.node)
                }
        }
    }

    private suspend fun monitorConnectedNode(originalNode: Node) {
        delay(AppConfig.VPN_HEALTH_CHECK_INTERVAL_MS)
        while (currentCoroutineContext().isActive && ServiceManager.vpnState.value == VpnState.CONNECTED) {
            if (probe204ThroughVpn()) {
                delay(AppConfig.VPN_HEALTH_CHECK_INTERVAL_MS)
                continue
            }

            var consecutiveFailures = 1
            while (consecutiveFailures < AppConfig.VPN_HEALTH_CHECK_FAILURE_THRESHOLD) {
                delay(AppConfig.VPN_HEALTH_CHECK_RETRY_DELAY_MS)
                if (probe204ThroughVpn()) break
                consecutiveFailures++
            }
            if (consecutiveFailures < AppConfig.VPN_HEALTH_CHECK_FAILURE_THRESHOLD) {
                delay(AppConfig.VPN_HEALTH_CHECK_INTERVAL_MS)
                continue
            }

            val needsSourceRefresh = refresher.hasRefreshSource(originalNode)
            val now = SystemClock.elapsedRealtime()
            if (needsSourceRefresh && now < nextAutomaticRefreshAllowedAt) {
                RuntimeLog.info(TAG, "VPN recovery refresh is cooling down")
                delay(AppConfig.VPN_HEALTH_CHECK_INTERVAL_MS)
                continue
            }

            if (needsSourceRefresh && NetworkClient.createUnderlyingNetworkClient(appContext, 1_000L) == null) {
                RuntimeLog.info(TAG, "VPN recovery is waiting for a validated underlying network")
                delay(AppConfig.VPN_HEALTH_CHECK_INTERVAL_MS)
                continue
            }

            val recovered = recoverConnection(originalNode, needsSourceRefresh)
            if (recovered) return
            delay(AppConfig.VPN_HEALTH_CHECK_INTERVAL_MS)
        }
    }

    private suspend fun probe204ThroughVpn(): Boolean = withContext(Dispatchers.IO) {
        val socksPort = BoxVpnService.getInternalSocksPort() ?: return@withContext false
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
            .connectTimeout(AppConfig.VPN_HEALTH_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(AppConfig.VPN_HEALTH_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(AppConfig.VPN_HEALTH_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(AppConfig.VPN_HEALTH_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        val urls = AppConfig.VPN_HEALTH_CHECK_URLS
        val url = urls[nextHealthCheckUrlIndex % urls.size]
        nextHealthCheckUrlIndex = (nextHealthCheckUrlIndex + 1) % urls.size
        val request = Request.Builder()
            .url(url)
            .header("Cache-Control", "no-cache")
            .header("User-Agent", AppConfig.HTTP_USER_AGENT)
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { it.code == 204 }
        }.getOrDefault(false)
    }

    private suspend fun recoverConnection(originalNode: Node, needsSourceRefresh: Boolean): Boolean {
        if (GlobalTestExecution.mutex.isLocked) {
            RuntimeLog.info(TAG, "VPN recovery postponed because another test or refresh is running")
            return false
        }
        if (!GlobalTestExecution.tryBeginFetching("连接异常恢复中")) {
            RuntimeLog.info(TAG, "VPN recovery postponed because another refresh is running")
            return false
        }
        return try {
            if (!needsSourceRefresh) {
                RuntimeLog.warn(TAG, "VPN probe failed; reconnecting favorite node without source refresh")
                ServiceManager.notifyMessage("VPN 连通性异常，正在使用收藏节点原配置重连")
                reconnect(originalNode)
                return true
            }

            nextAutomaticRefreshAllowedAt =
                SystemClock.elapsedRealtime() + AppConfig.VPN_RECOVERY_COOLDOWN_MS
            RuntimeLog.warn(TAG, "VPN 204 probe failed three times; refreshing current node source")
            ServiceManager.notifyMessage("VPN 连通性异常，正在直连刷新当前节点分组")
            val plan = refresher.prepareFor(originalNode).getOrElse { error ->
                Log.w(TAG, "Node refresh for VPN recovery failed", error)
                RuntimeLog.warn(TAG, "VPN recovery refresh failed", error)
                ServiceManager.notifyMessage("VPN 自动恢复失败：${error.message ?: "节点刷新失败"}")
                return false
            }
            if (plan == null) {
                ServiceManager.notifyMessage("当前收藏节点没有订阅来源，正在使用原配置重连")
                reconnect(originalNode)
                return true
            }

            val matchedNode = ReconnectNodeMatcher.findMatch(originalNode, plan.nodes)
            if (matchedNode == null) {
                RuntimeLog.warn(TAG, "VPN recovery could not match the previous node")
                ServiceManager.notifyMessage("未找到原连接节点，已保留旧节点数据和当前连接")
                return false
            }
            refresher.validateNodeConfiguration(matchedNode).getOrElse { error ->
                RuntimeLog.warn(TAG, "VPN recovery rejected an invalid refreshed node", error)
                ServiceManager.notifyMessage("刷新后的原节点配置无效，已保留旧节点数据和当前连接")
                return false
            }

            val reconnectNode = if (originalNode.source == xyz.a202132.app.data.model.NodeSource.FAVORITE) {
                matchedNode.copy(
                    id = originalNode.id,
                    source = originalNode.source,
                    subscriptionGroupId = originalNode.subscriptionGroupId,
                    favoriteSourceNodeId = matchedNode.id,
                    favoriteCreatedAt = originalNode.favoriteCreatedAt,
                    sortOrder = originalNode.sortOrder
                )
            } else {
                matchedNode
            }
            refresher.commit(
                plan = plan,
                refreshedFavorite = reconnectNode.takeIf {
                    originalNode.source == xyz.a202132.app.data.model.NodeSource.FAVORITE
                }
            )
            if (originalNode.source == xyz.a202132.app.data.model.NodeSource.SUBSCRIPTION) {
                settingsRepository.setSelectedNodeGroupId(plan.group.id)
            }
            settingsRepository.setSelectedNodeId(reconnectNode.id)
            RuntimeLog.info(
                TAG,
                "VPN recovery matched node by ${ReconnectNodeMatcher.matchType(originalNode, matchedNode)}"
            )
            ServiceManager.notifyMessage("已匹配 ${reconnectNode.name}，正在自动重连")
            reconnect(reconnectNode)
            true
        } finally {
            GlobalTestExecution.endFetching()
        }
    }

    private suspend fun reconnect(node: Node) {
        settingsRepository.setSelectedNodeId(node.id)
        val proxyMode = settingsRepository.proxyMode.first()
        ServiceManager.startVpn(appContext, node, proxyMode)
    }

    private data class MonitorState(
        val enabled: Boolean,
        val vpnState: VpnState,
        val node: Node?
    )

    private companion object {
        const val TAG = "VpnRecovery"
    }
}

internal object ReconnectNodeMatcher {
    fun findMatch(original: Node, candidates: List<Node>): Node? {
        val exactConfigurationMatches = candidates.filter {
            normalizedConfiguration(it) == normalizedConfiguration(original)
        }
        if (exactConfigurationMatches.size == 1) return exactConfigurationMatches.single()

        val endpointMatches = candidates.filter {
            it.type == original.type &&
                normalizeServer(it.server) == normalizeServer(original.server) &&
                it.port == original.port
        }
        if (endpointMatches.size == 1) return endpointMatches.single()
        return endpointMatches.filter { sameName(it, original) }.singleOrNull()
    }

    fun matchType(original: Node, matched: Node): String =
        if (normalizedConfiguration(original) == normalizedConfiguration(matched)) {
            "configuration"
        } else {
            "protocol/address/port"
        }

    private fun sameName(first: Node, second: Node): Boolean =
        first.getDisplayName().trim().equals(second.getDisplayName().trim(), ignoreCase = true)

    private fun normalizeServer(value: String): String =
        value.trim().removePrefix("[").removeSuffix("]").lowercase()

    private fun normalizedConfiguration(node: Node): String =
        "${node.type.protocol}|${node.getRawLinkPlain().trim().substringBefore('#')}"
}
