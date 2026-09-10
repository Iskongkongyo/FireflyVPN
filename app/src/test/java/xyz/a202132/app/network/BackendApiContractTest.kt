package xyz.a202132.app.network

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.a202132.app.AppConfig
import xyz.a202132.app.data.model.ApiEnvelope
import xyz.a202132.app.data.model.ClientCatalogInfo
import xyz.a202132.app.util.crypto.CryptoV2Protocol
import java.io.IOException
import java.net.URI

class BackendApiContractTest {
    @Test
    fun `bootstrap envelope matches backend v2 schema`() {
        val type = TypeToken.getParameterized(
            ApiEnvelope::class.java,
            ClientCatalogInfo::class.java
        ).type
        val envelope = Gson().fromJson<ApiEnvelope<ClientCatalogInfo>>(
            """
            {
              "ok": true,
              "data": {
                "crypto": {"version": 2, "algorithm": "P256-HKDF-SHA256-A256GCM"},
                "notice": {"enabled": true, "id": "n1", "title": "公告", "content": "内容", "showOnce": true},
                "appUpdate": {"versionCode": 22, "versionName": "2.4.0", "downloadUrl": "https://example.com/app.apk", "force": true, "changelog": "更新"},
                "settings": {"websiteUrl": "https://example.com", "feedbackEmail": "a@example.com", "feedbackUrl": "", "githubUrl": "https://github.com/example/repo"}
              }
            }
            """.trimIndent(),
            type
        )

        assertTrue(envelope.ok)
        val bootstrap = requireNotNull(envelope.data)
        assertEquals(CryptoV2Protocol.VERSION, bootstrap.crypto?.version)
        assertEquals(CryptoV2Protocol.ALGORITHM, bootstrap.crypto?.algorithm)
        assertEquals("n1", bootstrap.notice?.noticeId)
        assertTrue(bootstrap.notice?.hasNotice == true)
        assertEquals("2.4.0", bootstrap.appUpdate?.version)
        assertEquals(1, bootstrap.appUpdate?.isForce)
    }

    @Test
    fun `subscription catalog uses contentUrl and strict crypto v2`() {
        val catalog = FireflyV2ResponseParser.catalog(
            """
            {"ok":true,"data":[
              {"id":"main","name":"主线路","contentUrl":"/api/v2/subscriptions/main/content","cryptoVersion":2,"algorithm":"P256-HKDF-SHA256-A256GCM"}
            ]}
            """.trimIndent()
        )

        assertEquals(1, catalog.size)
        assertEquals("main", catalog.single().sourceId)
        assertEquals("/api/v2/subscriptions/main/content", catalog.single().contentUrl)
        assertEquals(CryptoV2Protocol.VERSION, catalog.single().cryptoVersion)
        assertEquals(CryptoV2Protocol.ALGORITHM, catalog.single().algorithm)
    }

    @Test
    fun `device token parser handles first and repeated enrollment`() {
        val token = "a".repeat(43)
        assertEquals(
            token,
            FireflyV2ResponseParser.deviceToken(
                """{"ok":true,"data":{"deviceToken":"$token","alreadyEnrolled":false}}"""
            )
        )
        assertEquals(
            token,
            FireflyV2ResponseParser.deviceToken(
                """{"ok":true,"data":{"alreadyEnrolled":true}}""",
                existingToken = token
            )
        )
    }

    @Test
    fun `error envelope is rejected and api domain stays unchanged`() {
        assertThrows(IOException::class.java) {
            FireflyV2ResponseParser.catalog(
                """{"ok":false,"error":"unauthorized","requestId":"request-1"}"""
            )
        }

        val expectedHost = "ly.202132.xyz"
        assertEquals(expectedHost, URI(AppConfig.API_BASE_URL).host)
        assertEquals(expectedHost, URI(AppConfig.BOOTSTRAP_URL).host)
        assertEquals(expectedHost, URI(AppConfig.SUBSCRIPTION_URL).host)
        assertEquals(expectedHost, URI(AppConfig.USAGE_REPORT_URL).host)
        assertTrue(AppConfig.BOOTSTRAP_URL.endsWith("/api/v2/bootstrap"))
        assertTrue(AppConfig.SUBSCRIPTION_URL.endsWith("/api/v2/subscriptions"))
        assertFalse(AppConfig.SUBSCRIPTION_URL.contains("/api/client"))
    }

    @Test
    fun `account and device ban codes are recognized`() {
        assertTrue(FireflyV2ResponseParser.isAccessBanCode("account_banned"))
        assertTrue(FireflyV2ResponseParser.isAccessBanCode("device_banned"))
        assertFalse(FireflyV2ResponseParser.isAccessBanCode("account_deleted"))
        assertFalse(FireflyV2ResponseParser.isAccessBanCode("unauthorized"))
        assertFalse(FireflyV2ResponseParser.isAccessBanCode(null))
    }
}
