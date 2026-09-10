package xyz.a202132.app.util

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object UnlockTestsRunner {

    private const val TAG = "UnlockTestsRunner"
    private const val NATIVE_BINARY_NAME = "libut.so"

    data class Result(
        val exitCode: Int,
        val stdout: String
    )

    private fun nativeBinary(context: Context): File? {
        val nativeDir = context.applicationInfo.nativeLibraryDir ?: return null
        val file = File(nativeDir, NATIVE_BINARY_NAME)
        return if (file.exists()) file else null
    }

    fun run(
        context: Context,
        args: List<String>,
        timeoutSeconds: Long = 90
    ): Result {
        val native = nativeBinary(context)
        if (native == null) {
            return Result(-2, "ut binary not found in nativeLibraryDir")
        }

        Log.d(TAG, "Run native binary: ${native.absolutePath}")
        val outputExecutor = Executors.newSingleThreadExecutor()
        var process: Process? = null
        return try {
            val command = mutableListOf(native.absolutePath).apply { addAll(args) }
            val startedProcess = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            process = startedProcess

            // 持续读取输出，避免子进程因 stdout 管道写满而无法结束。
            val outputFuture = outputExecutor.submit<String> {
                startedProcess.inputStream.bufferedReader().use { it.readText() }
            }
            val exitCode = waitForExitCode(startedProcess, timeoutSeconds)
            if (exitCode == null) {
                startedProcess.destroy()
                outputFuture.cancel(true)
                return Result(-1, "timeout after ${timeoutSeconds}s")
            }
            val output = outputFuture.get(5, TimeUnit.SECONDS)
            Result(exitCode, output)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.e(TAG, "Native ut binary interrupted", e)
            Result(-2, "native ut binary interrupted")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start native ut binary: ${e.message}", e)
            Result(-2, "failed to start ut binary: ${e.message ?: "unknown"}")
        } finally {
            process?.destroy()
            outputExecutor.shutdownNow()
        }
    }

    /**
     * Process.waitFor(timeout, unit) requires API 26. Polling exitValue keeps
     * the runner compatible with the app's API 24 minimum without busy-waiting.
     */
    private fun waitForExitCode(process: Process, timeoutSeconds: Long): Int? {
        val timeoutNanos = TimeUnit.SECONDS.toNanos(timeoutSeconds.coerceAtLeast(0L))
        val startedAt = System.nanoTime()
        while (true) {
            try {
                return process.exitValue()
            } catch (_: IllegalThreadStateException) {
                if (System.nanoTime() - startedAt >= timeoutNanos) return null
                Thread.sleep(50L)
            }
        }
    }
}
