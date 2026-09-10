package xyz.a202132.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import xyz.a202132.app.util.LegacyRawLinkMigrationCrypto

/**
 * 代理节点数据模型
 */
@Entity(tableName = "nodes")
@TypeConverters(NodeTypeConverter::class)
data class Node(
    @PrimaryKey
    val id: String,                       // 唯一标识 (MD5 of rawLink)
    val name: String,                     // 节点名称
    val type: NodeType,                   // 协议类型
    val server: String,                   // 服务器地址
    val port: Int,                        // 端口
    val rawLink: String,                  // 原始链接
    val country: String? = null,          // 国家代码 (如 "JP", "US")
    val countryName: String? = null,      // 国家名称 (如 "日本", "美国")
    val latency: Int = -1,                // 延迟(ms), -1表示未测试
    val isAvailable: Boolean = true,      // 是否可用
    val lastTestedAt: Long = 0,           // 上次测试时间戳
    val sortOrder: Int = 0,               // 排序顺序
    val downloadMbps: Float = 0f,         // 下载带宽(Mbps)
    val uploadMbps: Float = 0f,           // 上传带宽(Mbps)
    val unlockSummary: String = "",       // 流媒体解锁摘要
    val unlockPassed: Boolean = false,    // 是否通过解锁阈值
    val autoTestStatus: String = "",      // 自动化测试状态
    val autoTestedAt: Long = 0,           // 自动化测试时间戳
    // Node source metadata.
    val source: NodeSource = NodeSource.SUBSCRIPTION,
    val subscriptionGroupId: String? = null,
    val favoriteSourceNodeId: String? = null,
    val favoriteCreatedAt: Long = 0L
) {
    /**
     * 读取原始链接（自动兼容明文/密文存储）
     */
    fun getRawLinkPlain(): String = LegacyRawLinkMigrationCrypto.decryptOrNull(rawLink) ?: rawLink

    /**
     * 是否为加密存储格式
     */
    fun isRawLinkEncrypted(): Boolean = LegacyRawLinkMigrationCrypto.isLegacyValue(rawLink)

    /**
     * 获取国旗emoji
     */
    fun getFlagEmojiOrNull(): String? {
        // 1. 尝试在名称中查找现有的旗帜表情符号
        var i = 0
        while (i < name.length) {
            val codePoint = name.codePointAt(i)
            // 检查当前代码点是否为区域指示符（U+1F1E6 至 U+1F1FF）
            if (codePoint in 0x1F1E6..0x1F1FF) {
                // 检查下一个代码点
                val charCount = Character.charCount(codePoint)
                if (i + charCount < name.length) {
                    val nextCodePoint = name.codePointAt(i + charCount)
                    if (nextCodePoint in 0x1F1E6..0x1F1FF) {
                        return String(Character.toChars(codePoint)) + String(Character.toChars(nextCodePoint))
                    }
                }
            }
            i += Character.charCount(codePoint)
        }

        // 2. 回退至根据国家代码生成
        if (country.isNullOrEmpty() || country.length != 2) {
            return null
        }
        return try {
            val firstChar = Character.codePointAt(country.uppercase(), 0) - 0x41 + 0x1F1E6
            val secondChar = Character.codePointAt(country.uppercase(), 1) - 0x41 + 0x1F1E6
            String(Character.toChars(firstChar)) + String(Character.toChars(secondChar))
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 获取显示名称（去除国旗emoji）
     */
    fun getDisplayName(): String {
        val result = StringBuilder()
        var i = 0
        while (i < name.length) {
            val codePoint = name.codePointAt(i)
            val charCount = Character.charCount(codePoint)
            
            // 检查当前代码点是否为区域指示符（U+1F1E6 至 U+1F1FF）
            if (codePoint in 0x1F1E6..0x1F1FF) {
                // 跳过当前字符，如果下一个字符也是，则一并跳过。
                if (i + charCount < name.length) {
                    val nextCodePoint = name.codePointAt(i + charCount)
                    if (nextCodePoint in 0x1F1E6..0x1F1FF) {
                        i += charCount + Character.charCount(nextCodePoint)
                        continue
                    }
                }
                i += charCount
                continue
            }

            result.append(String(Character.toChars(codePoint)))
            i += charCount
        }
        return result.toString().trim()
    }
    
    /**
     * 获取延迟显示文本
     */
    fun getLatencyText(): String {
        return when {
            latency == -1 -> "未测试"
            latency == -2 -> "超时"
            !isAvailable && autoTestStatus.contains("FILTERED", ignoreCase = true) -> "不达标"
            !isAvailable -> "不可用"
            else -> "${latency}ms"
        }
    }
    
    /**
     * 获取延迟等级 (用于颜色显示)
     */
    fun getLatencyLevel(): LatencyLevel {
        return when {
            latency < 0 || !isAvailable -> LatencyLevel.BAD
            latency < 100 -> LatencyLevel.GOOD
            latency < 300 -> LatencyLevel.MEDIUM
            else -> LatencyLevel.BAD
        }
    }
}

enum class LatencyLevel {
    GOOD, MEDIUM, BAD
}

enum class NodeSource {
    SUBSCRIPTION,
    FAVORITE
}

@Deprecated("Use subscription group ids and FAVORITES_NODE_GROUP_ID")
enum class NodeListCategory {
    PRIMARY,
    FAVORITES
}

/**
 * 节点类型转换器
 */
class NodeTypeConverter {
    @TypeConverter
    fun fromNodeType(type: NodeType): String = type.protocol
    
    @TypeConverter
    fun toNodeType(protocol: String): NodeType = 
        NodeType.entries.find { it.protocol == protocol } ?: NodeType.UNKNOWN

    @TypeConverter
    fun fromNodeSource(source: NodeSource): String = source.name

    @TypeConverter
    fun toNodeSource(source: String): NodeSource =
        runCatching { NodeSource.valueOf(source) }.getOrDefault(NodeSource.SUBSCRIPTION)
}
