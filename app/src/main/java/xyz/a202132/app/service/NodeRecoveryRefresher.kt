package xyz.a202132.app.service

import android.content.Context
import androidx.room.withTransaction
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import xyz.a202132.app.AppConfig
import xyz.a202132.app.data.local.AppDatabase
import xyz.a202132.app.data.model.BUILT_IN_SUBSCRIPTION_GROUP_PREFIX
import xyz.a202132.app.data.model.DEFAULT_SUBSCRIPTION_GROUP_ID
import xyz.a202132.app.data.model.FAVORITES_NODE_GROUP_ID
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeSource
import xyz.a202132.app.data.model.SubscriptionGroup
import xyz.a202132.app.data.model.builtInSubscriptionGroupId
import xyz.a202132.app.data.model.isBuiltInSubscriptionGroup
import xyz.a202132.app.data.repository.SettingsRepository
import xyz.a202132.app.network.NetworkClient
import xyz.a202132.app.network.SubscriptionParser
import xyz.a202132.app.network.isFireflyAccessBanned
import xyz.a202132.app.util.SingBoxConfigGenerator
import java.io.IOException
import java.util.UUID

internal data class NodeRecoveryRefreshPlan(
    val group: SubscriptionGroup,
    val nodes: List<Node>
)

/** 只供 VPN 故障恢复使用；网络请求绑定到底层非 VPN 网络。 */
internal class NodeRecoveryRefresher(context: Context) {
    private val appContext = context.applicationContext
    private val database = AppDatabase.getInstance(appContext)
    private val nodeDao = database.nodeDao()
    private val subscriptionDao = database.subscriptionDao()
    private val settingsRepository = SettingsRepository(appContext)

    /**
     * 先在内存中准备刷新结果，不修改数据库。
     * 返回 null 表示收藏节点没有可刷新的订阅来源，应直接使用原配置重连。
     */
    suspend fun prepareFor(originalNode: Node): Result<NodeRecoveryRefreshPlan?> = runCatching {
        val groupId = resolveSourceGroupId(originalNode)
        if (groupId == null) {
            if (originalNode.source == NodeSource.FAVORITE) return@runCatching null
            throw IOException("当前节点没有可刷新的订阅来源")
        }
        if (originalNode.source == NodeSource.FAVORITE &&
            !isBuiltInSubscriptionGroup(groupId) &&
            subscriptionDao.getLinksForGroup(groupId).isEmpty()
        ) {
            return@runCatching null
        }

        val client = NetworkClient.createUnderlyingNetworkClient(
            appContext,
            AppConfig.NODE_REQUEST_TIMEOUT_MS
        ) ?: throw IOException("设备当前没有可用的底层网络")
        val parser = SubscriptionParser(appContext, client)

        if (isBuiltInSubscriptionGroup(groupId)) {
            prepareBuiltInGroup(groupId, parser)
        } else {
            prepareSubscriptionGroup(groupId, parser)
        }
    }

    suspend fun hasRefreshSource(originalNode: Node): Boolean {
        val groupId = resolveSourceGroupId(originalNode) ?: return false
        return isBuiltInSubscriptionGroup(groupId) ||
            originalNode.source != NodeSource.FAVORITE ||
            subscriptionDao.getLinksForGroup(groupId).isNotEmpty()
    }

    /** 匹配成功后才一次性提交分组节点及可选的收藏节点更新。 */
    suspend fun commit(plan: NodeRecoveryRefreshPlan, refreshedFavorite: Node? = null) {
        database.withTransaction {
            subscriptionDao.insertGroup(plan.group)
            nodeDao.replaceSubscriptionNodes(plan.group.id, plan.nodes)
            if (refreshedFavorite != null) {
                nodeDao.insertNode(refreshedFavorite)
            }
        }
    }

    fun validateNodeConfiguration(node: Node): Result<Unit> = runCatching {
        Libbox.checkConfig(SingBoxConfigGenerator().generateTestConfig(node))
    }

    private suspend fun resolveSourceGroupId(node: Node): String? {
        if (node.source == NodeSource.SUBSCRIPTION) {
            return node.subscriptionGroupId
                ?: settingsRepository.selectedNodeGroupId.first()
                    .takeUnless { it == FAVORITES_NODE_GROUP_ID }
        }

        val sourceNode = node.favoriteSourceNodeId?.let { nodeDao.getNodeById(it) }
        return sourceNode
            ?.takeIf { it.source == NodeSource.SUBSCRIPTION }
            ?.subscriptionGroupId
    }

