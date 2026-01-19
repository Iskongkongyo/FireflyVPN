package xyz.a202132.app.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object RuleManager {
    private const val TAG = "RuleManager"
    
    // Using jsDelivr for CDN acceleration
    private const val GEOIP_URL = "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/geoip.db"
    private const val GEOSITE_URL = "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite.db"

    suspend fun checkAndDownloadRules(context: Context): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val configDir = File(context.filesDir, "sing-box")
                if (!configDir.exists()) {
                    configDir.mkdirs()
                }

                val geoipFile = File(configDir, "geoip.db")
                val geositeFile = File(configDir, "geosite.db")

                var success = true
                
                if (!geoipFile.exists() || geoipFile.length() < 1024) {
                    Log.i(TAG, "Downloading geoip.db...")
                    if (!downloadFile(GEOIP_URL, geoipFile)) {
                        success = false
                    }
                }

                if (!geositeFile.exists() || geositeFile.length() < 1024) {
                    Log.i(TAG, "Downloading geosite.db...")
                    if (!downloadFile(GEOSITE_URL, geositeFile)) {
                        success = false
                    }
                }

                success
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check rules", e)
                false
            }
        }
    }

    private fun downloadFile(urlStr: String, targetFile: File): Boolean {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(urlStr)
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.requestMethod = "GET"
            conn.connect()

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val inputStream = conn.inputStream
                val outputStream = FileOutputStream(targetFile)
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                }
                outputStream.close()
                inputStream.close()
                Log.i(TAG, "Downloaded ${targetFile.name} successfully")
                return true
            } else {
                Log.e(TAG, "Download failed: HTTP ${conn.responseCode}")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download error for $urlStr", e)
            return false
        } finally {
            conn?.disconnect()
        }
    }
}
