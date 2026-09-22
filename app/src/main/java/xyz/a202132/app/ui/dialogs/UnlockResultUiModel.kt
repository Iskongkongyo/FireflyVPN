package xyz.a202132.app.ui.dialogs

import xyz.a202132.app.viewmodel.UnlockNodeResult
import xyz.a202132.app.viewmodel.UnlockResultStatus
import java.util.Locale

internal enum class UnlockServiceStatus {
    AVAILABLE,
    UNAVAILABLE,
    UNKNOWN,
    NOT_APPLICABLE
}

internal enum class UnlockServiceCategory {
    ALL,
    STREAMING,
    AI,
    SEARCH,
    SOCIAL,
    OTHER
}

internal enum class UnlockIpVersion {
    IPV4,
    IPV6
}

internal data class UnlockServiceItemUi(
    val id: String,
    val name: String,
    val status: UnlockServiceStatus,
    val region: String? = null,
    val category: UnlockServiceCategory = UnlockServiceCategory.OTHER,
    val detail: String? = null
)

internal data class UnlockResultUiModel(
    val nodeName: String,
    val status: UnlockResultStatus,
    val testedAt: Long,
    val exitCode: Int?,
    val durationMillis: Long?,
    val items: List<UnlockServiceItemUi>,
    val majorItems: List<UnlockServiceItemUi>,
    val mainRegion: String?,
    val projectUrl: String
) {
    val availableCount: Int = items.count { it.status == UnlockServiceStatus.AVAILABLE }
    val unavailableCount: Int = items.count { it.status == UnlockServiceStatus.UNAVAILABLE }
    val otherCount: Int = items.size - availableCount - unavailableCount
}

private val ansiCsiPattern = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
private val ansiWithoutEscapePattern = Regex("\\[[0-9;]{1,20}m")

internal fun sanitizeUnlockOutput(input: String): String = input
    .replace(ansiCsiPattern, "")
    .replace(ansiWithoutEscapePattern, "")

private val regionCodeToAlpha2: Map<String, String> by lazy {
    buildMap {
        Locale.getISOCountries().forEach { alpha2 ->
            put(alpha2, alpha2)
            runCatching { Locale("", alpha2).isO3Country }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { alpha3 -> put(alpha3.uppercase(Locale.ROOT), alpha2) }
        }
    }
}

internal fun regionFlagEmojiOrNull(region: String?): String? {
    val normalized = region?.trim()?.uppercase(Locale.ROOT).orEmpty()
    val alpha2 = when (normalized) {
        "TW", "TWN", "TAIWAN" -> "CN"
        "UK" -> "GB"
        else -> regionCodeToAlpha2[normalized]
    } ?: return null
    if (alpha2.length != 2 || alpha2.any { it !in 'A'..'Z' }) return null

    val first = 0x1F1E6 + (alpha2[0] - 'A')
    val second = 0x1F1E6 + (alpha2[1] - 'A')
    return String(Character.toChars(first)) + String(Character.toChars(second))
}

internal object UnlockResultUiMapper {
    private data class StatusMatch(
        val status: UnlockServiceStatus,
        val range: IntRange
    )

    private val statusPatterns = listOf(
        UnlockServiceStatus.NOT_APPLICABLE to Regex(
            "(?:\\bN/?A\\b|\\bnot\\s+applicable\\b|不适用|未测试)",
            RegexOption.IGNORE_CASE
        ),
        UnlockServiceStatus.UNAVAILABLE to Regex(
            "(?:\\bNO\\b|\\bnot\\s+available\\b|\\bunavailable\\b|\\bblocked\\b|\\bfail(?:ed)?\\b|不可用|失败|受限)",
            RegexOption.IGNORE_CASE
        ),
        UnlockServiceStatus.AVAILABLE to Regex(
            "(?:\\bYES\\b|\\bavailable\\b|\\bunlocked\\b|\\bfull\\s+unlock\\b|可用|已解锁|完整解锁)",
            RegexOption.IGNORE_CASE
        ),
        UnlockServiceStatus.UNKNOWN to Regex(
            "(?:\\bunknown\\b|\\berror\\b|\\btimeout\\b|未知|异常|超时)",
            RegexOption.IGNORE_CASE
        )
    )

