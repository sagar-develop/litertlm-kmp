/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore

/**
 * Typed catalog of downloadable model files. Consumers wire this so the
 * [EngineRegistry], their setup flow, and any management UI all read from
 * one source of truth â€” without scattering URL/filename string constants
 * across the codebase.
 *
 * The built-in [InMemoryModelCatalog] is a sample implementation; for
 * production, back it with whichever remote config service you use so new
 * model versions ship without an APK update.
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
    /**
     * When true, downloading this model requires an auth token (e.g. a
     * Hugging Face access token sent as `Authorization: Bearer …`). Consumers
     * gate the download UI on a token being present and pass it through
     * [ModelManager.downloadModel]'s `headers`. Defaults to false.
     */
    val requiresAuth: Boolean = false,
    /**
     * Whether this `.litertlm` bundle ships a vision encoder (multimodal). The
     * engine wires `EngineConfig.visionBackend` only for vision models — a
     * text-only bundle fails to initialize when a vision backend is attached, and
     * skipping it on text-only models also frees memory. Defaults to false.
     */
    val supportsVision: Boolean = false,
)

enum class ModelFormat {
    /** LiteRT-LM bundle (text decoder + embedding params + audio mel). */
    LITERTLM,
    /** MediaPipe Tasks Text Embedder `.tflite` (universal sentence encoder). */
    MEDIAPIPE_TEXT_EMBEDDER,
}

enum class ModelRole {
    /** Primary on-device LLM. Exactly one active descriptor per RAM tier has this role. */
    LLM_PRIMARY,
    /** Embedding model for RAG retrieval. */
    EMBEDDING,
}

interface ModelCatalog {
    fun all(): List<ModelDescriptor>
    fun byId(id: String): ModelDescriptor?
    fun byRole(role: ModelRole): List<ModelDescriptor>
}
