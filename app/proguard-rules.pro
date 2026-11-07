# EchoCast proguard rules

# --- Application / Android components -------------------------------------
-keep class com.rudisec.echocast.EchoCastApplication { *; }
-keep class com.rudisec.echocast.MainActivity { *; }
-keep class com.rudisec.echocast.DebugActivity { *; }
-keep class com.rudisec.echocast.PlaybackService { *; }
-keep class com.rudisec.echocast.EchoCastInCallService { *; }
-keep class com.rudisec.echocast.EchoCastTileService { *; }

# --- Models / serialization ----------------------------------------------
-keep class com.rudisec.echocast.AudioItem { *; }
-keep class com.rudisec.echocast.ai.** { *; }
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# --- Retrofit -------------------------------------------------------------
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**

# --- OkHttp optional TLS providers (not shipped) --------------------------
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
