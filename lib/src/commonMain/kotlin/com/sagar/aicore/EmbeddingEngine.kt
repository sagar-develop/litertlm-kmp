/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore

/**
 * Whether a piece of text is being embedded as a **search query** or as a stored
 * **document** chunk. Prompt-instructed embedders (e.g. EmbeddingGemma) emit
 * different vectors for each role and *require* the distinction for good
 * retrieval; symmetric embedders (e.g. USE-Lite) ignore it.
 */
enum class EmbeddingTask { QUERY, DOCUMENT }

/**
 * Interface for generating vector embeddings from text.
 *
 * [embed] is task-aware: callers state whether the text is a query or a document
 * so prompt-instructed models can apply the correct instruction prefix. Symmetric
 * models ignore [task]/[title]. [dimensions] is the length of the returned vector
 * and must match the vector store's index dimension for the active embedder.
 */
interface EmbeddingEngine {
    /** Length of the vectors this engine returns (e.g. USE-Lite = 100, EmbeddingGemma = 256). */
    val dimensions: Int

    /** Initializes the embedding engine with the model. */
    suspend fun initialize(modelPath: String)

    /**
     * Converts [text] into a [dimensions]-length embedding. [task] selects the
     * query/document instruction on prompt-instructed models; [title] is an optional
     * document title used only for [EmbeddingTask.DOCUMENT] on those models. Both are
     * ignored by symmetric models.
     */
    suspend fun embed(
        text: String,
        task: EmbeddingTask = EmbeddingTask.DOCUMENT,
        title: String? = null,
    ): FloatArray
}
