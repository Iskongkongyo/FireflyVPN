package xyz.a202132.app.network

import xyz.a202132.app.data.model.ApiEnvelope
import xyz.a202132.app.data.model.ClientCatalogInfo
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url

interface ApiService {
    
    @GET
    suspend fun getSubscription(
        @Url url: String,
        @Header("User-Agent") userAgent: String
    ): String
    
    @GET
    suspend fun getClientBootstrap(@Url url: String): ApiEnvelope<ClientCatalogInfo>
}
