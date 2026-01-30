package xyz.a202132.app.util

import android.content.Context
import android.util.Log

/**
 * Native signature verification to prevent tampered APKs from running.
 */
object SignatureVerifier {
    private const val TAG = "SignatureVerifier"

    init {
        try {
            System.loadLibrary("native-lib")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library", e)
        }
    }

    /**
     * Verify the APK signature against the expected hash.
     * If verification fails, the app will crash (native abort).
     */
    @JvmStatic
    external fun verifySignature(context: Context)
}
