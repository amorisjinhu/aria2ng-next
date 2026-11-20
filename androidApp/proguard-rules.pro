# App is small; the real win from R8 here is shrinking the kotlinx/ktor deps.
# Keep our own classes whole so JSON DTOs, Compose entry points and the
# FileProvider authority can never be obfuscated into breakage.
-keep class com.slipstream.** { *; }
-keepnames class com.slipstream.** { *; }

# kotlinx.serialization — generated Companion/$serializer lookup (canonical rules)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class com.slipstream.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Generic / reflection metadata kept for serialization + coroutines
-keepattributes Signature, Exceptions, InnerClasses, EnclosingMethod

# Optional libs referenced reflectively by ktor/coroutines
-dontwarn org.slf4j.**
-dontwarn io.netty.**
-dontwarn kotlinx.atomicfu.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn sun.misc.**
-dontwarn java.lang.management.**
-dontwarn reactor.blockhound.**
