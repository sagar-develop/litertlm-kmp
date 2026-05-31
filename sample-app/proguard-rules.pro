# App-specific R8/ProGuard rules for NativeLM.
# Engine-side keeps (LiteRT-LM, MediaPipe, Gson, kotlinx.serialization) arrive
# via litertlm-kmp's consumer-rules.pro — only app-only deps live here.

# ---- ObjectBox ----
# Entities + the generated MyObjectBox / EntityCursor / Entity_ metamodel are
# read reflectively + by the native store, so keep the whole db package and any
# @Entity-annotated class.
-keep class com.nativelm.app.data.db.** { *; }
-keep @io.objectbox.annotation.Entity class * { *; }
-keep class io.objectbox.** { *; }
-keepclassmembers class * {
    @io.objectbox.annotation.* <fields>;
}
-dontwarn io.objectbox.**

# ---- MediaPipe Tasks (USE-Lite text embedder for RAG) ----
# v0.3 never ran MediaPipe at runtime (the LLM is LiteRT-LM), so this gap was
# latent. The embedder's MediaPipe Graph does a stack-walk in its static init to
# locate its caller; R8 renaming/inlining breaks it ("no caller found on the
# stack for: s4.c" → ExceptionInInitializerError at Graph.<clinit>). Keep MediaPipe
# + protobuf + JNI intact so the stack-walk and native loading still resolve.
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
# MediaPipe logs via Google Flogger. FluentLogger.forEnclosingClass() walks the
# stack to auto-detect the caller class; R8 renaming/merging breaks that walk
# ("no caller found on the stack for: ...FluentLogger" at Graph.<clinit>). Keep
# Flogger intact so the stack inspection resolves.
-keep class com.google.common.flogger.** { *; }
-dontwarn com.google.common.flogger.**

# ---- PDFBox (tom-roush) — document text extraction for RAG ----
# Loads fonts/resources reflectively and references AWT/Harmony stubs absent on
# Android; keep the package and silence the missing-class warnings.
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-dontwarn java.awt.**
-dontwarn javax.**
-dontwarn org.apache.**

# ---- Tink / androidx.security-crypto (encrypted HF token) ----
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# Keep ViewModel + Application entry points (instantiated reflectively).
-keep class com.nativelm.app.SampleApplication { *; }
-keep class com.nativelm.app.MainActivity { *; }
