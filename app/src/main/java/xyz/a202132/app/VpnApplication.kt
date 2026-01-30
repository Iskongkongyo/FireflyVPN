package xyz.a202132.app

import android.app.Application
import xyz.a202132.app.util.SignatureVerifier

class VpnApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        // 签名校验 - 如果 APK 被篡改，APP 会立即 Crash
        SignatureVerifier.verifySignature(this)
        
        instance = this
    }
    
    companion object {
        lateinit var instance: VpnApplication
            private set
    }
}
