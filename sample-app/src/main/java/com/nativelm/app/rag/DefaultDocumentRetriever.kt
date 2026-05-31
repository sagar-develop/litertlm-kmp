/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.rag

import com.nativelm.app.data.db.DocumentRepository
import com.sagar.aicore.EmbeddingEngine

/**
 * Embeds the query, pulls the top-[k] chunks from the HNSW index, and formats them
 * (fenced + capped) for `LocalAiEngine.formatPrompt(retrievedContext = ...)`.
 * Returns [RetrievedContext.EMPTY] when nothing relevant is found.
 */
class DefaultDocumentRetriever(
    private val embeddingEngine: EmbeddingEngine,
    private val repository: DocumentRepository,
) : DocumentRetriever {

    override suspend fun retrieve(
        query: String,
        k: Int,
        documentIds: List<Long>?,
    ): RetrievedContext {
        if (query.isBlank()) return RetrievedContext.EMPTY
        val queryVector = embeddingEngine.embed(query)
        val hits = repository.findSimilarChunks(queryVector, k, documentIds)
        if (hits.isEmpty()) return RetrievedContext.EMPTY
        val titles = repository.listDocuments().associate { it.id to it.title }
        return RagContextFormatter.format(hits) { id -> titles[id] ?: "Document" }
    }
}
