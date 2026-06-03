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
# The MediaPipe + protobuf + Flogger + JNI keeps that fix the release-only
# Graph.<clinit> stack-walk crash live in the engine's consumer-rules.pro
# (litertlm-kmp owns the MediaPipe dependency), so every consumer inherits them.
# Only app-owned deps (PDFBox below) need rules here.

# ---- PDFBox (tom-roush) — document text extraction for RAG ----
# Loads fonts/resources reflectively and references AWT/Harmony stubs absent on
# Android; keep the package and silence the missing-class warnings.
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-dontwarn java.awt.**
-dontwarn javax.**
-dontwarn org.apache.**

# ---- ML Kit text recognition (on-device OCR for scanned PDFs + images) ----
# ML Kit ships its own consumer rules, but keep its packages + the bundled-model
# internals defensively (loaded via reflection/JNI) so a minified release doesn't
# strip them — the same class of runtime-only crash we hit with MediaPipe.
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-keep class com.google.android.gms.internal.mlkit_vision_text_** { *; }
-dontwarn com.google.android.gms.**

# ---- Tink / androidx.security-crypto (encrypted HF token) ----
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# ---- Signal Argon2 (native JNI key derivation for encrypted backups) ----
# Argon2Native.hash is a native method bound by JNI; keep the JNI surface so R8
# doesn't strip the method/class the native library resolves by name.
-keep class org.signal.argon2.** { *; }
-dontwarn org.signal.argon2.**

# ---- kotlinx.serialization (@Serializable backup DTOs) ----
# Keep the generated $serializer + serializer() accessors for the backup models so
# the minified release can encode/decode them.
-keepclassmembers class com.nativelm.app.data.backup.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.nativelm.app.data.backup.**$$serializer { *; }

# Keep ViewModel + Application entry points (instantiated reflectively).
-keep class com.nativelm.app.SampleApplication { *; }
-keep class com.nativelm.app.MainActivity { *; }
