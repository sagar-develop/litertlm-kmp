/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore.rag

import com.sagar.aicore.EmbeddingEngine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultDocumentRetrieverTest {

    private val embedder = object : EmbeddingEngine {
        var calls = 0
        override suspend fun initialize(modelPath: String) {}
        override suspend fun embed(text: String): FloatArray {
            calls++
            return FloatArray(100)
        }
    }

    /** Minimal [DocumentStore] returning fixed hits; only the retrieval path is exercised. */
    private class FakeStore(
        private val hits: List<ScoredChunk>,
        private val keyword: List<StoredChunk> = emptyList(),
    ) : DocumentStore {
        override suspend fun createDocument(projectId: Long, title: String, uri: String, localPath: String, mime: String, pageCount: Int): Long = 0
        override suspend fun getDocument(documentId: Long): StoredDocument? = null
        override suspend fun addChunks(documentId: Long, projectId: Long, chunks: List<NewChunk>) {}
        override suspend fun findSimilarChunks(queryEmbedding: FloatArray, k: Int, projectId: Long): List<ScoredChunk> = hits
        override suspend fun keywordCandidates(projectId: Long, terms: List<String>, limit: Int): List<StoredChunk> = keyword
        override suspend fun listDocuments(projectId: Long): List<StoredDocument> = emptyList()
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
        val r = DefaultDocumentRetriever(embedder, FakeStore(listOf(scored(0.9), scored(0.95))), maxDistance = 0.5)
        assertTrue(r.retrieve(1, "off topic").isEmpty)
    }

    @Test fun nearVectorHitsAreKept() = runTest {
        val r = DefaultDocumentRetriever(embedder, FakeStore(listOf(scored(0.2, id = 1), scored(0.9, id = 2))), maxDistance = 0.5)
        val ctx = r.retrieve(1, "on topic")
        assertFalse(ctx.isEmpty)
        assertEquals(1, ctx.citations.size)
    }

    @Test fun keywordArmRecoversExactMatchVectorMissed() = runTest {
        val r = DefaultDocumentRetriever(
            embedder,
            FakeStore(
                hits = listOf(scored(0.9, id = 1)),
                keyword = listOf(storedChunk(5, "the project codename is ZEPHYR NINE")),
            ),
            maxDistance = 0.5,
        )
        val ctx = r.retrieve(1, "what is zephyr nine")
        assertFalse(ctx.isEmpty)
        assertEquals(1, ctx.citations.size)
    }
}
