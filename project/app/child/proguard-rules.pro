# ==========================================
# Child App ProGuard/R8 Rules
# Privacy-First Child Helper
# ==========================================

# === Data Classes for Serialization ===
-keep class com.childhelper.core.common.model.** { *; }
-keep class com.childhelper.core.network.model.** { *; }
-keep class com.childhelper.core.network.signaling.** { *; }

# === Room Entities ===
-keep class com.childhelper.app.child.db.** { *; }
-keepclassmembers class com.childhelper.app.child.db.** { *; }

# === WebRTC ===
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
-keepclassmembers class org.webrtc.** { *; }

# === Firebase ===
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# === Hilt/Dagger ===
-keep class dagger.hilt.** { *; }
-dontwarn dagger.hilt.**
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-keepclassmembers @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }

# === Serialization ===
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature, Exceptions, SourceFile, LineNumberTable
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
    @kotlinx.serialization.Transient <fields>;
}
-keep class * implements kotlinx.serialization.** { *; }

# === CameraX ===
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# === LiteRT / TensorFlow Lite ===
-keep class com.google.ai.edge.litert.** { *; }
-dontwarn com.google.ai.edge.litert.**

# === AndroidX / Compose ===
-keep class androidx.compose.** { *; }
-keep class androidx.lifecycle.** { *; }
-keepclassmembers class androidx.lifecycle.** { *; }

# === OkHttp / Retrofit ===
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# === Coroutines ===
-keepclassmembernames class kotlinx.coroutines.** {
    volatile <fields>;
}

# === General Android ===
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# === Remove logs in release ===
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

# Keep security classes from obfuscation
-keep class com.childhelper.core.security.** { *; }
-keepclassmembers class com.childhelper.core.security.** { *; }
