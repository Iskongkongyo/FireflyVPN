package xyz.a202132.app.util

import android.content.Context
import android.util.Log
import java.io.File
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
        return try {
            val command = mutableListOf(native.absolutePath).apply { addAll(args) }
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                return Result(-1, "timeout after ${timeoutSeconds}s")
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            Result(process.exitValue(), output)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start native ut binary: ${e.message}", e)
            Result(-2, "failed to start ut binary: ${e.message ?: "unknown"}")
        }
    }
}
