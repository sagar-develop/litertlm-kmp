/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.llm

/**
 * The embedder + Matryoshka dim + reranker recommended for a device tier.
 * @param embedderId catalogue id of the RAG embedder to use.
 * @param dim HNSW index dimension (must match the embedder's output).
 * @param reranker whether to run the cross-encoder reranker second stage.
 */
data class EmbedderTier(val embedderId: String, val dim: Int, val reranker: Boolean)

/**
 * Device-tiered RAG embedder recommendation, mirroring the LLM `recommendedModelId`
 * tiering. Keyed on effective RAM (after the OEM RAM-expansion cap). EmbeddingGemma
 * is one downloaded model truncated per tier (Matryoshka); entry devices stay on the
 * ungated, no-download USE-Lite. The reranker is flagship-only.
 *
 * Note EmbeddingGemma is Gemma-licensed (gated) — recommending it implies the user
 * will need a Hugging Face token, the same as the Gemma LLMs.
 */
object EmbedderRecommendation {
    const val USE_ID = "universal-sentence-encoder"
    const val GEMMA_ID = "embeddinggemma-300m-onnx"
    const val RERANKER_ID = "ms-marco-minilm-l6-onnx"

    const val DIM_USE = 100
    const val DIM_MID = 256
    const val DIM_FLAGSHIP = 512

    /** The best embedder for a device with [ramMb] effective RAM. */
    fun forDevice(ramMb: Long): EmbedderTier = when {
        // Flagship: 512-dim Matryoshka + cross-encoder reranker.
        ramMb >= 10_000 -> EmbedderTier(GEMMA_ID, DIM_FLAGSHIP, reranker = true)
        // Mid-high: 256-dim + reranker. The reranker (MiniLM-L6, ~90 MB, runs only on the
        // ~24-candidate fused pool at query time) markedly improves recall of a specific
        // figure/fact buried in a long boilerplate-heavy document, and these devices have
        // the headroom for it alongside the LLM.
        ramMb >= 8_000 -> EmbedderTier(GEMMA_ID, DIM_MID, reranker = true)
        // Mid: 256-dim, no reranker (keep memory headroom on 6 GB devices).
        ramMb >= 6_000 -> EmbedderTier(GEMMA_ID, DIM_MID, reranker = false)
        else -> EmbedderTier(USE_ID, DIM_USE, reranker = false)
    }
}
