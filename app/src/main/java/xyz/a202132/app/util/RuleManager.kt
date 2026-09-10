package xyz.a202132.app.util

import android.content.Context
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.a202132.app.AppConfig
import xyz.a202132.app.rules.data.RuleRegistry
import xyz.a202132.app.rules.model.ManagedRule
import xyz.a202132.app.rules.model.RuleCategory
import xyz.a202132.app.rules.model.RuleUpdateStage
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** 管理 APK 内置及运行时更新的 sing-box SRS 规则文件。 */
object RuleManager {
    private const val TAG = "RuleManager"
    private const val RULE_SET_DIRECTORY = "rule-sets"
    private const val LEGACY_DIRECTORY = "sing-box"
    private const val MIN_RULE_BYTES = 8L

    const val GEOIP_CN_FILE = "geoip-cn.srs"
    const val GEOSITE_CN_FILE = "geosite-cn.srs"
    const val ANTI_AD_FILE = "anti-ad.srs"
    const val AD_RULES_FILE = "adrules.srs"

    /** sing-box 工作目录是 filesDir/sing-box，因此规则使用相邻目录的相对路径。 */
    fun configPath(fileName: String): String = "../$RULE_SET_DIRECTORY/$fileName"

    fun ruleSetDirectory(context: Context): File = File(context.filesDir, RULE_SET_DIRECTORY)

    fun ruleFile(context: Context, rule: ManagedRule): File? =
        rule.fileName?.let { File(ruleSetDirectory(context), it) }

    /** 确保离线规则始终可用；旧版本文件会优先迁移，再回退到 APK assets。 */
    suspend fun ensureRuleSets(context: Context) = withContext(Dispatchers.IO) {
        val directory = ruleSetDirectory(context)
        check(directory.exists() || directory.mkdirs()) { "Unable to create rule-set directory" }

        RuleRegistry.fileRules.forEach { rule ->
            val target = requireNotNull(ruleFile(context, rule))
            if (isValidRuleFile(target)) return@forEach

            val legacy = File(File(context.filesDir, LEGACY_DIRECTORY), requireNotNull(rule.fileName))
            val restored = if (isValidRuleFile(legacy)) {
                copyFileAtomically(legacy, target)
            } else {
                copyAssetAtomically(context, requireNotNull(rule.fileName), target)
            }
            if (!restored || !isValidRuleFile(target)) {
                throw IllegalStateException("Missing required rule set: ${rule.fileName}")
            }
        }
    }

    /** 保留原有启动更新行为，但只更新两项系统规则，避免启动时下载大型广告规则。 */
    suspend fun updateRuleSets(context: Context): Boolean = withContext(Dispatchers.IO) {
        RuleRegistry.rules
            .filter { it.category == RuleCategory.SYSTEM && it.updateable }
            .map { updateRuleInternal(context, it) {}.isSuccess }
            .all { it }
    }

    suspend fun updateRule(
        context: Context,
        ruleId: String,
        onStage: (RuleUpdateStage) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        val rule = RuleRegistry.find(ruleId)
            ?: return@withContext Result.failure(IllegalArgumentException("Unknown rule: $ruleId"))
        updateRuleInternal(context, rule, onStage)
    }

    suspend fun restoreBuiltIn(
        context: Context,
        ruleId: String,
        onStage: (RuleUpdateStage) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        val rule = RuleRegistry.find(ruleId)
            ?: return@withContext Result.failure(IllegalArgumentException("Unknown rule: $ruleId"))
        val fileName = rule.fileName
            ?: return@withContext Result.failure(IllegalArgumentException("Rule has no built-in file"))
        val target = requireNotNull(ruleFile(context, rule))
        onStage(RuleUpdateStage.RESTORING)
        runCatching {
            val directory = ruleSetDirectory(context)
            check(directory.exists() || directory.mkdirs())
            check(copyAssetAtomically(context, fileName, target))
            check(isValidRuleFile(target))
            target
        }.onFailure { Log.e(TAG, "Failed to restore $fileName", it) }
    }

    fun isValidRuleFile(file: File): Boolean {
        if (!file.isFile || file.length() < MIN_RULE_BYTES) return false
        return runCatching {
            val header = ByteArray(4)
            FileInputStream(file).use { input -> input.read(header) == header.size } &&
                header[0] == 'S'.code.toByte() &&
                header[1] == 'R'.code.toByte() &&
                header[2] == 'S'.code.toByte() &&
                header[3].toInt() > 0
        }.getOrDefault(false)
    }

    private fun updateRuleInternal(
        context: Context,
        rule: ManagedRule,
        onStage: (RuleUpdateStage) -> Unit
    ): Result<File> = runCatching {
        val fileName = requireNotNull(rule.fileName) { "Rule is not file based" }
        val remoteUrl = requireNotNull(rule.remoteUrl) { "Rule is not updateable" }
        require(rule.updateable) { "Rule is not updateable" }
        val directory = ruleSetDirectory(context)
        check(directory.exists() || directory.mkdirs())
        val target = File(directory, fileName)
        val temporary = File(directory, "$fileName.${UUID.randomUUID()}.tmp")

        onStage(RuleUpdateStage.CHECKING)
        try {
            onStage(RuleUpdateStage.DOWNLOADING)
            download(remoteUrl, temporary)
            onStage(RuleUpdateStage.VERIFYING)
            check(isValidRuleFile(temporary)) { "Invalid SRS rule file" }
            replaceAtomically(temporary, target)
            check(isValidRuleFile(target)) { "Replaced SRS rule file is invalid" }
            Log.i(TAG, "Updated $fileName (${target.length()} bytes)")
            target
        } finally {
            if (temporary.exists() && !temporary.delete()) {
                Log.w(TAG, "Failed to remove temporary rule file: ${temporary.name}")
            }
        }
    }.onFailure { Log.e(TAG, "Failed to update ${rule.fileName}; current file is unchanged", it) }

    private fun download(url: String, target: File) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 45_000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", AppConfig.HTTP_USER_AGENT)
        }
        try {
            connection.connect()
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "HTTP ${connection.responseCode}"
            }
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun copyAssetAtomically(context: Context, fileName: String, target: File): Boolean =
        runCatching {
            val temporary = File(target.parentFile, "$fileName.${UUID.randomUUID()}.tmp")
            try {
                context.assets.open("rule-sets/$fileName").use { input ->
                    FileOutputStream(temporary).use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                        output.fd.sync()
                    }
                }
                check(isValidRuleFile(temporary))
                replaceAtomically(temporary, target)
            } finally {
                if (temporary.exists()) temporary.delete()
            }
            true
        }.onFailure { Log.e(TAG, "Failed to restore asset $fileName", it) }
            .getOrDefault(false)

    private fun copyFileAtomically(source: File, target: File): Boolean = runCatching {
        val temporary = File(target.parentFile, "${target.name}.${UUID.randomUUID()}.tmp")
        try {
            source.inputStream().use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                    output.fd.sync()
                }
            }
            check(isValidRuleFile(temporary))
            replaceAtomically(temporary, target)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
        true
    }.onFailure { Log.e(TAG, "Failed to migrate ${source.name}", it) }.getOrDefault(false)

    /** Linux/Android rename(2) 会在同一文件系统内原子替换目标文件。 */
    private fun replaceAtomically(source: File, target: File) {
        check(source.parentFile?.canonicalPath == target.parentFile?.canonicalPath)
        Os.rename(source.absolutePath, target.absolutePath)
    }
}
