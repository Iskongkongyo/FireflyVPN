#include <jni.h>
#include <string>
#include <android/log.h>

#define TAG "NativeSecurity"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

/**
 * Native library for secure key storage and signature verification.
 */

// Expected signature SHA-256 hash (uppercase, no colons)
static const char* EXPECTED_SIGNATURE = "YOUR_RELEASE_SIGNATURE_SHA256_HERE";

/**
 * Verify APK signature against expected hash.
 * If signature doesn't match, crash the app.
 */
extern "C" JNIEXPORT void JNICALL Java_xyz_a202132_app_util_SignatureVerifier_verifySignature(
        JNIEnv* env,
        jclass /* clazz */,
        jobject context) {
    
    // Get PackageManager
    jclass contextClass = env->GetObjectClass(context);
    jmethodID getPackageManager = env->GetMethodID(contextClass, "getPackageManager", "()Landroid/content/pm/PackageManager;");
    jobject packageManager = env->CallObjectMethod(context, getPackageManager);
    
    // Get package name
    jmethodID getPackageName = env->GetMethodID(contextClass, "getPackageName", "()Ljava/lang/String;");
    jstring packageName = (jstring)env->CallObjectMethod(context, getPackageName);
    
    // Get PackageInfo with signing certificates
    jclass pmClass = env->GetObjectClass(packageManager);
    jmethodID getPackageInfo = env->GetMethodID(pmClass, "getPackageInfo", "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;");
    
    // GET_SIGNING_CERTIFICATES = 0x08000000 (API 28+), fallback to GET_SIGNATURES = 64
    jint flags = 64; // PackageManager.GET_SIGNATURES
    jobject packageInfo = env->CallObjectMethod(packageManager, getPackageInfo, packageName, flags);
    
    if (packageInfo == nullptr) {
        LOGE("Failed to get PackageInfo");
        abort();
    }
    
    // Get signatures array
    jclass piClass = env->GetObjectClass(packageInfo);
    jfieldID signaturesField = env->GetFieldID(piClass, "signatures", "[Landroid/content/pm/Signature;");
    jobjectArray signatures = (jobjectArray)env->GetObjectField(packageInfo, signaturesField);
    
    if (signatures == nullptr || env->GetArrayLength(signatures) == 0) {
        LOGE("No signatures found");
        abort();
    }
    
    // Get first signature
    jobject signature = env->GetObjectArrayElement(signatures, 0);
    jclass sigClass = env->GetObjectClass(signature);
    jmethodID toByteArray = env->GetMethodID(sigClass, "toByteArray", "()[B");
    jbyteArray signatureBytes = (jbyteArray)env->CallObjectMethod(signature, toByteArray);
    
    // Calculate SHA-256 hash using MessageDigest
    jclass mdClass = env->FindClass("java/security/MessageDigest");
    jmethodID getInstance = env->GetStaticMethodID(mdClass, "getInstance", "(Ljava/lang/String;)Ljava/security/MessageDigest;");
    jstring sha256 = env->NewStringUTF("SHA-256");
    jobject md = env->CallStaticObjectMethod(mdClass, getInstance, sha256);
    
    jmethodID digest = env->GetMethodID(mdClass, "digest", "([B)[B");
    jbyteArray hashBytes = (jbyteArray)env->CallObjectMethod(md, digest, signatureBytes);
    
    // Convert hash to hex string
    jsize hashLen = env->GetArrayLength(hashBytes);
    jbyte* hash = env->GetByteArrayElements(hashBytes, nullptr);
    
    char hexHash[65];
    for (int i = 0; i < hashLen; i++) {
        sprintf(hexHash + i * 2, "%02X", (unsigned char)hash[i]);
    }
    hexHash[64] = '\0';
    
    env->ReleaseByteArrayElements(hashBytes, hash, 0);
    
    LOGI("Current signature hash: %s", hexHash);
    LOGI("Expected signature hash: %s", EXPECTED_SIGNATURE);
    
    // Compare
    if (strcmp(hexHash, EXPECTED_SIGNATURE) != 0) {
        LOGE("Signature verification failed! APK has been tampered with.");
        // Crash the app
        abort();
    }
    
    LOGI("Signature verification passed.");
}

/**
 * Get obfuscated AES key.
 */
extern "C" JNIEXPORT jstring JNICALL Java_xyz_a202132_app_util_CryptoUtils_getNativeKey(JNIEnv* env, jobject /* this */) {

 // AES密钥示例: "MySecretKey12345" (16 bytes)
// 混淆算法：加密字节 = 原始字符 ^ (SEED + 索引)
const int SEED = 0x33;
unsigned char encrypted_key[] = {
    0x7E, 0x7D, 0x20, 0x5E, 0x5A, 0x60, 0x5A, 0x40,
    0x2A, 0x38, 0x0E, 0x45, 0x00, 0x08, 0x09, 0x03,
    0x00
};

    char decoded_key[17];
    for (int i = 0; i < 16; i++) {
        decoded_key[i] = encrypted_key[i] ^ (SEED + i);
    }
    decoded_key[16] = '\0';

    return env->NewStringUTF(decoded_key);
}
