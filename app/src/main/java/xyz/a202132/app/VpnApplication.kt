package xyz.a202132.app

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import xyz.a202132.app.service.VpnConnectivityRecoveryManager
import xyz.a202132.app.util.RuleManager
import xyz.a202132.app.util.RuntimeLog
import xyz.a202132.app.util.SignatureVerifier

class VpnApplication : Application() {
    
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var connectivityRecoveryManager: VpnConnectivityRecoveryManager
    
    override fun onCreate() {
        super.onCreate()
        
        // 签名校验 - 如果 APK 被篡改，APP 会立即 Crash
        SignatureVerifier.verifySignature(this)
        
        instance = this
        RuntimeLog.init(this)
        RuntimeLog.info("App", "Application started")

        connectivityRecoveryManager = VpnConnectivityRecoveryManager(this, applicationScope)
        connectivityRecoveryManager.start()
        
        // 后台更新规则集（不阻塞启动，失败不影响使用）
        applicationScope.launch(Dispatchers.IO) {
            try {
                RuleManager.updateRuleSets(this@VpnApplication)
            } catch (e: Exception) {
                Log.w("VpnApplication", "Background rule set update failed", e)
                RuntimeLog.warn("RuleManager", "Background rule set update failed", e)
            }
        }
    }
    
    companion object {
        lateinit var instance: VpnApplication
            private set
    }
}
