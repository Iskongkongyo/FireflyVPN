package xyz.a202132.app.network

import android.content.Context
import android.util.Log
import io.nekohasekai.libbox.Libbox
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.service.HeadlessPlatformInterface
import xyz.a202132.app.util.SingBoxConfigGenerator
import java.io.File
import java.net.Socket

/**
 * 流媒体解锁测试专用的临时无头 sing-box 管理器（单例串行）。
 */
object UnlockTestManager {

    private const val TAG = "UnlockTestManager"

    private var commandServer: io.nekohasekai.libbox.CommandServer? = null
    private var isRunning = false

    @Synchronized
    fun start(context: Context, node: Node, socksPort: Int): Boolean {
        if (isRunning) {
            Log.w(TAG, "Unlock test instance already running")
            return false
        }

        return try {
            val workDir = File(context.filesDir, "sing-box-unlock")
            if (!workDir.exists()) workDir.mkdirs()

            val options = io.nekohasekai.libbox.SetupOptions().apply {
                basePath = workDir.absolutePath
                workingPath = workDir.absolutePath
                tempPath = context.cacheDir.absolutePath
            }
            Libbox.setup(options)

            val config = SingBoxConfigGenerator().generateTestConfig(node, socksPort)
            val serverHandler = object : io.nekohasekai.libbox.CommandServerHandler {
                override fun serviceStop() {}
                override fun serviceReload() {}
                override fun getSystemProxyStatus(): io.nekohasekai.libbox.SystemProxyStatus {
                    return io.nekohasekai.libbox.SystemProxyStatus()
                }
                override fun setSystemProxyEnabled(enabled: Boolean) {}
                override fun writeDebugMessage(message: String?) {
                    Log.d(TAG, "Debug: $message")
                }
            }

            val platform = HeadlessPlatformInterface(context)
            commandServer = Libbox.newCommandServer(serverHandler, platform)
            commandServer?.start()
            commandServer?.startOrReloadService(config, io.nekohasekai.libbox.OverrideOptions())

            isRunning = true
            waitSocksReady(socksPort)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start unlock test instance", e)
            cleanup()
            false
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning) return
        cleanup()
    }

    private fun waitSocksReady(port: Int, retries: Int = 8, delayMs: Long = 500) {
        repeat(retries) {
            try {
                Socket("127.0.0.1", port).use { return }
            } catch (_: Exception) {
                Thread.sleep(delayMs)
            }
        }
    }

    private fun cleanup() {
        try {
            commandServer?.closeService()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing unlock test service", e)
        }
        try {
            commandServer?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing unlock test command server", e)
        }
        commandServer = null
        isRunning = false
    }
}
