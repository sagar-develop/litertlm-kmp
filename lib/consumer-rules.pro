# Consumer R8/ProGuard rules shipped with litertlm-kmp.
# Any app that depends on this engine inherits these automatically, so their
# release (minified) builds keep the reflection/JNI surfaces the engine relies on.

# ---- LiteRT-LM (com.google.ai.edge.litertlm) ----
# JNI bridge + native methods, and the Gson-backed tool-calling surface
# (OpenApiTool / ToolCall / ReflectionTool reflect over these types). The AAR
# ships no consumer rules of its own, so keep the package wholesale.
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# ---- Gson (used by LiteRT-LM tool calling) ----
-keep class com.google.gson.** { *; }
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
# Don't warn on Gson's optional/desugared references.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# ---- MediaPipe text embedder (com.google.mediapipe:tasks-text) ----
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**

# ---- kotlinx.serialization ----
# Keep generated serializers + the synthetic serializer() accessor.
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers, allowshrinking class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers @kotlinx.serialization.Serializable class * {
    *** Companion;
}
