# Add project specific ProGuard rules here.

# Keep sing-box libbox classes
-keep class io.nekohasekai.** { *; }
-keep class libbox.** { *; }

# Keep data models for Gson
-keep class xyz.a202132.app.data.model.** { *; }
-keep interface xyz.a202132.app.data.model.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# Keep Room entities
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# Retrofit
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes *Annotation*
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-keep interface xyz.a202132.app.network.ApiService { *; }
-keep class kotlin.coroutines.Continuation

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# SQLCipher (JNI depends on internal field/method names; do not obfuscate)
-keep class net.sqlcipher.** { *; }
-dontwarn net.sqlcipher.**
# Future-proof if migrating to newer sqlcipher-android package names
-keep class net.zetetic.database.sqlcipher.** { *; }
-dontwarn net.zetetic.database.sqlcipher.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Remove all Log calls in Release build
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
    # 日后需要保留 Warn 和 Error用于崩溃分析，可以删除下面三行
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}
