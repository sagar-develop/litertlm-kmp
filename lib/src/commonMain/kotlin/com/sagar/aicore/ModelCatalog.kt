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
     * Extra files that must be downloaded alongside the primary [url]/[fileName]
     * for the model to load — e.g. an ONNX external-data weights blob
     * (`model.onnx_data`) or a `tokenizer.json`. Each lands next to the primary
     * file in the model dir. Empty for single-file models. See [CompanionFile].
     */
    val companions: List<CompanionFile> = emptyList(),
    /**
     * For [ModelRole.EMBEDDING] models: the vector dimension this descriptor
     * produces (after any Matryoshka truncation). Must match the HNSW index the
     * vectors are written to. 0 for non-embedding roles. USE-Lite = 100;
     * EmbeddingGemma tiers = 128 / 256 / 512.
     */
    val embeddingDim: Int = 0,
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
    /**
     * Whether this model reliably follows the optional chart-emitting instruction
     * (see `chart.ChartInstruction`). Tiny models (≲1B) tend to parrot the in-prompt
     * chart examples into unrelated answers instead of honoring the "only when it
     * genuinely helps" guardrail, so consumers should append the chart instruction
     * to the system prompt ONLY when this is true. Defaults to false (off).
     */
    val supportsCharts: Boolean = false,
)

enum class ModelFormat {
    /** LiteRT-LM bundle (text decoder + embedding params + audio mel). */
    LITERTLM,
    /** MediaPipe Tasks Text Embedder `.tflite` (universal sentence encoder). */
    MEDIAPIPE_TEXT_EMBEDDER,
    /** Whisper GGML/GGUF weights for on-device speech-to-text (whisper.cpp). */
    WHISPER_GGML,
    /**
     * ONNX text-embedding transformer (EmbeddingGemma) run via ONNX Runtime.
     * Ships as a graph file plus an `model.onnx_data` external-weights companion
     * and a `tokenizer.json` companion. Pooled + Matryoshka-truncated + L2-normed
     * by [EmbeddingEngine]; deliberately NOT the MediaPipe path (no Google deps,
     * protects the zero-telemetry stance).
     */
    ONNX_EMBEDDER,
    /**
     * ONNX cross-encoder reranker (query+passage → relevance score) run via ONNX
     * Runtime. Second-stage precision reranking over first-stage candidates;
     * gated to capable device tiers.
     */
    ONNX_RERANKER,
}

enum class ModelRole {
    /** Primary on-device LLM. Exactly one active descriptor per RAM tier has this role. */
    LLM_PRIMARY,
    /** Embedding model for RAG retrieval. */
    EMBEDDING,
    /**
     * Speech-to-text (voice input). The "Audio" model category — a Whisper model used to
     * transcribe dictated audio entirely on-device. Surfaced as its own group in model UIs
     * and downloaded on first use of voice input.
     */
    SPEECH_TO_TEXT,
    /**
     * Cross-encoder reranker. Optional second retrieval stage that re-scores the
     * top first-stage candidates for precision. Recommended only on flagship-tier
     * devices; absence simply skips reranking.
     */
    RERANKER,
}

/**
 * A secondary file a [ModelDescriptor] needs alongside its primary download.
 * Downloaded to the same model dir under [fileName]; verified against [sha256]
 * when present. Used for ONNX external-data weights and tokenizers.
 */
data class CompanionFile(
    val url: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String? = null,
)

interface ModelCatalog {
    fun all(): List<ModelDescriptor>
    fun byId(id: String): ModelDescriptor?
    fun byRole(role: ModelRole): List<ModelDescriptor>
}
