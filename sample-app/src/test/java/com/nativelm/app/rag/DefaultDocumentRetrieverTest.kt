/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.rag

import com.nativelm.app.data.db.Chunk
import com.nativelm.app.data.db.ChunkInput
import com.nativelm.app.data.db.DocumentEntity
import com.nativelm.app.data.db.DocumentRepository
import com.nativelm.app.data.db.ScoredChunk
import com.sagar.aicore.EmbeddingEngine
import com.sagar.aicore.EmbeddingTask
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

    private class FakeRepo(
        private val hits: List<ScoredChunk>,
        private val keyword: List<Chunk> = emptyList(),
    ) : DocumentRepository {
        override suspend fun createDocument(projectId: Long, title: String, uri: String, localPath: String, mime: String, pageCount: Int): Long = 0
        override suspend fun getDocument(documentId: Long): DocumentEntity? = null
        override suspend fun addChunks(documentId: Long, projectId: Long, dim: Int, chunks: List<ChunkInput>) {}
        override suspend fun findSimilarChunks(queryEmbedding: FloatArray, k: Int, projectId: Long): List<ScoredChunk> = hits
        override suspend fun keywordCandidates(projectId: Long, terms: List<String>, limit: Int, dim: Int): List<Chunk> = keyword
        override suspend fun listDocuments(projectId: Long): List<DocumentEntity> = emptyList()
        override suspend fun chunksForProject(projectId: Long, documentId: Long, dim: Int): List<Chunk> = emptyList()
        override suspend fun chunkCount(projectId: Long, dim: Int): Long = 0
        override suspend fun documentIdsWithChunks(dim: Int): List<Long> = emptyList()
        override suspend fun clearChunksOfDocument(documentId: Long, dim: Int) {}
        override suspend fun deleteDocument(documentId: Long) {}
        override suspend fun deleteDocumentsOfProject(projectId: Long) {}
    }

    private fun scored(score: Double, id: Long = 0, text: String = "fact") =
        ScoredChunk(Chunk(id = id, documentId = 1, projectId = 1, text = text, pageNumber = 0, chunkIndex = 0), score)

    private fun chunk(id: Long, text: String) =
        Chunk(id = id, documentId = 1, projectId = 1, text = text, pageNumber = 0, chunkIndex = 0)

    @Test fun blankQueryReturnsEmptyWithoutEmbedding() = runTest {
        val r = DefaultDocumentRetriever(embedder, FakeRepo(listOf(scored(0.1))))
        assertTrue(r.retrieve(1, "   ").isEmpty)
        assertEquals(0, embedder.calls) // short-circuits before embedding
    }

    @Test fun nonPositiveProjectReturnsEmpty() = runTest {
        val r = DefaultDocumentRetriever(embedder, FakeRepo(listOf(scored(0.1))))
        assertTrue(r.retrieve(0, "hello").isEmpty)
    }

    @Test fun farHitsWithNoKeywordMatchAreEmpty() = runTest {
        // Vector hits beyond the cutoff AND no keyword candidates → no grounding.
        val r = DefaultDocumentRetriever(embedder, FakeRepo(listOf(scored(0.9), scored(0.95))), maxDistance = 0.5)
        assertTrue(r.retrieve(1, "off topic").isEmpty)
    }

    @Test fun nearVectorHitsAreKept() = runTest {
        val r = DefaultDocumentRetriever(embedder, FakeRepo(listOf(scored(0.2, id = 1), scored(0.9, id = 2))), maxDistance = 0.5)
        val ctx = r.retrieve(1, "on topic")
        assertFalse(ctx.isEmpty)
        assertEquals(1, ctx.citations.size) // only the near chunk survives the vector gate
    }

    @Test fun keywordArmRecoversExactMatchVectorMissed() = runTest {
        // Vector arm finds nothing relevant (all far), but the keyword arm matches
        // an exact term — hybrid should still ground on it.
        val r = DefaultDocumentRetriever(
            embedder,
            FakeRepo(
                hits = listOf(scored(0.9, id = 1)), // filtered by the gate
                keyword = listOf(chunk(5, "the project codename is ZEPHYR NINE")),
            ),
            maxDistance = 0.5,
        )
        val ctx = r.retrieve(1, "what is zephyr nine")
        assertFalse(ctx.isEmpty)
        assertEquals(1, ctx.citations.size)
    }
}
