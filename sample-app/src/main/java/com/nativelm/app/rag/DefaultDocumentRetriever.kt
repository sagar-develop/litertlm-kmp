/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.rag

import com.nativelm.app.data.db.Chunk
import com.nativelm.app.data.db.DocumentRepository
import com.nativelm.app.data.db.ScoredChunk
import com.sagar.aicore.EmbeddingEngine
import com.sagar.aicore.EmbeddingTask

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
    private val repository: DocumentRepository,
    /** Override the cosine-distance gate; when null it's chosen per active embedder. */
    private val maxDistance: Double? = null,
) : DocumentRetriever {

    override suspend fun retrieve(projectId: Long, query: String, k: Int): RetrievedContext {
        if (query.isBlank() || projectId <= 0L) return RetrievedContext.EMPTY

        val dim = embeddingEngine.dimensions
        val gate = maxDistance ?: gateFor(dim)

        // ── Vector arm: nearest neighbors, gated to genuine semantic matches. ──
        // QUERY task: prompt-instructed embedders (EmbeddingGemma) need the query role.
        val queryVector = embeddingEngine.embed(query, EmbeddingTask.QUERY)
        val vectorHits = repository.findSimilarChunks(queryVector, VECTOR_POOL, projectId)
            .filter { it.score <= gate }
        val vectorRanking = vectorHits.map { it.chunk.id }

        // ── Keyword arm: BM25 over chunks that contain a query term. ──
        val terms = KeywordSearch.queryTerms(query)
        val keywordCandidates = if (terms.isEmpty()) {
            emptyList()
        } else {
            repository.keywordCandidates(projectId, terms, KEYWORD_POOL, dim)
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

        val byId: Map<Long, Chunk> =
            (vectorHits.map { it.chunk } + keywordCandidates).associateBy { it.id }
        val ordered = fusedIds.mapNotNull { id -> byId[id] }.map { ScoredChunk(it, 0.0) }
        if (ordered.isEmpty()) return RetrievedContext.EMPTY

        val titles = repository.listDocuments(projectId).associate { it.id to it.title }
        return RagContextFormatter.format(ordered) { id -> titles[id] ?: "Source" }
    }

    /**
     * Cosine-distance gate per embedder (0 = identical direction … up to 2 = opposite).
     * USE-Lite (100-dim) and EmbeddingGemma (256-dim) have different distance
     * distributions, so the cutoff differs. Both are deliberately loose — they only
     * drop clearly-unrelated hits. **Tune against real corpora on-device.**
     */
    private fun gateFor(dim: Int): Double =
        if (dim == 256) RELEVANCE_MAX_DISTANCE_GEMMA else RELEVANCE_MAX_DISTANCE_USE

    companion object {
        const val RELEVANCE_MAX_DISTANCE_USE: Double = 0.75

        /** Provisional — EmbeddingGemma vectors are L2-normalized; retune on-device. */
        const val RELEVANCE_MAX_DISTANCE_GEMMA: Double = 0.55

        /** Candidate-pool sizes per arm before fusion (final result is top-k). */
        private const val VECTOR_POOL = 30
        private const val KEYWORD_POOL = 120
    }
}
