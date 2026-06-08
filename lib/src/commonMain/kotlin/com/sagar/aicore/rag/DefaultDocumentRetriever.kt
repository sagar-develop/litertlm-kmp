/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore.rag

import com.sagar.aicore.EmbeddingEngine
import com.sagar.aicore.EmbeddingTask

/** A [RagConfig.docRelevanceMargin] at or above this disables the document gate. */
private const val GATE_DISABLED = 1.0

/**
 * Generic document/insurance words that appear in many source titles ("CarPolicy",
 * "policy_copy", …). They are excluded from the title-match leg of the relevance gate so
 * it keys on a document's distinctive SUBJECT word ("car", "health") rather than over-
 * admitting every source whose title happens to contain "policy".
 */
private val TITLE_MATCH_STOPWORDS = setOf(
    "policy", "policies", "insurance", "insurer", "premium", "cover", "coverage",
    "plan", "document", "copy", "kit", "report", "statement", "certificate", "welcome",
)

/**
 * Fraction of [RagConfig.docRelevanceMargin] within which a document with only ONE near
 * hit still counts as relevant — i.e. it is essentially TIED with the overall best match.
 * A lone chunk that is merely within the (looser) margin but clearly worse is treated as
 * a coincidental lexical/semantic overlap and rejected.
 */
