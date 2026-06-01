/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.rag

import com.nativelm.app.data.db.DocumentChunkEntity
import com.nativelm.app.data.db.DocumentEntity
import com.nativelm.app.data.db.DocumentRepository
import com.nativelm.app.data.db.ScoredChunk
import com.sagar.aicore.EmbeddingEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultDocumentRetrieverTest {

    private val embedder = object : EmbeddingEngine {
        var calls = 0
        override suspend fun initialize(modelPath: String) {}
        override suspend fun embed(text: String): FloatArray {
            calls++
            return FloatArray(100)
        }
    }

    private class FakeRepo(private val hits: List<ScoredChunk>) : DocumentRepository {
        override suspend fun createDocument(projectId: Long, title: String, uri: String, localPath: String, mime: String, pageCount: Int): Long = 0
        override suspend fun getDocument(documentId: Long): DocumentEntity? = null
        override suspend fun addChunks(documentId: Long, projectId: Long, chunks: List<DocumentChunkEntity>) {}
        override suspend fun findSimilarChunks(queryEmbedding: FloatArray, k: Int, projectId: Long): List<ScoredChunk> = hits
        override suspend fun listDocuments(projectId: Long): List<DocumentEntity> = emptyList()
        override suspend fun deleteDocument(documentId: Long) {}
        override suspend fun deleteDocumentsOfProject(projectId: Long) {}
    }

    private fun scored(score: Double) =
        ScoredChunk(DocumentChunkEntity().apply { documentId = 1; text = "fact" }, score)

    @Test fun blankQueryReturnsEmptyWithoutEmbedding() = runTest {
        val r = DefaultDocumentRetriever(embedder, FakeRepo(listOf(scored(0.1))))
        assertTrue(r.retrieve(1, "   ").isEmpty)
        assertEquals(0, embedder.calls) // short-circuits before embedding
    }

    @Test fun nonPositiveProjectReturnsEmpty() = runTest {
        val r = DefaultDocumentRetriever(embedder, FakeRepo(listOf(scored(0.1))))
        assertTrue(r.retrieve(0, "hello").isEmpty)
    }

    @Test fun farHitsAreFilteredToEmpty() = runTest {
        // Every hit is beyond the cutoff → no grounding, no bogus citations.
        val r = DefaultDocumentRetriever(embedder, FakeRepo(listOf(scored(0.9), scored(0.95))), maxDistance = 0.5)
        assertTrue(r.retrieve(1, "off topic").isEmpty)
    }

    @Test fun nearHitsAreKept() = runTest {
        val r = DefaultDocumentRetriever(embedder, FakeRepo(listOf(scored(0.2), scored(0.9))), maxDistance = 0.5)
        val ctx = r.retrieve(1, "on topic")
        assertFalse(ctx.isEmpty)
        assertEquals(1, ctx.citations.size) // only the near chunk survives the gate
    }
}
