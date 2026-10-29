# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class app.polyphony.**$$serializer { *; }
-keepclassmembers class app.polyphony.** { *** Companion; }
-keepclasseswithmembers class app.polyphony.** { kotlinx.serialization.KSerializer serializer(...); }

# OkHttp / Retrofit
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepattributes Signature, Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-if interface * { @retrofit2.http.* public *** *(...); }
-keep,allowoptimization,allowshrinking,allowobfuscation class <3>

# Jellyfin SDK
-keep class org.jellyfin.sdk.model.** { *; }

# Cast
-keep class com.google.android.gms.cast.framework.** { *; }
-keep class app.polyphony.core.playback.cast.** { *; }

# SLF4J is an optional dependency of kotlin-logging (pulled in by the Jellyfin SDK); the
# Android build has no SLF4J binding, which is fine.
-dontwarn org.slf4j.**
