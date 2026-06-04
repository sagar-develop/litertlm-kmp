/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore.rag

import com.sagar.aicore.EmbeddingEngine

/**
 * Hybrid retriever: blends semantic (vector) and lexical (keyword/BM25) search.
 *
 * The vector arm embeds the query and pulls nearest chunks from the project's HNSW
 * index, gated on cosine distance so off-topic questions don't ground. The keyword
 * arm BM25-ranks the chunks that actually contain the query terms — recovering
 * exact matches (names, codenames, IDs) that USE-Lite's coarse 100-dim embeddings
 * rank poorly, especially in large projects. The two rankings are merged with
 * Reciprocal Rank Fusion, then formatted (fenced + capped) for
 * `LocalAiEngine.formatPrompt(retrievedContext)`.
 *
 * Both arms are relevance-gated by construction (vector by [maxDistance]; keyword
 * to chunks containing a query term), so [RetrievedContext.EMPTY] still means
 * "nothing relevant — answer as ordinary chat" rather than citing unrelated text.
 */
class DefaultDocumentRetriever(
    private val embeddingEngine: EmbeddingEngine,
    private val store: DocumentStore,
    private val maxDistance: Double = RELEVANCE_MAX_DISTANCE,
) : DocumentRetriever {

    override suspend fun retrieve(projectId: Long, query: String, k: Int): RetrievedContext {
        if (query.isBlank() || projectId <= 0L) return RetrievedContext.EMPTY

        // ── Vector arm: nearest neighbors, gated to genuine semantic matches. ──
        val queryVector = embeddingEngine.embed(query)
        val vectorHits = store.findSimilarChunks(queryVector, VECTOR_POOL, projectId)
            .filter { it.score <= maxDistance }
        val vectorRanking = vectorHits.map { it.chunk.id }

        // ── Keyword arm: BM25 over chunks that contain a query term. ──
        val terms = KeywordSearch.queryTerms(query)
        val keywordCandidates = if (terms.isEmpty()) {
            emptyList()
        } else {
            store.keywordCandidates(projectId, terms, KEYWORD_POOL)
        }
        val keywordRanking = KeywordSearch.rank(
            query,
            keywordCandidates.map { KeywordSearch.Doc(it.id, it.text) },
        )

        if (vectorRanking.isEmpty() && keywordRanking.isEmpty()) return RetrievedContext.EMPTY

        // ── Fuse and take the top k. ──
        val fusedIds = KeywordSearch
            .reciprocalRankFusion(listOf(vectorRanking, keywordRanking))
            .take(k)

        val byId: Map<Long, StoredChunk> =
            (vectorHits.map { it.chunk } + keywordCandidates).associateBy { it.id }
        val ordered = fusedIds.mapNotNull { id -> byId[id] }.map { ScoredChunk(it, 0.0) }
        if (ordered.isEmpty()) return RetrievedContext.EMPTY

        val titles = store.listDocuments(projectId).associate { it.id to it.title }
        return RagContextFormatter.format(ordered) { id -> titles[id] ?: "Source" }
    }

    companion object {
        /**
         * Cosine-distance cutoff (0 = identical direction … up to 2 = opposite).
         * Vector hits farther than this are dropped. A real USE-Lite match sits well
         * below this; the value is deliberately loose so it only filters clearly
         * unrelated hits — tune against real corpora.
         */
        const val RELEVANCE_MAX_DISTANCE: Double = 0.75

        /** Candidate-pool sizes per arm before fusion (final result is top-k). */
        private const val VECTOR_POOL = 30
        private const val KEYWORD_POOL = 120
    }
}
