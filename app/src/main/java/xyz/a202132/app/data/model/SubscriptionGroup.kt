package xyz.a202132.app.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

const val DEFAULT_SUBSCRIPTION_GROUP_ID = "subscription_default"
const val FAVORITES_NODE_GROUP_ID = "favorites"
const val BUILT_IN_SUBSCRIPTION_GROUP_PREFIX = "firefly_catalog:"

fun builtInSubscriptionGroupId(subscriptionId: String): String =
    BUILT_IN_SUBSCRIPTION_GROUP_PREFIX + subscriptionId

fun isBuiltInSubscriptionGroup(groupId: String): Boolean =
    groupId == DEFAULT_SUBSCRIPTION_GROUP_ID ||
        groupId.startsWith(BUILT_IN_SUBSCRIPTION_GROUP_PREFIX)

@Entity(tableName = "subscription_groups")
data class SubscriptionGroup(
    @PrimaryKey
    val id: String,
    val name: String,
    val autoUpdateEnabled: Boolean = false,
    val updateIntervalMinutes: Int = SubscriptionAutoUpdatePolicy.DEFAULT_INTERVAL_MINUTES,
    val deduplicateEnabled: Boolean = true,
    val userAgent: String = "",
    val lastUpdatedAt: Long = 0L,
    val lastError: String? = null,
    val lastNodeCount: Int = 0,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "subscription_links",
    indices = [
        Index(value = ["groupId"]),
        Index(value = ["groupId", "url"], unique = true)
    ]
)
data class SubscriptionLink(
    @PrimaryKey
    val id: String,
    val groupId: String,
    val url: String,
    val sortOrder: Int = 0
)

data class SubscriptionGroupSummary(
    val group: SubscriptionGroup,
    val links: List<SubscriptionLink>
)

data class NodeGroup(
    val id: String,
    val name: String,
    val sortOrder: Int,
    val isFavorites: Boolean = false,
    val isBuiltIn: Boolean = false,
    val hasUnreadNodes: Boolean = false
)