    private suspend fun prepareSubscriptionGroup(
        groupId: String,
        parser: SubscriptionParser
    ): NodeRecoveryRefreshPlan {
        val group = subscriptionDao.getGroup(groupId)
            ?: throw IOException("订阅分组不存在")
        val links = subscriptionDao.getLinksForGroup(groupId)
        if (links.isEmpty()) throw IOException("当前订阅没有可用链接")
        val userAgent = group.userAgent.trim().ifBlank { AppConfig.HTTP_USER_AGENT }

        val results = coroutineScope {
            links.map { link ->
                async {
                    var result = parser.fetchAndParse(link.url, userAgent)
                    if (result.isFailure && !result.exceptionOrNull().isFireflyAccessBanned()) {
                        result = parser.fetchAndParse(link.url, userAgent)
                    }
                    link to result
                }
            }.awaitAll()
        }
        val successful = results.filter { it.second.isSuccess }
        if (successful.isEmpty()) {
            val error = results.firstNotNullOfOrNull { it.second.exceptionOrNull() }
            throw IOException(error?.message ?: "订阅刷新失败", error)
        }

        val candidates = successful.flatMap { (link, result) ->
            result.getOrThrow().mapIndexed { index, node -> Triple(link, index, node) }
        }
        val merged = if (group.deduplicateEnabled) {
            candidates.distinctBy { it.third.getRawLinkPlain().trim() }
        } else {
            candidates
        }
        val refreshedNodes = merged.mapIndexed { sortOrder, (link, index, node) ->
            val identity = if (group.deduplicateEnabled) node.id else "${link.id}|$index|${node.id}"
            node.copy(
                id = stableSubscriptionNodeId(groupId, identity),
                source = NodeSource.SUBSCRIPTION,
                subscriptionGroupId = groupId,
                favoriteSourceNodeId = null,
                favoriteCreatedAt = 0L,
                sortOrder = sortOrder
            )
        }
        val failedCount = results.size - successful.size
        return NodeRecoveryRefreshPlan(
            group = group.copy(
                lastUpdatedAt = System.currentTimeMillis(),
                lastError = failedCount.takeIf { it > 0 }
                    ?.let { "$it/${results.size} 个链接更新失败" },
                lastNodeCount = refreshedNodes.size
            ),
            nodes = refreshedNodes
        )
    }

    private suspend fun prepareBuiltInGroup(
        requestedGroupId: String,
        parser: SubscriptionParser
    ): NodeRecoveryRefreshPlan {
        val requestedSourceId = requestedGroupId
            .takeIf { it.startsWith(BUILT_IN_SUBSCRIPTION_GROUP_PREFIX) }
            ?.removePrefix(BUILT_IN_SUBSCRIPTION_GROUP_PREFIX)
        var result = parser.fetchAndParseFireflyCatalogGroup(requestedSourceId)
        if (result.isFailure && !result.exceptionOrNull().isFireflyAccessBanned()) {
            result = parser.fetchAndParseFireflyCatalogGroup(requestedSourceId)
        }
        val entry = result.getOrElse { throw IOException("内置节点接口刷新失败", it) }
        entry.error?.let { throw IOException("当前内置节点分组刷新失败", it) }
        val parsedNodes = entry.nodes?.distinctBy { it.getRawLinkPlain().trim() }
            ?: throw IOException("当前内置节点分组没有可用节点")
        val resolvedGroupId = builtInSubscriptionGroupId(entry.sourceId)
        if (requestedSourceId != null && resolvedGroupId != requestedGroupId) {
            throw IOException("内置节点分组 ID 不匹配")
        }

        val existingGroups = subscriptionDao.getGroupsOnce()
        val existing = subscriptionDao.getGroup(resolvedGroupId)
            ?: subscriptionDao.getGroup(requestedGroupId)
        val group = SubscriptionGroup(
            id = resolvedGroupId,
            name = entry.name.trim().ifBlank { "节点分组" }.take(80),
            autoUpdateEnabled = false,
            updateIntervalMinutes = existing?.updateIntervalMinutes ?: 360,
            deduplicateEnabled = true,
            userAgent = "",
            lastUpdatedAt = System.currentTimeMillis(),
            lastError = null,
            lastNodeCount = parsedNodes.size,
            sortOrder = existing?.sortOrder
                ?: (existingGroups.maxOfOrNull { it.sortOrder } ?: -1) + 1,
            createdAt = existing?.createdAt ?: System.currentTimeMillis()
        )
        val nodes = parsedNodes.mapIndexed { index, node ->
            node.copy(
                id = stableSubscriptionNodeId(resolvedGroupId, node.id),
                source = NodeSource.SUBSCRIPTION,
                subscriptionGroupId = resolvedGroupId,
                favoriteSourceNodeId = null,
                favoriteCreatedAt = 0L,
                sortOrder = index
            )
        }
        return NodeRecoveryRefreshPlan(group, nodes)
    }

    private fun stableSubscriptionNodeId(groupId: String, identity: String): String =
        if (groupId == DEFAULT_SUBSCRIPTION_GROUP_ID && '|' !in identity) {
            identity
        } else {
            "sub_${UUID.nameUUIDFromBytes("$groupId|$identity".toByteArray(Charsets.UTF_8))}"
        }
}
