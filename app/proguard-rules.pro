# Proguard rules for Alaktra Music Player
-keep class com.example.model.** { *; }
-keep class com.example.data.remote.** { *; }
-keep class com.example.data.local.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Gson rules
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Retrofit rules
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes Exceptions

# OkHttp rules
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Media3 / ExoPlayer rules
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Strip verbose/debug logs in release builds to prevent credential/state leakage
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}
