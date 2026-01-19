package xyz.a202132.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import xyz.a202132.app.data.model.ProxyMode
import xyz.a202132.app.service.ServiceManager
import xyz.a202132.app.ui.screens.MainScreen
import xyz.a202132.app.ui.theme.BackgroundDark
import xyz.a202132.app.ui.theme.FireflyVPNTheme
import xyz.a202132.app.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    
    private var pendingVpnAction: (() -> Unit)? = null
    
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // VPN权限已授予，执行之前的操作
            pendingVpnAction?.invoke()
        } else {
            Toast.makeText(this, "VPN权限被拒绝", Toast.LENGTH_SHORT).show()
        }
        pendingVpnAction = null
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            FireflyVPNTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val viewModel: MainViewModel = viewModel()
                    
                    MainScreen(
                        viewModel = viewModel,
                        onStartVpn = { action ->
                            requestVpnPermission(action)
                        }
                    )
                }
            }
        }
    }
    
    private fun requestVpnPermission(action: () -> Unit) {
        val intent = android.net.VpnService.prepare(this)
        if (intent != null) {
            pendingVpnAction = action
            vpnPermissionLauncher.launch(intent)
        } else {
            // 已有权限，直接执行
            action()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
    }
}
