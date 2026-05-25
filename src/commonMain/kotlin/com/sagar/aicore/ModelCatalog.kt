package com.sagar.aicore

/**
 * Typed catalog of downloadable model files. Replaces the loose
 * URL/filename string constants previously held in
 * `SetupViewModel` so the [EngineRegistry], setup flow, and model
 * management UI all read from one source of truth.
 *
 * In Phase 1 backed by an in-code static list; eventually backed by a
 * Supabase Storage row once the backend ships.
 */
data class ModelDescriptor(
    val id: String,
    val url: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String? = null,
    val format: ModelFormat,
    val role: ModelRole,
    val minDeviceRamMb: Long,
)

enum class ModelFormat {
    /** LiteRT-LM bundle (text decoder + embedding params + audio mel). */
    LITERTLM,
    /** MediaPipe Tasks Text Embedder `.tflite` (universal sentence encoder). */
    MEDIAPIPE_TEXT_EMBEDDER,
}

enum class ModelRole {
    /** Primary on-device LLM for quiz + chat. Exactly one active descriptor per RAM tier has this role. */
    LLM_PRIMARY,
    /** Embedding model for RAG retrieval. */
    EMBEDDING,
}

interface ModelCatalog {
    fun all(): List<ModelDescriptor>
    fun byId(id: String): ModelDescriptor?
    fun byRole(role: ModelRole): List<ModelDescriptor>
}
