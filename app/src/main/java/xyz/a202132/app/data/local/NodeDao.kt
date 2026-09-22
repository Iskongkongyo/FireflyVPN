package xyz.a202132.app.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import xyz.a202132.app.data.model.Node

@Dao
interface NodeDao {
    @Query("SELECT * FROM nodes WHERE isAvailable = 1 ORDER BY CASE WHEN latency < 0 THEN 1 ELSE 0 END, latency ASC")
    fun getAllAvailableNodes(): Flow<List<Node>>
    
    @Query("SELECT * FROM nodes ORDER BY sortOrder ASC, favoriteCreatedAt ASC, id ASC")
    fun getAllNodes(): Flow<List<Node>>

    @Query("SELECT * FROM nodes WHERE source = 'SUBSCRIPTION' ORDER BY sortOrder ASC, id ASC")
    fun getSubscriptionNodes(): Flow<List<Node>>

    @Query("SELECT * FROM nodes WHERE source = 'SUBSCRIPTION' AND subscriptionGroupId = :groupId ORDER BY sortOrder ASC, id ASC")
    fun getSubscriptionNodes(groupId: String): Flow<List<Node>>

    @Query("SELECT * FROM nodes WHERE source = 'FAVORITE' ORDER BY sortOrder ASC, favoriteCreatedAt ASC, id ASC")
    fun getFavoriteNodes(): Flow<List<Node>>

    @Query("SELECT favoriteSourceNodeId FROM nodes WHERE source = 'FAVORITE' AND favoriteSourceNodeId IS NOT NULL")
    fun getFavoriteSourceNodeIds(): Flow<List<String?>>
    
    @Query("SELECT * FROM nodes WHERE isAvailable = 1 ORDER BY CASE WHEN latency < 0 THEN 1 ELSE 0 END, latency ASC LIMIT 1")
    suspend fun getBestNode(): Node?
    
    @Query("SELECT * FROM nodes WHERE id = :id")
    suspend fun getNodeById(id: String): Node?

    @Query("SELECT * FROM nodes WHERE source = 'FAVORITE' AND favoriteSourceNodeId = :sourceNodeId LIMIT 1")
    suspend fun getFavoriteBySourceNodeId(sourceNodeId: String): Node?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNode(node: Node)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNodes(nodes: List<Node>)
    
    @Update
    suspend fun updateNode(node: Node)

    @Query("UPDATE nodes SET sortOrder = :sortOrder WHERE id = :nodeId")
    suspend fun updateSortOrder(nodeId: String, sortOrder: Int)

    @Transaction
    suspend fun updateSortOrders(nodes: List<Node>) {
        nodes.forEach { node -> updateSortOrder(node.id, node.sortOrder) }
    }
    
    @Delete
    suspend fun deleteNode(node: Node)
    
    @Query("DELETE FROM nodes")
    suspend fun deleteAllNodes()

    @Query("DELETE FROM nodes WHERE source = 'SUBSCRIPTION'")
    suspend fun deleteSubscriptionNodes()

    @Query("DELETE FROM nodes WHERE source = 'SUBSCRIPTION' AND subscriptionGroupId = :groupId")
    suspend fun deleteSubscriptionNodes(groupId: String)

    @Query("DELETE FROM nodes WHERE id = :nodeId")
    suspend fun deleteNodeById(nodeId: String)

    @Transaction
    suspend fun replaceAllNodes(nodes: List<Node>) {
        deleteAllNodes()
        insertNodes(nodes)
    }

    @Transaction
    suspend fun replaceSubscriptionNodes(nodes: List<Node>) {
        deleteSubscriptionNodes()
        insertNodes(nodes)
    }

    @Transaction
    suspend fun replaceSubscriptionNodes(groupId: String, nodes: List<Node>) {
        deleteSubscriptionNodes(groupId)
        insertNodes(nodes)
    }
    
    @Query("UPDATE nodes SET latency = :latency, isAvailable = :isAvailable, lastTestedAt = :testedAt WHERE id = :nodeId")
    suspend fun updateLatency(nodeId: String, latency: Int, isAvailable: Boolean, testedAt: Long): Int

    @Query(
        """
        UPDATE nodes SET
            downloadMbps = :downloadMbps,
            uploadMbps = :uploadMbps,
            downloadTestStatus = :downloadStatus,
            uploadTestStatus = :uploadStatus,
            downloadTestMessage = :downloadMessage,
            uploadTestMessage = :uploadMessage,
            autoTestedAt = :testedAt
        WHERE id = :nodeId
        """
    )
    suspend fun updateBandwidth(
        nodeId: String,
        downloadMbps: Float,
        uploadMbps: Float,
        downloadStatus: xyz.a202132.app.data.model.BandwidthTestStatus,
        uploadStatus: xyz.a202132.app.data.model.BandwidthTestStatus,
        downloadMessage: String?,
        uploadMessage: String?,
        testedAt: Long
    )

    @Query("UPDATE nodes SET unlockSummary = :summary, unlockPassed = :passed, autoTestedAt = :testedAt WHERE id = :nodeId")
    suspend fun updateUnlock(nodeId: String, summary: String, passed: Boolean, testedAt: Long)

    @Query("UPDATE nodes SET isAvailable = :available, autoTestStatus = :status, autoTestedAt = :testedAt WHERE id = :nodeId")
    suspend fun updateAutoTestStatus(nodeId: String, available: Boolean, status: String, testedAt: Long)
    
    @Query("SELECT COUNT(*) FROM nodes")
    suspend fun getNodeCount(): Int
}