    private val regionPattern = Regex(
        "(?:region|地区|区域)\\s*[:：=\\-]?\\s*[\\[(]?\\s*([A-Z]{2,8})\\b",
        RegexOption.IGNORE_CASE
    )
    private val standaloneRegionPattern = Regex(
        "^(?:region|地区|区域)\\s*[:：=\\-]?\\s*[\\[(]?\\s*([A-Z]{2,8})\\s*[])]?\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val regionAnnotationPattern = Regex(
        "[\\[(]?\\s*(?:region|地区|区域)\\s*[:：=\\-]?\\s*[A-Z]{2,8}\\s*[])]?",
        RegexOption.IGNORE_CASE
    )
    private val trailingRegionPattern = Regex(
        "(?:\\bYES\\b|\\bNO\\b|可用|不可用)\\s*[-|/]\\s*([A-Z]{2,8})\\b",
        RegexOption.IGNORE_CASE
    )
    private val youtubeCdnPattern = Regex(
        "^YouTube\\s+CDN\\s*[:：=\\-]?\\s*(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val ipv4HeaderPattern = Regex("^\\s*IPv4\\s*:?\\s*$", RegexOption.IGNORE_CASE)
    private val ipv6HeaderPattern = Regex("^\\s*IPv6\\s*:?\\s*$", RegexOption.IGNORE_CASE)
    private val projectPattern = Regex(
        "https?://github\\.com/oneclickvirt/UnlockTests[^\\s\\[]*",
        RegexOption.IGNORE_CASE
    )

    fun map(
        result: UnlockNodeResult,
        ipVersion: UnlockIpVersion = UnlockIpVersion.IPV4
    ): UnlockResultUiModel {
        val source = sanitizeUnlockOutput(result.fullOutput.ifBlank { result.rawOutput })
        val rawOutput = sanitizeUnlockOutput(result.rawOutput)
        val sourceItems = parseItems(ipVersionSource(source, ipVersion))
        val parsedItems = if (sourceItems.isNotEmpty() || hasIpSections(source)) {
            sourceItems
        } else {
            parseItems(ipVersionSource(rawOutput, ipVersion))
        }
        val mainRegion = parsedItems
            .asSequence()
            .filter { it.status == UnlockServiceStatus.AVAILABLE }
            .mapNotNull { it.region }
            .groupingBy { it.uppercase() }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            ?: parsedItems.mapNotNull { it.region }.firstOrNull()?.uppercase()

        return UnlockResultUiModel(
            nodeName = result.nodeName,
            status = result.status,
            testedAt = result.testedAt,
            exitCode = result.exitCode ?: parseExitCode(result.rawOutput),
            durationMillis = result.durationMillis,
            items = parsedItems,
            majorItems = majorItems(parsedItems),
            mainRegion = mainRegion,
            projectUrl = projectPattern.find(source)?.value
                ?: projectPattern.find(rawOutput)?.value
                ?: "https://github.com/oneclickvirt/UnlockTests"
        )
    }

    private fun parseItems(source: String): List<UnlockServiceItemUi> {
        if (source.isBlank()) return emptyList()
        val items = mutableListOf<UnlockServiceItemUi>()

        source.lineSequence()
            .map(::cleanLine)
            .filter { it.isNotBlank() }
            .forEach { line ->
                if (isMetadataOrDecoration(line)) return@forEach

                if (
                    items.isNotEmpty() &&
                    items.last().status == UnlockServiceStatus.UNKNOWN &&
                    isUnknownDetailContinuation(line)
                ) {
                    val previous = items.last()
                    items[items.lastIndex] = previous.copy(
                        detail = appendDetail(previous.detail, line)
                    )
                    return@forEach
                }

                val statusMatch = findStatus(line)
                if (statusMatch == null) {
                    val region = extractStandaloneRegion(line)
                    if (region != null && items.isNotEmpty() && items.last().region == null) {
                        items[items.lastIndex] = items.last().copy(region = region)
                    } else if (youtubeCdnPattern.matches(line)) {
                        val match = youtubeCdnPattern.matchEntire(line)
                        val cdn = match?.groupValues?.getOrNull(1)?.trim().orEmpty()
                        val youtubeIndex = items.indexOfLast {
                            it.name.contains("youtube", ignoreCase = true)
                        }
                        if (youtubeIndex >= 0 && cdn.isNotBlank()) {
                            val youtube = items[youtubeIndex]
                            items[youtubeIndex] = youtube.copy(
                                detail = appendDetail(youtube.detail, "CDN：$cdn")
                            )
                        }
                    } else if (
                        items.isNotEmpty() &&
                        items.last().status == UnlockServiceStatus.UNKNOWN &&
                        isUnknownDetailContinuation(line)
                    ) {
                        val previous = items.last()
                        items[items.lastIndex] = previous.copy(
                            detail = appendDetail(previous.detail, line)
                        )
                    }
                    return@forEach
                }

                val beforeStatus = line.substring(0, statusMatch.range.first)
                val afterStatus = line.substring(statusMatch.range.last + 1)
                val (rawName, prefixDetail) = splitNameAndDetail(beforeStatus)
                val name = sanitizeName(rawName)
                if (!isServiceName(name)) {
                    return@forEach
                }

                val region = extractRegion(line)
                val suffixDetail = sanitizeDetail(afterStatus, region)
                val detail = listOfNotNull(prefixDetail, suffixDetail)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                    .ifBlank { null }

                items += UnlockServiceItemUi(
                    id = "${normalizeName(name)}-${items.size}",
                    name = name,
                    status = statusMatch.status,
                    region = region,
                    category = categoryFor(name),
                    detail = detail
                )
            }

        return items
            .groupBy { normalizeName(it.name) }
            .values
            .map { duplicates ->
                duplicates.maxWithOrNull(
                    compareBy<UnlockServiceItemUi> { statusPriority(it.status) }
                        .thenBy { if (it.region != null) 1 else 0 }
                        .thenBy { it.detail?.length ?: 0 }
                ) ?: duplicates.first()
            }
            .mapIndexed { index, item -> item.copy(id = "${normalizeName(item.name)}-$index") }
    }

    private fun findStatus(line: String): StatusMatch? = statusPatterns
        .mapNotNull { (status, pattern) ->
            pattern.find(line)?.let { StatusMatch(status, it.range) }
        }
        .minWithOrNull(compareBy<StatusMatch> { it.range.first }.thenBy { statusPriority(it.status) })

    private fun extractRegion(line: String): String? {
        val explicitRegion = regionPattern.findAll(line)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .firstOrNull(::isValidRegionCode)
        val code = explicitRegion
            ?: trailingRegionPattern.find(line)?.groupValues?.getOrNull(1)
        return code?.uppercase()?.takeIf(::isValidRegionCode)
    }

    private fun extractStandaloneRegion(line: String): String? = standaloneRegionPattern
        .matchEntire(line)
        ?.groupValues
        ?.getOrNull(1)
        ?.uppercase()
        ?.takeIf(::isValidRegionCode)

    private fun isValidRegionCode(code: String): Boolean {
        val normalized = code.uppercase()
        return normalized.matches(Regex("[A-Z]{2,8}")) &&
            normalized !in setOf("YES", "NO", "NA", "UNKNOWN", "ERROR")
    }

    private fun splitNameAndDetail(prefix: String): Pair<String, String?> {
        val cleaned = prefix.trim().trimEnd(':', '：', '-', '—', '|')
        val parts = cleaned.split(Regex("\\s+[—-]\\s+"), limit = 2)
        return if (parts.size == 2 && parts[0].length >= 2 && parts[1].length >= 4) {
            parts[0] to parts[1]
        } else {
            cleaned to null
        }
    }

    private fun sanitizeName(value: String): String = value
        .replace(Regex("^\\[(?:IPv[46]|TCP|UDP)]\\s*", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^[\\s│┃┆┊├└┌┬┼─━•*+✅❌❓✔✘]+"), "")
        .replace(Regex("^\\d+[.)、]\\s*"), "")
        .trim()
        .trim(':', '：', '-', '—', '|')
        .trim()
        .take(100)

    private fun sanitizeDetail(value: String, region: String?): String? {
        var detail = value
            .replace(regionAnnotationPattern, "")
            .replace(Regex("\\(\\s*\\)|\\[\\s*]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim(':', '：', '-', '—', '|', '(', ')', '[', ']')
            .trim()
        if (region != null && detail.equals(region, ignoreCase = true)) detail = ""
        return detail.takeIf { it.length >= 2 }?.take(500)
    }

    private fun isServiceName(name: String): Boolean {
        if (name.length !in 2..100) return false
        val lower = name.lowercase()
        return !lower.startsWith("http") &&
            !lower.startsWith("get ") &&
            !lower.startsWith("post ") &&
            !lower.startsWith("测试") &&
            !lower.startsWith("摘要") &&
            !lower.startsWith("退出码") &&
            !lower.startsWith("test time") &&
            !lower.startsWith("total") &&
            !lower.contains("github.com") &&
            !name.matches(Regex("(?:YES|NO|可用|不可用)[\\s:=/0-9.-]*", RegexOption.IGNORE_CASE))
    }

    private fun isMetadataOrDecoration(line: String): Boolean {
        val lower = line.lowercase()
        return line.all { it in "-=─━│┃┆┊┌┐└┘├┤┬┴┼+ " } ||
            line.matches(Regex("^[=─━-]+\\s*[\\[【].*[]】]\\s*[=─━-]+$")) ||
            lower.startsWith("测试时间") ||
            lower.startsWith("节点:") ||
            lower.startsWith("节点：") ||
            lower.startsWith("退出码") ||
            lower.startsWith("摘要") ||
            lower.startsWith("测试结果") ||
            lower.contains("github.com/oneclickvirt/unlocktests")
    }

    private fun isUnknownDetailContinuation(line: String): Boolean {
        val lower = line.lowercase()
        return line.length in 3..240 &&
            (lower.startsWith("get ") ||
                lower.startsWith("post ") ||
                lower.startsWith("http") ||
                lower.startsWith("error:") ||
                lower.startsWith("timeout"))
    }

    private fun appendDetail(existing: String?, next: String): String =
        listOfNotNull(existing, next.takeIf { it.isNotBlank() })
            .joinToString("\n")
            .take(500)

    private fun ipVersionSource(source: String, ipVersion: UnlockIpVersion): String {
        val lines = source.lines()
        val ipv4Start = lines.indexOfFirst { ipv4HeaderPattern.matches(cleanLine(it)) }
        val ipv6Start = lines.indexOfFirst { ipv6HeaderPattern.matches(cleanLine(it)) }
        if (ipv4Start < 0 && ipv6Start < 0) return source

        val sectionStart = when (ipVersion) {
            UnlockIpVersion.IPV4 -> ipv4Start
            UnlockIpVersion.IPV6 -> ipv6Start
        }
        if (sectionStart < 0) return ""

        val nextSectionStart = lines.withIndex()
            .firstOrNull { (index, line) ->
                index > sectionStart &&
                    (ipv4HeaderPattern.matches(cleanLine(line)) ||
                        ipv6HeaderPattern.matches(cleanLine(line)))
            }
            ?.index
            ?: lines.size
        return lines.subList(sectionStart + 1, nextSectionStart).joinToString("\n")
    }

    private fun hasIpSections(source: String): Boolean = source.lineSequence().any { line ->
        val cleaned = cleanLine(line)
        ipv4HeaderPattern.matches(cleaned) || ipv6HeaderPattern.matches(cleaned)
    }

    private fun cleanLine(input: String): String = sanitizeUnlockOutput(input)
        .replace(Regex("^\\s*[-*•]+\\s*"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun normalizeName(name: String): String = name
        .lowercase()
        .replace(Regex("[^a-z0-9\\p{L}]+"), "")

    private fun statusPriority(status: UnlockServiceStatus): Int = when (status) {
        UnlockServiceStatus.AVAILABLE -> 4
        UnlockServiceStatus.UNAVAILABLE -> 3
        UnlockServiceStatus.UNKNOWN -> 2
        UnlockServiceStatus.NOT_APPLICABLE -> 1
    }

    private fun categoryFor(name: String): UnlockServiceCategory {
        val lower = name.lowercase()
        return when {
            listOf(
                "netflix", "youtube", "disney", "dazn", "paramount", "prime video",
                "amazon prime", "hbo", "spotify", "tvb", "viu", "kocowa", "abema",
                "bilibili", "iqiyi", "steam", "apple tv", "hulu", "peacock", "sonyliv"
            ).any(lower::contains) -> UnlockServiceCategory.STREAMING
            listOf("chatgpt", "openai", "claude", "gemini", "meta ai", "metaai", "sora", "copilot")
                .any(lower::contains) -> UnlockServiceCategory.AI
            listOf("google search", "googlesearch", "bing search", "bingsearch", "search engine")
                .any(lower::contains) -> UnlockServiceCategory.SEARCH
            listOf("instagram", "reddit", "tiktok", "facebook", "twitter", "telegram", "discord")
                .any(lower::contains) -> UnlockServiceCategory.SOCIAL
            else -> UnlockServiceCategory.OTHER
        }
    }

    private fun majorItems(items: List<UnlockServiceItemUi>): List<UnlockServiceItemUi> {
        data class Major(val name: String, val aliases: List<String>, val exclude: List<String> = emptyList())
        val majors = listOf(
            Major("Netflix", listOf("netflix"), listOf("cdn")),
            Major("YouTube", listOf("youtube"), listOf("cdn")),
            Major("Disney+", listOf("disney")),
            Major("ChatGPT", listOf("chatgpt", "openai")),
            Major("Claude", listOf("claude")),
            Major("Gemini", listOf("gemini"))
        )
        return majors.mapIndexed { index, major ->
            val match = items
                .filter { item -> major.aliases.any { item.name.contains(it, ignoreCase = true) } }
                .sortedBy { item -> if (major.exclude.any { item.name.contains(it, ignoreCase = true) }) 1 else 0 }
                .firstOrNull()
            match?.copy(id = "major-$index", name = major.name)
                ?: UnlockServiceItemUi(
                    id = "major-$index",
                    name = major.name,
                    status = UnlockServiceStatus.UNKNOWN,
                    category = categoryFor(major.name)
                )
        }
    }

    private fun parseExitCode(content: String): Int? = Regex("退出码\\s*[:：]\\s*(-?\\d+)")
        .find(content)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
}
