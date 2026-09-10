package xyz.a202132.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.gson.GsonBuilder
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Converter
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import xyz.a202132.app.AppConfig
import java.lang.reflect.Type
import java.net.InetAddress
import java.util.concurrent.TimeUnit

object NetworkClient {

    fun withUserAgent(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        return builder.addInterceptor { chain ->
            val original = chain.request()
            val request = if (original.header("User-Agent").isNullOrBlank()) {
                original.newBuilder()
                    .header("User-Agent", AppConfig.HTTP_USER_AGENT)
                    .build()
            } else {
                original
            }
            chain.proceed(request)
        }
    }
    
    private val okHttpClient = withUserAgent(OkHttpClient.Builder())
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val gson = GsonBuilder()
        .setLenient()
        .create()

    // 订阅响应的字符串转换器
    private val stringConverterFactory = object : Converter.Factory() {
        override fun responseBodyConverter(
            type: Type,
            annotations: Array<out Annotation>,
            retrofit: Retrofit
        ): Converter<ResponseBody, *>? {
            return if (type == String::class.java) {
                Converter<ResponseBody, String> { it.string() }
            } else {
                null
            }
        }
    }
    
    private val retrofit = Retrofit.Builder()
        .baseUrl(AppConfig.API_BASE_URL + "/")
        .client(okHttpClient)
        .addConverterFactory(stringConverterFactory)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
    
    val apiService: ApiService = retrofit.create(ApiService::class.java)

    fun createApiService(client: OkHttpClient): ApiService = Retrofit.Builder()
        .baseUrl(AppConfig.API_BASE_URL + "/")
        .client(client)
        .addConverterFactory(stringConverterFactory)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
        .create(ApiService::class.java)

    /**
     * 创建绑定到底层 Wi-Fi/移动网络的客户端，用于 VPN 出口失效后的节点恢复请求。
     * 返回 null 表示设备当前没有可用的非 VPN 网络。
     */
    @Suppress("DEPRECATION")
    fun createUnderlyingNetworkClient(context: Context, timeoutMs: Long): OkHttpClient? {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivityManager.allNetworks.firstOrNull { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@firstOrNull false
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) ||
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ) {
                return@firstOrNull false
            }
            true
        }
            ?: return null

        return withUserAgent(OkHttpClient.Builder())
            .socketFactory(network.socketFactory)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    network.getAllByName(hostname).toList()
            })
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
    }

    // OkHttpClient 用于延迟测试（更短的超时时间）
    val latencyTestClient = withUserAgent(OkHttpClient.Builder())
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()
}
