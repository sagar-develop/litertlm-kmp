/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.llm

import com.sagar.aicore.CompanionFile
import com.sagar.aicore.ModelCatalog
import com.sagar.aicore.ModelDescriptor
import com.sagar.aicore.ModelFormat
import com.sagar.aicore.ModelRole

/**
 * NativeLM's cross-device model catalogue. Points at verified Hugging Face
 * `resolve` URLs (real `.litertlm` files in the `litert-community` org). One LLM per
 * RAM tier — entry phones to flagships — plus the RAG embedder.
 *
 * Gating: the Gemma bundles are license-gated, so [ModelDescriptor.requiresAuth]
 * is `true` (the app sends `Authorization: Bearer <hf-token>` and the user must
 * accept each repo's license on Hugging Face). The non-Gemma models (Qwen /
 * DeepSeek / Phi) are Apache-2.0 / MIT and download token-free (`requiresAuth =
 * false`). `supportsVision` drives both the engine's vision backend and the
 * Model-management input-type chips; only the multimodal Gemma 4 bundles are
 * vision-capable today.
 */
class NativeLmModelCatalog : ModelCatalog {

    private val entries: List<ModelDescriptor> = listOf(
        // ── Entry (~3–4 GB) — Qwen3 0.6B, text, ungated (Apache-2.0). The
        // friction-free default for the lowest tier: no token, no license accept. ──
        ModelDescriptor(
            id = "qwen3-0_6b-litertlm",
            url = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm?download=true",
            fileName = "Qwen3-0.6B.litertlm",
            sizeBytes = 614_236_160L,
            format = ModelFormat.LITERTLM,
            role = ModelRole.LLM_PRIMARY,
            minDeviceRamMb = 3500,
            requiresAuth = false,
            supportsVision = false,
        ),
        // ── Small (~4 GB) — Gemma 3 1B INT4, text (~557 MB). Gemma-licensed. ──
        ModelDescriptor(
            id = "gemma3-1b-it-int4-litertlm",
            url = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.litertlm?download=true",
            fileName = "gemma3-1b-it-int4.litertlm",
            sizeBytes = 584_417_280L,
            format = ModelFormat.LITERTLM,
            role = ModelRole.LLM_PRIMARY,
            minDeviceRamMb = 4000,
            requiresAuth = true,
            supportsVision = false,
        ),
        // ── Mid (~6 GB) — DeepSeek-R1-Distill-Qwen 1.5B, q8, text reasoning
        // (~1.8 GB), ungated (MIT). A non-Gemma reasoning option. ──
        ModelDescriptor(
            id = "deepseek-r1-distill-qwen-1_5b-litertlm",
            url = "https://huggingface.co/litert-community/DeepSeek-R1-Distill-Qwen-1.5B/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm?download=true",
            fileName = "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
            sizeBytes = 1_833_451_520L,
            format = ModelFormat.LITERTLM,
            role = ModelRole.LLM_PRIMARY,
            minDeviceRamMb = 6000,
            requiresAuth = false,
            supportsVision = false,
            supportsCharts = true,
        ),
        // ── Mid+ (~7 GB) — Gemma 4 E2B, multimodal (~2.6 GB). 7000 excludes 6 GB
        // phones (which report ~5.9 GB) while keeping it on genuine 8 GB+ hardware. ──
        ModelDescriptor(
            id = "gemma-4-e2b-it-litertlm",
            url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm?download=true",
            fileName = "gemma-4-E2B-it.litertlm",
            sizeBytes = 2_588_000_000L,
            format = ModelFormat.LITERTLM,
            role = ModelRole.LLM_PRIMARY,
            minDeviceRamMb = 7000,
            requiresAuth = true,
            supportsVision = true,
            supportsCharts = true,
        ),
        // ── High (~10 GB) — Gemma 4 E4B, multimodal (~3.7 GB). ──
        ModelDescriptor(
            id = "gemma-4-e4b-it-litertlm",
            url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm?download=true",
            fileName = "gemma-4-E4B-it.litertlm",
            sizeBytes = 3_659_530_240L,
            format = ModelFormat.LITERTLM,
            role = ModelRole.LLM_PRIMARY,
            minDeviceRamMb = 10000,
            requiresAuth = true,
            supportsVision = true,
            supportsCharts = true,
        ),
        // ── High (~10 GB) — Phi-4-mini, q8, text reasoning (~3.9 GB), ungated
        // (MIT). A non-Gemma high-tier option alongside E4B. ──
        ModelDescriptor(
            id = "phi-4-mini-instruct-litertlm",
            url = "https://huggingface.co/litert-community/Phi-4-mini-instruct/resolve/main/Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm?download=true",
            fileName = "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            sizeBytes = 3_910_090_752L,
            format = ModelFormat.LITERTLM,
            role = ModelRole.LLM_PRIMARY,
            minDeviceRamMb = 10000,
            requiresAuth = false,
            supportsVision = false,
            supportsCharts = true,
        ),
        // ── Flagship (~12 GB+) — Qwen3 4B, channelwise int8, text (~5.3 GB),
        // ungated (Apache-2.0). NOTE: reachable only on devices whose effective
        // RAM clears 12 GB; the OEM RAM-expansion cap (EXPANSION_CAP_MB = 9000)
        // currently blocks expansion-enabled flagships — tracked for RAM-detection
        // refinement (docs/LOW_END_PLAN.md). ──
        ModelDescriptor(
            id = "qwen3-4b-litertlm",
            url = "https://huggingface.co/litert-community/Qwen3-4B/resolve/main/qwen3_4b_channelwise_int8_float32kv.litertlm?download=true",
            fileName = "qwen3_4b_channelwise_int8_float32kv.litertlm",
            sizeBytes = 5_672_370_176L,
            format = ModelFormat.LITERTLM,
            role = ModelRole.LLM_PRIMARY,
            minDeviceRamMb = 12000,
            requiresAuth = false,
            supportsVision = false,
            supportsCharts = true,
        ),
        ModelDescriptor(
            id = "universal-sentence-encoder",
            // Public MediaPipe model — no auth required. The ungated entry-tier RAG
            // embedder and the universal fallback when EmbeddingGemma isn't downloaded.
            url = "https://storage.googleapis.com/mediapipe-models/text_embedder/universal_sentence_encoder/float32/latest/universal_sentence_encoder.tflite",
            fileName = "universal_sentence_encoder.tflite",
            sizeBytes = 6_120_274L,
            format = ModelFormat.MEDIAPIPE_TEXT_EMBEDDER,
            role = ModelRole.EMBEDDING,
            minDeviceRamMb = 0,
            requiresAuth = false,
            embeddingDim = 100,
        ),
        // ── RAG embedder upgrade — EmbeddingGemma 300M (ONNX, int8). Gemma-licensed
        // → gated (requiresAuth). One downloaded artifact serves every capable tier:
        // Matryoshka truncation picks the active dim (256 mid / 512 flagship) at
        // runtime, so this single descriptor backs the whole tier matrix; entry
        // phones stay on USE-Lite. Multi-file ONNX: the graph references its weights
        // blob (`model_quantized.onnx_data`) by name, and the tokenizer ships as a
        // companion — both land next to the graph in the model dir.
        // sizeBytes are HF-reported estimates; Content-Length drives real progress.
        ModelDescriptor(
            id = "embeddinggemma-300m-onnx",
            url = "https://huggingface.co/onnx-community/embeddinggemma-300m-ONNX/resolve/main/onnx/model_quantized.onnx?download=true",
            // Keep the original graph name: the .onnx references its weights blob by
            // the exact name "model_quantized.onnx_data" — renaming would break the
            // external-data load. Unique in the shared model dir, so no collision.
            fileName = "model_quantized.onnx",
            sizeBytes = 581_632L,
            format = ModelFormat.ONNX_EMBEDDER,
            role = ModelRole.EMBEDDING,
            minDeviceRamMb = 6000,
            requiresAuth = true,
            embeddingDim = 256,
            companions = listOf(
                CompanionFile(
                    url = "https://huggingface.co/onnx-community/embeddinggemma-300m-ONNX/resolve/main/onnx/model_quantized.onnx_data?download=true",
                    // MUST match the in-graph external-data reference exactly.
                    fileName = "model_quantized.onnx_data",
                    sizeBytes = 324_009_984L,
                ),
                CompanionFile(
                    url = "https://huggingface.co/onnx-community/embeddinggemma-300m-ONNX/resolve/main/tokenizer.json?download=true",
                    // Prefixed to avoid colliding with the reranker's tokenizer.json.
                    fileName = "embeddinggemma-tokenizer.json",
                    sizeBytes = 17_500_000L,
                ),
            ),
        ),
        // ── Reranker — ms-marco MiniLM-L6 cross-encoder (ONNX). Apache-2.0, ungated.
        // Second-stage precision rerank over first-stage candidates. Small (90 MB, runs
        // only on the ~24-candidate fused pool at query time), so it's offered on mid-high
        // and flagship tiers — matches EmbedderRecommendation's ≥8 GB reranker tier.
        // Single-file graph + WordPiece tokenizer.
        ModelDescriptor(
            id = "ms-marco-minilm-l6-onnx",
            url = "https://huggingface.co/Xenova/ms-marco-MiniLM-L-6-v2/resolve/main/onnx/model.onnx?download=true",
            fileName = "ms-marco-minilm-l6.onnx",
            sizeBytes = 90_400_000L,
            format = ModelFormat.ONNX_RERANKER,
            role = ModelRole.RERANKER,
            minDeviceRamMb = 8000,
            requiresAuth = false,
            companions = listOf(
                CompanionFile(
                    url = "https://huggingface.co/Xenova/ms-marco-MiniLM-L-6-v2/resolve/main/tokenizer.json?download=true",
                    fileName = "ms-marco-minilm-l6_tokenizer.json",
                    sizeBytes = 711_396L,
                ),
            ),
        ),
        // ── Audio (voice input) — Whisper tiny, multilingual, q5_1 quantized (~31 MB).
        // On-device speech-to-text via whisper.cpp; auto-detects the spoken language. ──
        ModelDescriptor(
            id = "whisper-tiny-q5_1",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin",
            fileName = "whisper-tiny-q5_1.bin",
            sizeBytes = 32_152_673L,
            sha256 = "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7",
            format = ModelFormat.WHISPER_GGML,
            role = ModelRole.SPEECH_TO_TEXT,
            minDeviceRamMb = 2000,
            requiresAuth = false,
        ),
    )

    override fun all(): List<ModelDescriptor> = entries
    override fun byId(id: String): ModelDescriptor? = entries.firstOrNull { it.id == id }
    override fun byRole(role: ModelRole): List<ModelDescriptor> = entries.filter { it.role == role }
}
