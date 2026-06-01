/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.rag

import com.nativelm.app.data.db.DocumentRepository
import com.sagar.aicore.EmbeddingEngine

/**
 * Embeds the query, pulls the top-[k] chunks from a project's HNSW index, drops
 * the ones that aren't actually relevant, and formats the rest (fenced + capped)
 * for `LocalAiEngine.formatPrompt(retrievedContext)`. Returns
 * [RetrievedContext.EMPTY] when nothing relevant remains — the caller then answers
 * as ordinary (ungrounded) chat instead of citing unrelated text.
 */
class DefaultDocumentRetriever(
    private val embeddingEngine: EmbeddingEngine,
    private val repository: DocumentRepository,
    private val maxDistance: Double = RELEVANCE_MAX_DISTANCE,
) : DocumentRetriever {

    override suspend fun retrieve(projectId: Long, query: String, k: Int): RetrievedContext {
        if (query.isBlank() || projectId <= 0L) return RetrievedContext.EMPTY
        val queryVector = embeddingEngine.embed(query)
        // HNSW returns the k nearest chunks even when they're far from the query,
        // so an off-topic question would still pull (and cite) whatever exists.
        // Gate on cosine distance so grounding fires only on a genuine match.
        val hits = repository.findSimilarChunks(queryVector, k, projectId)
            .filter { it.score <= maxDistance }
        if (hits.isEmpty()) return RetrievedContext.EMPTY
        val titles = repository.listDocuments(projectId).associate { it.id to it.title }
        return RagContextFormatter.format(hits) { id -> titles[id] ?: "Source" }
    }

    companion object {
        /**
         * Cosine-distance cutoff (0 = identical direction … up to 2 = opposite).
         * Chunks farther than this are treated as irrelevant and dropped. A real
         * USE-Lite match sits well below this; the value is deliberately loose so
         * it only filters clearly-unrelated hits — tune against real corpora.
         */
        const val RELEVANCE_MAX_DISTANCE: Double = 0.75
    }
}
