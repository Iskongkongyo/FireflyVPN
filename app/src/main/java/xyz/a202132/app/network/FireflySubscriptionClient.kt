package xyz.a202132.app.network

import android.content.Context
import android.os.Build
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import xyz.a202132.app.AppConfig
import xyz.a202132.app.util.crypto.CryptoV2Protocol
import xyz.a202132.app.util.crypto.DeviceIdentityManager
import xyz.a202132.app.util.crypto.DeviceKeyManager
import xyz.a202132.app.util.crypto.SecureTokenStorage
import xyz.a202132.app.util.crypto.SubscriptionCryptoV2
import java.io.IOException
import java.util.concurrent.TimeUnit

data class FireflyCatalogSubscription(
    val sourceId: String,
    val name: String,
    val content: String? = null,
    val error: Throwable? = null
)

internal data class FireflyCatalogDescriptor(
    val sourceId: String,
    val name: String,
    val contentUrl: String,
    val cryptoVersion: Int,
    val algorithm: String
)

/** Carries the HTTP status so credential recovery can distinguish an expired token. */
internal class FireflyApiException(
    val statusCode: Int,
    val errorCode: String?,
    message: String
) : IOException(message)

/** 新后端 `{ok,data}` 响应的集中解析与错误映射。 */
internal object FireflyV2ResponseParser {
    fun catalog(body: String): List<FireflyCatalogDescriptor> {
        val data = runCatching { successData(body, "线路目录").asJsonArray }
            .getOrElse { throw IOException("线路目录格式无效", it) }
        return buildList {
            for (index in 0 until data.size()) {
                val item = runCatching { data[index].asJsonObject }
                    .getOrElse { throw IOException("线路目录格式无效", it) }
                val id = item.text("id")
                val name = item.text("name")
                val contentUrl = item.text("contentUrl")
                if (id.isEmpty() || contentUrl.isEmpty()) throw IOException("线路目录格式无效")
                add(
                    FireflyCatalogDescriptor(
                        sourceId = id,
                        name = name.ifBlank { "节点分组 ${index + 1}" },
                        contentUrl = contentUrl,
                        cryptoVersion = item.integer("cryptoVersion"),
                        algorithm = item.text("algorithm")
                    )
                )
            }
        }
    }

    fun deviceToken(body: String, existingToken: String? = null): String {
        val data = runCatching { successData(body, "设备注册").asJsonObject }
            .getOrElse { throw IOException("设备注册响应格式无效", it) }
        return data.text("deviceToken").takeIf(String::isNotBlank)
            ?: existingToken
            ?: throw IOException("设备注册响应缺少令牌")
    }

    fun rotatedToken(body: String): String {
        val data = runCatching { successData(body, "设备密钥轮换").asJsonObject }
            .getOrElse { throw IOException("设备密钥轮换响应格式无效", it) }
        return data.text("deviceToken").takeIf(String::isNotBlank)
            ?: throw IOException("设备密钥轮换响应缺少令牌")
    }

    fun requireSuccess(body: String, label: String) {
        successData(body, label)
    }

    fun errorCode(body: String): String? = runCatching {
        JsonParser.parseString(body).asJsonObject.text("error").takeIf(String::isNotBlank)
    }.getOrNull()

    fun isAccessBanCode(code: String?): Boolean =
        code == "account_banned" || code == "device_banned"

    private fun successData(body: String, label: String): JsonElement {
        val root = runCatching { JsonParser.parseString(body).asJsonObject }
            .getOrElse { throw IOException("$label 响应格式无效", it) }
        if (runCatching { root["ok"]?.asBoolean }.getOrNull() != true) {
            throw IOException(errorMessage(root.text("error"), label))
        }
        return root["data"]?.takeUnless { it.isJsonNull }
            ?: throw IOException("$label 响应缺少 data")
    }

    private fun JsonObject.text(name: String): String =
        runCatching { get(name)?.asString.orEmpty().trim() }.getOrDefault("")

