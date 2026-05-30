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

# ---- Tink / androidx.security-crypto (encrypted HF token) ----
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# Keep ViewModel + Application entry points (instantiated reflectively).
-keep class com.nativelm.app.SampleApplication { *; }
-keep class com.nativelm.app.MainActivity { *; }
