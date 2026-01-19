package xyz.a202132.app.network

import xyz.a202132.app.data.model.NoticeInfo
import xyz.a202132.app.data.model.UpdateInfo
import retrofit2.http.GET
import retrofit2.http.Url

interface ApiService {
    
    @GET
    suspend fun getSubscription(@Url url: String): String
    
    @GET
    suspend fun getUpdateInfo(@Url url: String): UpdateInfo
    
    @GET
    suspend fun getNoticeInfo(@Url url: String): NoticeInfo
}
