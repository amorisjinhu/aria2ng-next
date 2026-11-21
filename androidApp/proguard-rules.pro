# App is small; the real win from R8 here is shrinking the kotlinx/ktor deps.
# Only @Serializable models must survive whole — generated serializers touch
# them by name. Manifest components (Activity/Service/FileProvider) and
# Compose entry points are kept by AGP automatically; the rest of our own
# code is free to shrink/obfuscate.
-keep @kotlinx.serialization.Serializable class com.slipstream.** { *; }

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
