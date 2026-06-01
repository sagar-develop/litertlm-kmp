# Consumer R8/ProGuard rules shipped with litertlm-kmp.
# Any app that depends on this engine inherits these automatically, so their
# release (minified) builds keep the reflection/JNI surfaces the engine relies on.

# ---- LiteRT-LM (com.google.ai.edge.litertlm) ----
# JNI bridge + native methods, and the Gson-backed tool-calling surface
# (OpenApiTool / ToolCall / ReflectionTool reflect over these types). The AAR
# ships no consumer rules of its own, so keep the package wholesale.
# The native-methods keep is shared with MediaPipe below (both use JNI);
# includedescriptorclasses also pins the parameter/return types they bind to.
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

# ---- Gson (used by LiteRT-LM tool calling) ----
-keep class com.google.gson.** { *; }
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
# Don't warn on Gson's optional/desugared references.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# ---- MediaPipe text embedder (com.google.mediapipe:tasks-text) ----
# The embedder's MediaPipe Graph stack-walks during static init to find its
# caller; R8 renaming/inlining/merging breaks that walk ("no caller found on the
# stack for: ... at Graph.<clinit>" → ExceptionInInitializerError). This is a
# runtime-only crash, latent until something actually runs MediaPipe. Keep
# MediaPipe + protobuf + JNI (above) + Flogger — MediaPipe logs through Flogger,
# whose FluentLogger.forEnclosingClass() stack-walks the same way — so both
# stack inspections still resolve under minification.
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**
-keep class com.google.common.flogger.** { *; }
-dontwarn com.google.common.flogger.**

# ---- kotlinx.serialization ----
# Keep generated serializers + the synthetic serializer() accessor.
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers, allowshrinking class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers @kotlinx.serialization.Serializable class * {
    *** Companion;
}