    private fun JsonObject.integer(name: String): Int =
        runCatching { get(name)?.asInt ?: 0 }.getOrDefault(0)

    private fun errorMessage(code: String, label: String): String = when (code) {
        "unauthorized" -> "设备凭据无效"
        "account_banned" -> "此匿名账号已被停用"
        "account_deleted" -> "此匿名账号已被删除"
        "device_banned" -> "此设备已被停用"
        "device_revoked" -> "此设备已被撤销"
        "device_conflict" -> "设备身份与服务端记录冲突"
        "subscription_not_found" -> "订阅不存在"
        "subscription_unavailable", "upstream_failed" -> "订阅暂时不可用"
        "unsupported_crypto_version" -> "服务端不支持当前 Crypto V2 版本"
        "invalid_challenge", "replay_detected" -> "订阅安全校验失败，请重试"
        "rate_limited" -> "请求过于频繁，请稍后重试"
        else -> "$label 请求失败${code.takeIf(String::isNotBlank)?.let { "（$it）" }.orEmpty()}"
    }
}

/** 对接 Firefly Edge `/api/v2` 的匿名设备注册、目录和 Crypto V2 安全下发。 */
class FireflySubscriptionClient(context: Context, clientOverride: OkHttpClient? = null) {
    private val deviceKeyManager = DeviceKeyManager(context)
    private val identityManager = DeviceIdentityManager(context)
    private val tokenStorage = SecureTokenStorage(context)
    private val cryptoV2 = SubscriptionCryptoV2()
    private val client: OkHttpClient = clientOverride
        ?: NetworkClient.withUserAgent(OkHttpClient.Builder())
            .connectTimeout(AppConfig.NODE_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(AppConfig.NODE_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(AppConfig.NODE_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()

    @Synchronized
    fun fetch(url: String, userAgent: String): String {
        val endpoint = url.toHttpUrl()
        return if (endpoint.encodedPath.trimEnd('/') == CLIENT_CATALOG_PATH) {
            val entries = fetchCatalogSubscriptions(endpoint, userAgent)
            val contents = entries.mapNotNull { it.content?.takeIf(String::isNotBlank) }
            if (contents.isEmpty()) {
                throw IOException("线路目录中没有可用订阅", entries.firstNotNullOfOrNull { it.error })
            }
            contents.joinToString("\n")
        } else {
            fetchCryptoV2(endpoint, userAgent)
        }
    }

    @Synchronized
    fun fetchCatalogSubscriptions(url: String, userAgent: String): List<FireflyCatalogSubscription> {
        val catalogUrl = url.toHttpUrl()
        if (catalogUrl.encodedPath.trimEnd('/') != CLIENT_CATALOG_PATH) {
            throw IOException("地址不是 Firefly V2 线路目录")
        }
        return fetchCatalogSubscriptions(catalogUrl, userAgent)
    }

    /** 只下载指定目录分组；sourceId 为空时用于兼容旧数据并选择目录首项。 */
    @Synchronized
    fun fetchCatalogSubscription(
        url: String,
        userAgent: String,
        sourceId: String?
    ): FireflyCatalogSubscription {
        val catalogUrl = url.toHttpUrl()
        if (catalogUrl.encodedPath.trimEnd('/') != CLIENT_CATALOG_PATH) {
            throw IOException("地址不是 Firefly V2 线路目录")
        }
        return fetchCatalogSubscriptions(
            catalogUrl = catalogUrl,
            userAgent = userAgent,
            requestedSourceId = sourceId,
            firstOnly = sourceId == null
        ).firstOrNull() ?: throw IOException("当前内置节点分组已不存在")
    }

    @Synchronized
    fun reportUsage(sessionId: String, uploadBytes: Long, downloadBytes: Long, userAgent: String) {
        require(sessionId.matches(Regex("^[A-Za-z0-9._:-]{1,128}$")))
        require(uploadBytes >= 0L && downloadBytes >= 0L)
        val endpoint = AppConfig.USAGE_REPORT_URL.toHttpUrl()
        val credentials = prepareDeviceCredentials(endpoint, userAgent)
        var token = credentials.token
        var exchange = requestUsage(
            endpoint, userAgent, credentials.deviceId, token,
            sessionId, uploadBytes, downloadBytes
        )
        if (exchange.statusCode == 401) {
            token = enrollWithCredentialRecovery(endpoint, userAgent, credentials.deviceId, token)
            exchange = requestUsage(
                endpoint, userAgent, credentials.deviceId, token,
                sessionId, uploadBytes, downloadBytes
            )
        }
        ensureSuccessful(exchange, "流量上报")
        FireflyV2ResponseParser.requireSuccess(exchange.body, "流量上报")
    }

    private fun fetchCatalogSubscriptions(
        catalogUrl: HttpUrl,
        userAgent: String,
        requestedSourceId: String? = null,
        firstOnly: Boolean = false
    ): List<FireflyCatalogSubscription> {
        val credentials = prepareDeviceCredentials(catalogUrl, userAgent)
        var token = credentials.token
        var catalog = requestAuthenticatedGet(catalogUrl, userAgent, credentials.deviceId, token)
        if (catalog.statusCode == 401) {
            token = enrollWithCredentialRecovery(catalogUrl, userAgent, credentials.deviceId, token)
            catalog = requestAuthenticatedGet(catalogUrl, userAgent, credentials.deviceId, token)
        }
        ensureSuccessful(catalog, "线路目录")
        val descriptors = FireflyV2ResponseParser.catalog(catalog.body)

        val selectedDescriptors = when {
            requestedSourceId != null -> descriptors.filter { it.sourceId == requestedSourceId }
            firstOnly -> descriptors.take(1)
            else -> descriptors
        }

        return selectedDescriptors.map { source ->
            val result = runCatching {
                if (source.cryptoVersion != CryptoV2Protocol.VERSION ||
                    source.algorithm != CryptoV2Protocol.ALGORITHM
                ) {
                    throw IOException("订阅使用了不支持的 Crypto V2 协议")
                }
                val subscriptionUrl = catalogUrl.resolve(source.contentUrl)
                    ?: throw IOException("订阅地址无效")
                requireSameOrigin(catalogUrl, subscriptionUrl)
                fetchCryptoV2(subscriptionUrl, userAgent)
            }
            result.fold(
                onSuccess = { content ->
                    FireflyCatalogSubscription(source.sourceId, source.name, content.trim())
                },
                onFailure = { failure ->
                    FireflyCatalogSubscription(source.sourceId, source.name, error = failure)
                }
            )
        }
    }

    private fun fetchCryptoV2(url: HttpUrl, userAgent: String): String {
        val credentials = prepareDeviceCredentials(url, userAgent)
        var token = credentials.token
        var exchange = requestCryptoV2(url, userAgent, credentials.deviceId, token)
        if (exchange.statusCode == 401) {
            token = enrollWithCredentialRecovery(url, userAgent, credentials.deviceId, token)
            exchange = requestCryptoV2(url, userAgent, credentials.deviceId, token)
        }
        return decryptV2Exchange(url, exchange, credentials.deviceId)
    }

    private fun decryptV2Exchange(url: HttpUrl, exchange: HttpExchange, deviceId: String): String {
        ensureSuccessful(exchange, "Crypto V2 订阅")
        if (responseVersion(exchange.body) != CryptoV2Protocol.VERSION) {
            throw IOException("服务端未返回 Crypto V2 响应，已拒绝降级")
        }
        return cryptoV2.decrypt(
            responseJson = exchange.body,
            privateKey = deviceKeyManager.getPrivateKey(),
            expectedDeviceId = deviceId,
            expectedRequestId = exchange.challenge
                ?: throw IOException("Crypto V2 请求缺少 challenge"),
            expectedSubscriptionId = subscriptionIdFromUrl(url)
        )
    }

    private fun prepareDeviceCredentials(url: HttpUrl, userAgent: String): DeviceCredentials {
        val keyWasMissing = !deviceKeyManager.hasKey()
        val token = tokenStorage.read()
        deviceKeyManager.ensureKeyPair()
        val deviceId = identityManager.getDeviceId()
        if (keyWasMissing && token != null) {
            try {
                rotateServerKey(url, userAgent, deviceId, token)
            } catch (error: FireflyApiException) {
                if (error.statusCode != 401) throw error
                // Android backup/restore may retain an encrypted token payload after its
                // Keystore key is gone. Discard that unusable credential and let enroll
                // rebind the newly generated public key.
                tokenStorage.clear()
            }
        }
        return DeviceCredentials(deviceId, tokenStorage.read())
    }

    private fun requestAuthenticatedGet(
        url: HttpUrl,
        userAgent: String,
        deviceId: String,
        token: String?
    ): HttpExchange = execute(
        Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", userAgent)
            .header(CryptoV2Protocol.DEVICE_ID_HEADER, deviceId)
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .build()
    )

    private fun requestCryptoV2(
        url: HttpUrl,
        userAgent: String,
        deviceId: String,
        token: String?
    ): HttpExchange {
        val challenge = SubscriptionCryptoV2.newChallenge()
        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", userAgent)
            .header(CryptoV2Protocol.DEVICE_ID_HEADER, deviceId)
            .header(CryptoV2Protocol.VERSION_HEADER, CryptoV2Protocol.VERSION.toString())
            .header(CryptoV2Protocol.CHALLENGE_HEADER, challenge)
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .build()
        return execute(request, challenge)
    }

    private fun requestUsage(
        url: HttpUrl,
        userAgent: String,
        deviceId: String,
        token: String?,
        sessionId: String,
        uploadBytes: Long,
        downloadBytes: Long
    ): HttpExchange {
        val json = JSONObject()
            .put("sessionId", sessionId)
            .put("uploadBytes", uploadBytes)
            .put("downloadBytes", downloadBytes)
            .toString()
        return execute(
            Request.Builder()
                .url(url)
                .post(json.toRequestBody(JSON_MEDIA_TYPE))
                .header("User-Agent", userAgent)
                .header(CryptoV2Protocol.DEVICE_ID_HEADER, deviceId)
                .apply { if (token != null) header("Authorization", "Bearer $token") }
                .build()
        )
    }

    private fun execute(request: Request, challenge: String? = null): HttpExchange =
        client.newCall(request).execute().use { response ->
            HttpExchange(
                statusCode = response.code,
                body = response.body?.string().orEmpty(),
                challenge = challenge
            )
        }

    private fun enroll(url: HttpUrl, userAgent: String, deviceId: String, existingToken: String?): String {
        val json = JSONObject()
            .put("deviceId", deviceId)
            .put("platform", "android")
            .put("deviceName", Build.MODEL.take(80))
            .put("publicKey", deviceKeyManager.getPublicKeySpkiBase64Url())
            .put("cryptoVersion", CryptoV2Protocol.VERSION)
            .toString()
        val request = Request.Builder()
            .url(apiEndpoint(url, "/api/v2/devices/enroll"))
            .post(json.toRequestBody(JSON_MEDIA_TYPE))
            .header("User-Agent", userAgent)
            .header(CryptoV2Protocol.DEVICE_ID_HEADER, deviceId)
            .apply { if (existingToken != null) header("Authorization", "Bearer $existingToken") }
            .build()
        return execute(request).let { response ->
            ensureSuccessful(response, "设备注册")
            FireflyV2ResponseParser.deviceToken(response.body, existingToken).also(tokenStorage::write)
        }
    }

    /**
     * Recovers the case where this installation has no usable token but its local public key
     * happens to equal the key already stored by the server. A fresh key makes the backend's
     * reinstall rebind path unambiguous; the stable hardware-derived device ID is unchanged.
     */
    private fun enrollWithCredentialRecovery(
        url: HttpUrl,
        userAgent: String,
        deviceId: String,
        existingToken: String?
    ): String {
        try {
            return enroll(url, userAgent, deviceId, existingToken)
        } catch (error: FireflyApiException) {
            if (error.statusCode != 401) throw error
        }

        tokenStorage.clear()
        deviceKeyManager.deleteKey()
        deviceKeyManager.ensureKeyPair()
        return enroll(url, userAgent, deviceId, existingToken = null)
    }

    private fun rotateServerKey(url: HttpUrl, userAgent: String, deviceId: String, token: String) {
        val json = JSONObject()
            .put("publicKey", deviceKeyManager.getPublicKeySpkiBase64Url())
            .toString()
        val response = execute(
            Request.Builder()
                .url(apiEndpoint(url, "/api/v2/devices/$deviceId/rotate-key"))
                .post(json.toRequestBody(JSON_MEDIA_TYPE))
                .header("User-Agent", userAgent)
                .header("Authorization", "Bearer $token")
                .header(CryptoV2Protocol.DEVICE_ID_HEADER, deviceId)
                .build()
        )
        ensureSuccessful(response, "设备密钥轮换")
        tokenStorage.write(FireflyV2ResponseParser.rotatedToken(response.body))
    }

    private fun apiEndpoint(url: HttpUrl, path: String): HttpUrl = url.newBuilder()
        .encodedPath(path)
        .query(null)
        .fragment(null)
        .build()

    private fun subscriptionIdFromUrl(url: HttpUrl): String? {
        val marker = url.pathSegments.indexOf("subscriptions")
        return if (marker >= 0) url.pathSegments.getOrNull(marker + 1)?.takeIf(String::isNotBlank)
        else null
    }

    private fun requireSameOrigin(catalogUrl: HttpUrl, subscriptionUrl: HttpUrl) {
        if (catalogUrl.scheme != subscriptionUrl.scheme ||
            catalogUrl.host != subscriptionUrl.host || catalogUrl.port != subscriptionUrl.port
        ) {
            throw IOException("拒绝向第三方域名发送 Firefly Crypto V2 设备凭据")
        }
    }

    private fun ensureSuccessful(exchange: HttpExchange, label: String) {
        val code = FireflyV2ResponseParser.errorCode(exchange.body)
        // Do not rely solely on the HTTP status: a proxy or future endpoint may preserve
        // the JSON error envelope while normalizing the response status.
        if (FireflyV2ResponseParser.isAccessBanCode(code)) {
            FireflyAccessBanEvents.publish(code!!)
            throw FireflyAccessBannedException(code)
        }
        if (exchange.statusCode !in 200..299) {
            val message = when (exchange.statusCode) {
                401 -> "设备凭据无效，请重新注册设备"
                403 -> when (code) {
                    "account_banned" -> "此匿名账号已被停用"
                    "account_deleted" -> "此匿名账号已被删除"
                    "device_revoked" -> "此设备已被撤销"
                    else -> "此设备已被停用"
                }
                409 -> "设备身份与服务端记录冲突"
                429 -> "$label 请求过于频繁，请稍后重试"
                else -> "$label 请求失败（HTTP ${exchange.statusCode}${code?.let { ", $it" }.orEmpty()}）"
            }
            throw FireflyApiException(exchange.statusCode, code, message)
        }
    }

    private fun responseVersion(body: String): Int =
        runCatching { JSONObject(body).optInt("version", 0) }.getOrDefault(0)

    private data class DeviceCredentials(val deviceId: String, val token: String?)

    private data class HttpExchange(
        val statusCode: Int,
        val body: String,
        val challenge: String? = null
    )

    private companion object {
        const val CLIENT_CATALOG_PATH = "/api/v2/subscriptions"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
