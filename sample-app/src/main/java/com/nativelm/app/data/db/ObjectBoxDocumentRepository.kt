/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.data.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ObjectBox-backed [DocumentRepository]. Vector retrieval uses the HNSW index on
 * [DocumentChunkEntity.embedding]. All operations run on [Dispatchers.IO]. Uses the
 * Java-style QueryBuilder to match the rest of the data layer; the generated `*_`
 * query-metadata classes live in this package so need no import.
 */
class ObjectBoxDocumentRepository : DocumentRepository {

    private val documents = ObjectBox.store.boxFor(DocumentEntity::class.java)
    private val chunks = ObjectBox.store.boxFor(DocumentChunkEntity::class.java)

    override suspend fun createDocument(
        title: String,
        uri: String,
        mime: String,
        pageCount: Int,
    ): Long = withContext(Dispatchers.IO) {
        documents.put(
            DocumentEntity().apply {
                this.title = title
                sourceUri = uri
                mimeType = mime
                this.pageCount = pageCount
                chunkCount = 0
                createdAt = System.currentTimeMillis()
            },
        )
    }

    override suspend fun addChunks(
        documentId: Long,
        chunks: List<DocumentChunkEntity>,
    ): Unit = withContext(Dispatchers.IO) {
        chunks.forEach {
            it.id = 0
            it.documentId = documentId
        }
        this@ObjectBoxDocumentRepository.chunks.put(chunks)
        // Keep the parent's chunkCount in sync (ingestion may add in batches).
        documents.get(documentId)?.let { doc ->
            doc.chunkCount = this@ObjectBoxDocumentRepository.chunks
                .query().equal(DocumentChunkEntity_.documentId, documentId).build()
                .use { it.count().toInt() }
            documents.put(doc)
        }
    }

    override suspend fun findSimilarChunks(
        queryEmbedding: FloatArray,
        k: Int,
        documentIds: List<Long>?,
    ): List<ScoredChunk> = withContext(Dispatchers.IO) {
        require(queryEmbedding.size == DocumentChunkEntity.EMBEDDING_DIM) {
            "Query embedding dim ${queryEmbedding.size} != ${DocumentChunkEntity.EMBEDDING_DIM}"
        }
        val builder = chunks.query()
            .nearestNeighbors(DocumentChunkEntity_.embedding, queryEmbedding, k)
        if (!documentIds.isNullOrEmpty()) {
            // Post-filter the HNSW candidates to the selected documents (may return < k).
            builder.`in`(DocumentChunkEntity_.documentId, documentIds.toLongArray())
        }
        builder.build().use { query ->
            query.findWithScores().map { ScoredChunk(it.get(), it.score) }
        }
    }

    override suspend fun listDocuments(): List<DocumentEntity> = withContext(Dispatchers.IO) {
        documents.query().orderDesc(DocumentEntity_.createdAt).build().use { it.find() }
    }

    override suspend fun deleteDocument(documentId: Long): Unit = withContext(Dispatchers.IO) {
        // tx-split (predecessor landmine #3): remove HNSW-indexed chunks in their
        // own transaction, THEN the parent — combining them deadlocks the HNSW commit.
        chunks.query().equal(DocumentChunkEntity_.documentId, documentId).build().use { it.remove() }
        documents.remove(documentId)
    }
}