private const val TIGHT_TIE_FRACTION = 0.25

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
    private val config: RagConfig = RagConfig(),
    /** Optional cross-encoder second stage (flagship tier); null = first-stage only. */
    private val reranker: Reranker? = null,
) : DocumentRetriever {

    override suspend fun retrieve(projectId: Long, query: String, k: Int): RetrievedContext {
        if (query.isBlank() || projectId <= 0L) return RetrievedContext.EMPTY

        // ── Vector arm: nearest neighbors, gated to genuine semantic matches. ──
        val queryVector = embeddingEngine.embed(query, EmbeddingTask.QUERY)
        val rawHits = store.findSimilarChunks(queryVector, config.vectorPoolSize, projectId)
        val vectorHits = rawHits.filter { it.score <= config.relevanceMaxDistance }

        // Query terms and document titles are needed both by the relevance gate (title
        // match) and the keyword arm below; compute them once up front.
        val terms = KeywordSearch.queryTerms(query)
        val titles = store.listDocuments(projectId).associate { it.id to it.title }

        // ── Document-level relevance gate (dominance + title match). ──
        // Ground on the document that clearly best matches the query, and admit a second
        // document ONLY if its best chunk is essentially TIED with the overall best. A
        // wrong but lexically-similar document (e.g. a health policy sharing the words
        // "insurance"/"premium"/"amount") can still produce a small cluster of near-margin
        // chunks, so a count-based "cluster = relevant" test leaks it back in; requiring a
        // near-tie at the very top does not. The keyword arm is then restricted to the
        // surviving documents so BM25's shared-term matches can't reintroduce the rest.
        val relevantDocs: Set<Long> = run {
            if (vectorHits.isEmpty()) return@run emptySet()
            val allDocs = vectorHits.mapTo(HashSet()) { it.chunk.documentId }
            // Coarse embedders (USE-Lite) and tests disable the gate via a large margin.
            if (config.docRelevanceMargin >= GATE_DISABLED) return@run allDocs

            // Title match (OVERRIDE): if a DISTINCTIVE query term names a document by its
            // title (e.g. "car" → a "CarPolicy" source), ground on THAT document — the user
            // pointed at it explicitly, which beats vector ranking. This fixes the case where
            // a different policy that merely uses "insurer" more formally out-ranks the car
            // policy for "who is the insurer of my CAR policy". Matched over ALL project docs
            // (not just vector hits) so a doc whose relevant chunk is lexical-only is still
            // admitted; the keyword arm then supplies its chunks. Generic document words
            // ("policy"/"insurance"/…) are skipped so this keys on the subject word.
            val titleTerms = terms.filter { it.length >= 3 && it !in TITLE_MATCH_STOPWORDS }
            val titleMatched = titles.filterValues { title ->
                val t = title.lowercase(); titleTerms.any { t.contains(it) }
            }.keys
            if (titleMatched.isNotEmpty()) return@run titleMatched.toSet()

            // Otherwise: dominance — the best-matching document, plus any document whose best
            // chunk is essentially TIED with the overall best. A wrong but lexically-similar
            // document (sharing "insurance"/"premium") can still cluster near the margin, so
            // a count-based test leaks it back in; requiring a near-tie at the top does not.
            val best = vectorHits.minOf { it.score }
            val primaryDoc = vectorHits.minByOrNull { it.score }!!.chunk.documentId
            val tieThreshold = best + config.docRelevanceMargin * TIGHT_TIE_FRACTION
            val bestByDoc = HashMap<Long, Double>()
            for (h in vectorHits) {
                val cur = bestByDoc[h.chunk.documentId]
                if (cur == null || h.score < cur) bestByDoc[h.chunk.documentId] = h.score
            }
            bestByDoc.filter { (doc, docBest) -> doc == primaryDoc || docBest <= tieThreshold }.keys
        }
        val gatedVectorHits =
            if (relevantDocs.isEmpty()) vectorHits else vectorHits.filter { it.chunk.documentId in relevantDocs }
        val vectorRanking = gatedVectorHits.map { it.chunk.id }

        // ── Keyword arm: BM25 over chunks containing a query term, restricted to the
        // vector-confirmed documents (falls back to all docs only when the vector arm
        // found nothing semantically relevant). ──
        val keywordCandidatesAll = if (terms.isEmpty()) {
            emptyList()
        } else {
            store.keywordCandidates(projectId, terms, config.keywordPoolSize)
        }
        val keywordCandidates =
            if (relevantDocs.isEmpty()) keywordCandidatesAll
            else keywordCandidatesAll.filter { it.documentId in relevantDocs }
        val keywordRanking = KeywordSearch.rank(
            query,
            keywordCandidates.map { KeywordSearch.Doc(it.id, it.text) },
        )

        if (vectorRanking.isEmpty() && keywordRanking.isEmpty()) return RetrievedContext.EMPTY

        // ── Fuse, cap per-document, then take the top k. ──
        val fusedIds = KeywordSearch
            .reciprocalRankFusion(listOf(vectorRanking, keywordRanking))

        val byId: Map<Long, StoredChunk> =
            (gatedVectorHits.map { it.chunk } + keywordCandidates).associateBy { it.id }
        val fusedChunks = fusedIds.mapNotNull { id -> byId[id] }

        // Optional cross-encoder rerank of the top fused candidates (flagship tier).
        // Re-orders by query↔passage relevance before the diversity cap and top-k.
        val rr = reranker
        val ranked = if (rr != null && fusedChunks.isNotEmpty()) {
            val pool = fusedChunks.take(config.rerankPoolSize)
            val scores = runCatching { rr.scores(query, pool.map { it.text }) }.getOrNull()
            if (scores != null && scores.size == pool.size) {
                pool.indices.sortedByDescending { scores[it] }.map { pool[it] }
            } else {
                fusedChunks // reranker failed — fall back to fusion order
            }
        } else {
            fusedChunks
        }

        // Limit chunks per source so a single large document can't fill every slot
        // and crowd out the genuinely relevant source — but NOT when exactly one
        // document is the clear semantic winner (then it SHOULD fill the answer).
        val applyCap = config.maxChunksPerDocument > 0 && relevantDocs.size != 1
        val capped = if (applyCap) {
            val perDoc = HashMap<Long, Int>()
            ranked.filter { c ->
                val seen = perDoc.getOrElse(c.documentId) { 0 }
                if (seen >= config.maxChunksPerDocument) {
                    false
                } else {
                    perDoc[c.documentId] = seen + 1
                    true
                }
            }
        } else {
            ranked
        }
        val ordered = capped.take(k).map { ScoredChunk(it, 0.0) }
        if (ordered.isEmpty()) return RetrievedContext.EMPTY

        return RagContextFormatter.format(ordered, config.maxContextChars) { id -> titles[id] ?: "Source" }
    }
}
