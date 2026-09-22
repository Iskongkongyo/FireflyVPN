package xyz.a202132.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.SubscriptionGroup
import xyz.a202132.app.data.model.SubscriptionLink

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscription_groups ORDER BY sortOrder ASC, createdAt ASC")
    fun getGroups(): Flow<List<SubscriptionGroup>>

    @Query("SELECT * FROM subscription_groups ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun getGroupsOnce(): List<SubscriptionGroup>

    @Query("SELECT * FROM subscription_groups WHERE id = :groupId LIMIT 1")
    suspend fun getGroup(groupId: String): SubscriptionGroup?

    @Query("SELECT * FROM subscription_links ORDER BY groupId ASC, sortOrder ASC")
    fun getAllLinks(): Flow<List<SubscriptionLink>>

    @Query("SELECT * FROM subscription_links WHERE groupId = :groupId ORDER BY sortOrder ASC")
    suspend fun getLinksForGroup(groupId: String): List<SubscriptionLink>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroup(group: SubscriptionGroup)

    @Update
    suspend fun updateGroup(group: SubscriptionGroup)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLinks(links: List<SubscriptionLink>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNodes(nodes: List<Node>)

    @Query("DELETE FROM subscription_links WHERE groupId = :groupId")
    suspend fun deleteLinksForGroup(groupId: String)

    @Query("DELETE FROM subscription_groups WHERE id = :groupId")
    suspend fun deleteGroupRow(groupId: String)

    @Query("DELETE FROM nodes WHERE source = 'SUBSCRIPTION' AND subscriptionGroupId = :groupId")
    suspend fun deleteNodesForGroup(groupId: String)

    @Query("DELETE FROM subscription_links WHERE groupId = :defaultGroupId OR instr(groupId, :builtInPrefix) = 1")
    suspend fun deleteBuiltInLinks(defaultGroupId: String, builtInPrefix: String)

    @Query("DELETE FROM nodes WHERE source = 'SUBSCRIPTION' AND (subscriptionGroupId = :defaultGroupId OR instr(subscriptionGroupId, :builtInPrefix) = 1)")
    suspend fun deleteBuiltInNodes(defaultGroupId: String, builtInPrefix: String)

    @Query("DELETE FROM subscription_groups WHERE id = :defaultGroupId OR instr(id, :builtInPrefix) = 1")
    suspend fun deleteBuiltInGroupRows(defaultGroupId: String, builtInPrefix: String)

    @Query("UPDATE subscription_groups SET lastUpdatedAt = :updatedAt, lastError = :error, lastNodeCount = :nodeCount WHERE id = :groupId")
    suspend fun updateRefreshResult(groupId: String, updatedAt: Long, error: String?, nodeCount: Int)

    @Transaction
    suspend fun saveGroup(group: SubscriptionGroup, links: List<SubscriptionLink>) {
        insertGroup(group)
        deleteLinksForGroup(group.id)
        if (links.isNotEmpty()) insertLinks(links)
    }

    @Transaction
    suspend fun deleteGroup(groupId: String) {
        deleteLinksForGroup(groupId)
        deleteNodesForGroup(groupId)
        deleteGroupRow(groupId)
    }

    @Transaction
    suspend fun deleteBuiltInGroups(defaultGroupId: String, builtInPrefix: String) {
        deleteBuiltInLinks(defaultGroupId, builtInPrefix)
        deleteBuiltInNodes(defaultGroupId, builtInPrefix)
        deleteBuiltInGroupRows(defaultGroupId, builtInPrefix)
    }

    /**
     * 一次事务提交整批内置目录变更，避免逐组写入时 Room Flow 连续发射中间状态，
     * 导致节点 Pager 在请求期间反复重组和改变页码。
     */
    @Transaction
    suspend fun replaceBuiltInCatalog(
        groups: List<SubscriptionGroup>,
        refreshedGroupIds: List<String>,
        nodes: List<Node>,
        staleGroupIds: List<String>
    ) {
        groups.forEach { insertGroup(it) }
        refreshedGroupIds.forEach { deleteNodesForGroup(it) }
        if (nodes.isNotEmpty()) insertNodes(nodes)
        staleGroupIds.forEach { staleGroupId ->
            deleteLinksForGroup(staleGroupId)
            deleteNodesForGroup(staleGroupId)
            deleteGroupRow(staleGroupId)
        }
    }
}
