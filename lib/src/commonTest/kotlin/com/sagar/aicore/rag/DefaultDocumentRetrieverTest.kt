/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore.rag

import com.sagar.aicore.EmbeddingEngine
import com.sagar.aicore.EmbeddingTask
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultDocumentRetrieverTest {

    private val embedder = object : EmbeddingEngine {
        var calls = 0
        override val dimensions: Int = 100
        override suspend fun initialize(modelPath: String) {}
        override suspend fun embed(text: String, task: EmbeddingTask, title: String?): FloatArray {
            calls++
            return FloatArray(100)
        }
    }

    /** Minimal [DocumentStore] returning fixed hits; only the retrieval path is exercised. */
    private class FakeStore(
        private val hits: List<ScoredChunk>,
        private val keyword: List<StoredChunk> = emptyList(),
        /** documentId → title, for the title-match leg of the relevance gate. */
        private val titles: Map<Long, String> = emptyMap(),
    ) : DocumentStore {
        override suspend fun createDocument(projectId: Long, title: String, uri: String, localPath: String, mime: String, pageCount: Int): Long = 0
        override suspend fun getDocument(documentId: Long): StoredDocument? = null
        override suspend fun addChunks(documentId: Long, projectId: Long, chunks: List<NewChunk>) {}
        override suspend fun findSimilarChunks(queryEmbedding: FloatArray, k: Int, projectId: Long): List<ScoredChunk> = hits
        override suspend fun keywordCandidates(projectId: Long, terms: List<String>, limit: Int): List<StoredChunk> = keyword
        override suspend fun listDocuments(projectId: Long): List<StoredDocument> =
            titles.map { (id, title) -> StoredDocument(id, projectId = 1, title = title, sourceUri = "", localPath = "", mimeType = "", pageCount = 0, chunkCount = 0, createdAt = 0) }
        override suspend fun chunksForProject(projectId: Long, documentId: Long): List<StoredChunk> = emptyList()
        override suspend fun deleteDocument(documentId: Long) {}
        override suspend fun deleteDocumentsOfProject(projectId: Long) {}
    }

    private fun storedChunk(id: Long, text: String) =
        StoredChunk(id = id, documentId = 1, projectId = 1, text = text, pageNumber = 0, chunkIndex = 0)

    private fun scored(score: Double, id: Long = 0, text: String = "fact") =
        ScoredChunk(storedChunk(id, text), score)

    @Test fun blankQueryReturnsEmptyWithoutEmbedding() = runTest {
        val r = DefaultDocumentRetriever(embedder, FakeStore(listOf(scored(0.1))))
        assertTrue(r.retrieve(1, "   ").isEmpty)
        assertEquals(0, embedder.calls)
    }

    @Test fun nonPositiveProjectReturnsEmpty() = runTest {
        val r = DefaultDocumentRetriever(embedder, FakeStore(listOf(scored(0.1))))
        assertTrue(r.retrieve(0, "hello").isEmpty)
    }

    @Test fun farHitsWithNoKeywordMatchAreEmpty() = runTest {
        val r = DefaultDocumentRetriever(embedder, FakeStore(listOf(scored(0.9), scored(0.95))), config = RagConfig(relevanceMaxDistance = 0.5))
        assertTrue(r.retrieve(1, "off topic").isEmpty)
    }

    @Test fun nearVectorHitsAreKept() = runTest {
        val r = DefaultDocumentRetriever(embedder, FakeStore(listOf(scored(0.2, id = 1), scored(0.9, id = 2))), config = RagConfig(relevanceMaxDistance = 0.5))
        val ctx = r.retrieve(1, "on topic")
        assertFalse(ctx.isEmpty)
        assertEquals(1, ctx.citations.size)
    }

    @Test fun perDocumentCapKeepsTopKDiverse() = runTest {
        // Four near hits from doc 1 plus one from doc 2. Without a cap the top-3
        // would be all doc 1; with maxChunksPerDocument=2 the third slot must go
        // to doc 2 so the relevant second source isn't crowded out.
        val hits = listOf(
            ScoredChunk(StoredChunk(id = 1, documentId = 1, projectId = 1, text = "alpha one", pageNumber = 0, chunkIndex = 0), 0.1),
            ScoredChunk(StoredChunk(id = 2, documentId = 1, projectId = 1, text = "alpha two", pageNumber = 0, chunkIndex = 1), 0.1),
            ScoredChunk(StoredChunk(id = 3, documentId = 1, projectId = 1, text = "alpha three", pageNumber = 0, chunkIndex = 2), 0.1),
            ScoredChunk(StoredChunk(id = 4, documentId = 1, projectId = 1, text = "alpha four", pageNumber = 0, chunkIndex = 3), 0.1),
            ScoredChunk(StoredChunk(id = 5, documentId = 2, projectId = 1, text = "beta one", pageNumber = 0, chunkIndex = 0), 0.1),
        )
        val r = DefaultDocumentRetriever(
            embedder,
            FakeStore(hits),
            config = RagConfig(relevanceMaxDistance = 0.5, maxChunksPerDocument = 2),
        )
        val ctx = r.retrieve(1, "topic", k = 3)
        assertEquals(3, ctx.citations.size)
        assertTrue(ctx.citations.any { it.documentId == 2L }, "second source must survive the per-document cap")
    }

    @Test fun rerankerReordersTopCandidates() = runTest {
        // Two near vector hits from different docs; the reranker prefers doc 2.
        val hits = listOf(
            ScoredChunk(StoredChunk(id = 1, documentId = 1, projectId = 1, text = "alpha passage", pageNumber = 0, chunkIndex = 0), 0.1),
            ScoredChunk(StoredChunk(id = 2, documentId = 2, projectId = 1, text = "beta passage", pageNumber = 0, chunkIndex = 0), 0.2),
        )
        val reranker = object : Reranker {
            override suspend fun initialize(modelPath: String) {}
            override suspend fun scores(query: String, passages: List<String>): FloatArray =
                FloatArray(passages.size) { if (passages[it].contains("beta")) 9f else 1f }
        }
        // docRelevanceMargin disabled (2.0) so both docs survive the doc-gate and the
        // test isolates reranker reordering.
        val r = DefaultDocumentRetriever(embedder, FakeStore(hits), RagConfig(relevanceMaxDistance = 0.5, docRelevanceMargin = 2.0), reranker)
        val ctx = r.retrieve(1, "query", k = 2)
        assertEquals(2L, ctx.citations.first().documentId, "reranker should float doc 2 to the top")
    }

    @Test fun docGateExcludesLexicalOnlyWrongDoc() = runTest {
        // doc 10 is the semantic match (near vector hit); doc 20 only matches lexically
        // ("insurance"/"premium") and is far in vector space. The document-level gate
        // must drop doc 20 so the answer doesn't ground on the wrong source.
        val carChunk = StoredChunk(id = 1, documentId = 10, projectId = 1, text = "car insurance premium is 5000", pageNumber = 0, chunkIndex = 0)
        val healthChunk = StoredChunk(id = 2, documentId = 20, projectId = 1, text = "health insurance premium is 8504", pageNumber = 0, chunkIndex = 0)
        val r = DefaultDocumentRetriever(
            embedder,
            FakeStore(
                hits = listOf(ScoredChunk(carChunk, 0.30), ScoredChunk(healthChunk, 0.70)),
                keyword = listOf(healthChunk),
            ),
            config = RagConfig(relevanceMaxDistance = 0.75, docRelevanceMargin = 0.08),
        )
        val ctx = r.retrieve(1, "what is my car insurance premium")
        assertFalse(ctx.isEmpty)
        assertTrue(ctx.citations.all { it.documentId == 10L }, "lexically-similar wrong doc (20) must be gated out")
    }

    @Test fun docGateExcludesWrongDocWithSmallCluster() = runTest {
        // Reproduces the real on-device "...premium amount" leak: the car doc clearly best
        // matches (0.463), while the health doc has a SMALL CLUSTER of near-but-worse hits
        // (0.528/0.535, within the 0.08 margin) plus lexical keyword matches. A count-based
        // gate would admit the cluster; the dominance gate keeps only the car doc because
        // the health doc's best (0.528) is not tied with the overall best (≤0.483).
        val car1 = StoredChunk(id = 1, documentId = 10, projectId = 1, text = "car premium 35423", pageNumber = 6, chunkIndex = 0)
        val car2 = StoredChunk(id = 2, documentId = 10, projectId = 1, text = "car policy details", pageNumber = 1, chunkIndex = 1)
        val car3 = StoredChunk(id = 3, documentId = 10, projectId = 1, text = "car gst 6376", pageNumber = 8, chunkIndex = 2)
        val health1 = StoredChunk(id = 4, documentId = 20, projectId = 1, text = "health insurance premium amount", pageNumber = 10, chunkIndex = 0)
        val health2 = StoredChunk(id = 5, documentId = 20, projectId = 1, text = "health insurance premium payable", pageNumber = 12, chunkIndex = 1)
        val r = DefaultDocumentRetriever(
            embedder,
            FakeStore(
                hits = listOf(
                    ScoredChunk(car1, 0.463),
                    ScoredChunk(car2, 0.503),
                    ScoredChunk(car3, 0.505),
                    ScoredChunk(health1, 0.528), // cluster of TWO near (but worse) hits …
                    ScoredChunk(health2, 0.535), // … which a count-based gate would admit
                ),
                keyword = listOf(health1, health2), // BM25 would otherwise float these to the top
            ),
            config = RagConfig(relevanceMaxDistance = 0.62, docRelevanceMargin = 0.08),
        )
        val ctx = r.retrieve(1, "what is the car insurance premium amount")
        assertFalse(ctx.isEmpty)
        assertTrue(ctx.citations.all { it.documentId == 10L }, "wrong doc with a near-margin cluster must still be gated out")
    }

    @Test fun titleMatchAdmitsNamedDocumentVectorRankedLower() = runTest {
        // "who is the insurer of my CAR policy": the health policy uses "insurer" more
        // formally so the vector arm ranks it first; the car policy is a lower vector hit.
        // The query NAMES the car document by its title ("CarPolicy"), so the title-match
        // leg of the gate must admit it (generic words "insurer"/"policy" are ignored; the
        // distinctive subject "car" is what matches the title).
        val carChunk = StoredChunk(id = 1, documentId = 10, projectId = 1, text = "TATA AIG is your insurer for this car policy", pageNumber = 0, chunkIndex = 0)
        val healthChunk = StoredChunk(id = 2, documentId = 20, projectId = 1, text = "ICICI Lombard the Company is the insurer", pageNumber = 0, chunkIndex = 0)
        val r = DefaultDocumentRetriever(
            embedder,
            FakeStore(
                hits = listOf(ScoredChunk(healthChunk, 0.40), ScoredChunk(carChunk, 0.52)),
                keyword = listOf(carChunk),
                titles = mapOf(10L to "CarPolicy_178067849", 20L to "ICICI Health Shield 360"),
            ),
            config = RagConfig(relevanceMaxDistance = 0.62, docRelevanceMargin = 0.08),
        )
        val ctx = r.retrieve(1, "who is the insurer of my car policy")
        assertFalse(ctx.isEmpty)
        assertTrue(ctx.citations.all { it.documentId == 10L }, "title-named car doc must override the vector-primary health doc")
    }

    @Test fun titleMatchIgnoresGenericPolicyWordToAvoidPollution() = runTest {
        // A LIFE query must not drag in the car document just because both titles contain
        // the generic word "policy". With "policy" in the stop list and "car" absent from
        // the query, the car doc (a weak vector hit) stays gated out.
        val lifeChunk = StoredChunk(id = 1, documentId = 30, projectId = 1, text = "the nominee under this life policy is the father", pageNumber = 0, chunkIndex = 0)
        val carChunk = StoredChunk(id = 2, documentId = 10, projectId = 1, text = "this car policy nominee field", pageNumber = 0, chunkIndex = 0)
        val r = DefaultDocumentRetriever(
            embedder,
            FakeStore(
                hits = listOf(ScoredChunk(lifeChunk, 0.35), ScoredChunk(carChunk, 0.58)),
                keyword = listOf(carChunk),
                titles = mapOf(30L to "Welcome Kit Life Cover", 10L to "CarPolicy_178067849"),
            ),
            config = RagConfig(relevanceMaxDistance = 0.62, docRelevanceMargin = 0.08),
        )
        val ctx = r.retrieve(1, "who is the nominee on my policy")
        assertFalse(ctx.isEmpty)
        assertTrue(ctx.citations.all { it.documentId == 30L }, "generic 'policy' must not admit the car doc")
    }

    @Test fun keywordArmRecoversExactMatchVectorMissed() = runTest {
        val r = DefaultDocumentRetriever(
            embedder,
            FakeStore(
                hits = listOf(scored(0.9, id = 1)),
                keyword = listOf(storedChunk(5, "the project codename is ZEPHYR NINE")),
            ),
            config = RagConfig(relevanceMaxDistance = 0.5),
        )
        val ctx = r.retrieve(1, "what is zephyr nine")
        assertFalse(ctx.isEmpty)
        assertEquals(1, ctx.citations.size)
    }
}
