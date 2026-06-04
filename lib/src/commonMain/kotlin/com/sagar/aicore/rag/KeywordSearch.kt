/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore.rag

import kotlin.math.ln

/**
 * Lexical (keyword) half of hybrid retrieval. Pure Kotlin — no platform, no model —
 * so it's unit-testable and runs in microseconds.
 *
 * Why it exists: the vector arm (USE-Lite, 100-dim) is semantically fuzzy and can
 * rank an exact term match (a name, a codename, an ID) below loosely-related text,
 * especially once a project holds many chunks. BM25 over the chunks that actually
 * contain the query terms recovers those exact matches; [reciprocalRankFusion]
 * then blends the two rankings without having to reconcile their score scales.
 */
object KeywordSearch {

    /** A candidate chunk to score, identified by its row id. */
    data class Doc(val id: Long, val text: String)

    private const val K1 = 1.5      // BM25 term-frequency saturation
    private const val B = 0.75      // BM25 length normalization
    private const val MIN_TERM_LEN = 2

    // Small, conservative stop list: drop the words that would match nearly every
    // chunk (and so make terrible keyword signals) without touching content terms.
    private val STOPWORDS = setOf(
        "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "is", "are",
        "was", "were", "be", "been", "it", "its", "this", "that", "these", "those",
        "with", "as", "at", "by", "from", "what", "which", "who", "whom", "how",
        "when", "where", "why", "do", "does", "did", "can", "could", "should",
        "would", "i", "you", "he", "she", "they", "we", "me", "my", "your", "about",
        "tell", "give", "list", "name", "show",
    )

    /** Split text into lowercase alphanumeric terms; used for both query and docs. */
    fun tokenize(text: String): List<String> =
        text.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= MIN_TERM_LEN }

    /** Query terms worth searching for: tokenized, de-stopworded, de-duplicated. */
    fun queryTerms(query: String): List<String> =
        tokenize(query).filterNot { it in STOPWORDS }.distinct()

    /**
     * Rank [docs] by BM25 against [query], best-first, keeping only docs that match
     * at least one (non-stop) query term. [docs] is expected to be the set of chunks
     * that contain a query term — BM25 statistics (idf, avg length) are computed over
     * that candidate set, which is enough to order within it.
     */
    fun rank(query: String, docs: List<Doc>): List<Long> {
        val terms = queryTerms(query)
        if (terms.isEmpty() || docs.isEmpty()) return emptyList()

        val tokenized = docs.map { it.id to tokenize(it.text) }
        val n = tokenized.size
        val avgLen = tokenized.sumOf { it.second.size }.toDouble() / n
        val df = HashMap<String, Int>()
        for ((_, toks) in tokenized) {
            for (t in toks.toSet()) if (t in terms) df[t] = (df[t] ?: 0) + 1
        }

        val scored = ArrayList<Pair<Long, Double>>(n)
        for ((id, toks) in tokenized) {
            if (toks.isEmpty()) continue
            val tf = HashMap<String, Int>()
            for (t in toks) if (t in terms) tf[t] = (tf[t] ?: 0) + 1
            if (tf.isEmpty()) continue
            var score = 0.0
            val dl = toks.size.toDouble()
            for ((t, f) in tf) {
                val docFreq = df[t] ?: continue
                val idf = ln(1.0 + (n - docFreq + 0.5) / (docFreq + 0.5))
                score += idf * (f * (K1 + 1)) / (f + K1 * (1 - B + B * dl / avgLen))
            }
            if (score > 0.0) scored += id to score
        }
        return scored.sortedByDescending { it.second }.map { it.first }
    }

    /**
     * Reciprocal Rank Fusion: merge several best-first id rankings into one. Each
     * list contributes 1/(k + rank) to an id's score, so an id ranked highly by
     * either arm rises, and agreement across arms compounds. Scale-free, which is
     * why we fuse *ranks* rather than raw cosine/BM25 scores.
     */
    fun reciprocalRankFusion(rankings: List<List<Long>>, k: Int = 60): List<Long> {
        val fused = HashMap<Long, Double>()
        for (ranking in rankings) {
            ranking.forEachIndexed { index, id ->
                fused[id] = (fused[id] ?: 0.0) + 1.0 / (k + index + 1)
            }
        }
        return fused.entries.sortedByDescending { it.value }.map { it.key }
    }
}
