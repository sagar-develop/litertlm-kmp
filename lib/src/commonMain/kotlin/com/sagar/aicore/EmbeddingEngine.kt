/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore

/**
 * What an embedding is being computed *for*. Instruction-tuned embedders
 * (EmbeddingGemma) are asymmetric: a search query and an indexed document must
 * be embedded with different task prefixes or recall collapses. Symmetric
 * embedders (USE-Lite) ignore this and embed both the same way.
 */
enum class EmbeddingTask { QUERY, DOCUMENT }

/**
 * Interface for generating vector embeddings from text.
 *
 * Task-aware (see [EmbeddingTask]) so instruction-tuned models can apply the
 * correct query/document prompt; symmetric models simply ignore [task]/[title].
 */
interface EmbeddingEngine {
    /**
     * Output dimension of the vectors this engine produces. Must match the
     * HNSW index the vectors are stored in. USE-Lite = 100; EmbeddingGemma is
     * Matryoshka-truncated to a tier-selected dim (128 / 256 / 512).
     */
    val dimensions: Int

    /**
     * Initializes the embedding engine with the model.
     */
    suspend fun initialize(modelPath: String)

    /**
     * Converts a string into a float array representing its vector embedding.
     *
     * @param task whether [text] is a search query or a document being indexed.
     *   Symmetric embedders ignore this.
     * @param title optional document title, used only for [EmbeddingTask.DOCUMENT]
     *   on prompt-instructed models; ignored otherwise.
     */
    suspend fun embed(text: String, task: EmbeddingTask, title: String? = null): FloatArray
}
