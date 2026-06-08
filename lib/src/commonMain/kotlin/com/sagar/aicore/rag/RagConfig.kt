/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore.rag

/**
 * Tunables for the RAG pipeline, so a consumer can adjust retrieval/ingestion
 * behaviour without forking the engine. The defaults are the values NativeLM
 * shipped with (USE-Lite, 100-dim); change them per corpus/model.
 *
 * @param chunkSize characters per chunk (see [TextChunker]).
 * @param chunkOverlap characters shared between consecutive chunks.
 * @param relevanceMaxDistance cosine-distance gate for the vector arm — hits
 *   farther than this are dropped (0 = identical … 2 = opposite).
 * @param vectorPoolSize nearest-neighbour candidates pulled before fusion.
 * @param keywordPoolSize lexical candidates pulled before fusion.
 * @param maxContextChars cap on the fenced grounding block fed to the model.
 * @param maxChunksPerDocument cap on how many chunks from a single source may
 *   survive into the final fused top-k. Without it, one large document can fill
 *   every slot and crowd out the genuinely relevant source — the "answered from
 *   the wrong PDF" failure. 0 disables the cap.
 */
data class RagConfig(
    val chunkSize: Int = 500,
    val chunkOverlap: Int = 50,
    val relevanceMaxDistance: Double = 0.75,
    val vectorPoolSize: Int = 40,
    val keywordPoolSize: Int = 160,
    // Size of the grounding block fed to the model per turn. The app flushes prior turns'
    // grounding from the KV cache each grounded turn (re-prefilling only the bounded
    // transcript), so this no longer ACCUMULATES across turns — a single block of this
    // size sits comfortably in the on-device context window. Larger than the old 4000 so
    // more chunks (better recall of a specific figure) survive into the prompt.
    val maxContextChars: Int = 6000,
    val maxChunksPerDocument: Int = 3,
    /**
     * When a [Reranker] is attached, how many top fused candidates to re-score with
     * the cross-encoder before the per-document cap and final top-k. Bounds rerank
     * latency; ignored when no reranker is present.
     */
    val rerankPoolSize: Int = 24,
    /**
     * Document-level relevance margin (cosine distance). After the vector arm runs,
     * only documents whose BEST chunk distance is within this margin of the overall
     * best document survive; the keyword arm is then restricted to those documents.
     * This stops BM25 — which matches shared terms like "insurance"/"premium" — from
     * grounding on a lexically-similar but semantically wrong document (e.g. a health
     * policy answering a car-insurance question). Tuned for EmbeddingGemma's cosine
     * spread; set very high (≥2.0) to disable for coarse embedders like USE-Lite.
     */
    val docRelevanceMargin: Double = 0.08,
)
