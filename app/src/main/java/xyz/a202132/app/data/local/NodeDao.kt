package xyz.a202132.app.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import xyz.a202132.app.data.model.Node

@Dao
interface NodeDao {
    @Query("SELECT * FROM nodes WHERE isAvailable = 1 ORDER BY latency ASC")
    fun getAllAvailableNodes(): Flow<List<Node>>
    
    @Query("SELECT * FROM nodes ORDER BY sortOrder ASC, latency ASC")
    fun getAllNodes(): Flow<List<Node>>
    
    @Query("SELECT * FROM nodes WHERE isAvailable = 1 ORDER BY latency ASC LIMIT 1")
    suspend fun getBestNode(): Node?
    
    @Query("SELECT * FROM nodes WHERE id = :id")
    suspend fun getNodeById(id: String): Node?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNode(node: Node)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNodes(nodes: List<Node>)
    
    @Update
    suspend fun updateNode(node: Node)
    
    @Delete
    suspend fun deleteNode(node: Node)
    
    @Query("DELETE FROM nodes")
    suspend fun deleteAllNodes()
    
    @Query("UPDATE nodes SET latency = :latency, isAvailable = :isAvailable, lastTestedAt = :testedAt WHERE id = :nodeId")
    suspend fun updateLatency(nodeId: String, latency: Int, isAvailable: Boolean, testedAt: Long)

    @Query("UPDATE nodes SET downloadMbps = :downloadMbps, uploadMbps = :uploadMbps, autoTestedAt = :testedAt WHERE id = :nodeId")
    suspend fun updateBandwidth(nodeId: String, downloadMbps: Float, uploadMbps: Float, testedAt: Long)

    @Query("UPDATE nodes SET unlockSummary = :summary, unlockPassed = :passed, autoTestedAt = :testedAt WHERE id = :nodeId")
    suspend fun updateUnlock(nodeId: String, summary: String, passed: Boolean, testedAt: Long)

    @Query("UPDATE nodes SET isAvailable = :available, autoTestStatus = :status, autoTestedAt = :testedAt WHERE id = :nodeId")
    suspend fun updateAutoTestStatus(nodeId: String, available: Boolean, status: String, testedAt: Long)
    
    @Query("SELECT COUNT(*) FROM nodes")
    suspend fun getNodeCount(): Int
}
