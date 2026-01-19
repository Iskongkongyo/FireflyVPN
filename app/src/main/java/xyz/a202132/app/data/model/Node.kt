package xyz.a202132.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters

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
    val sortOrder: Int = 0                // 排序顺序
) {
    /**
     * 获取国旗emoji
     */
    fun getFlagEmoji(): String {
        // 1. Try to find existing flag emoji in the name (Regional Indicator Symbol Pair)
        var i = 0
        while (i < name.length) {
            val codePoint = name.codePointAt(i)
            // Check if current code point is a Regional Indicator Symbol (U+1F1E6 to U+1F1FF)
            if (codePoint in 0x1F1E6..0x1F1FF) {
                // Check next code point
                val charCount = Character.charCount(codePoint)
                if (i + charCount < name.length) {
                    val nextCodePoint = name.codePointAt(i + charCount)
                    if (nextCodePoint in 0x1F1E6..0x1F1FF) {
                        // Found a pair!
                        return String(Character.toChars(codePoint)) + String(Character.toChars(nextCodePoint))
                    }
                }
            }
            i += Character.charCount(codePoint)
        }

        // 2. Fallback to generating from country code
        if (country.isNullOrEmpty() || country.length != 2) {
            return "🌐"
        }
        return try {
            val firstChar = Character.codePointAt(country.uppercase(), 0) - 0x41 + 0x1F1E6
            val secondChar = Character.codePointAt(country.uppercase(), 1) - 0x41 + 0x1F1E6
            String(Character.toChars(firstChar)) + String(Character.toChars(secondChar))
        } catch (e: Exception) {
            "🌐"
        }
    }
    
    /**
     * 获取延迟显示文本
     */
    fun getLatencyText(): String {
        return when {
            latency == -1 -> "测试中"
            latency == -2 -> "超时"
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

/**
 * Room TypeConverter for NodeType
 */
class NodeTypeConverter {
    @TypeConverter
    fun fromNodeType(type: NodeType): String = type.protocol
    
    @TypeConverter
    fun toNodeType(protocol: String): NodeType = 
        NodeType.entries.find { it.protocol == protocol } ?: NodeType.UNKNOWN
}
