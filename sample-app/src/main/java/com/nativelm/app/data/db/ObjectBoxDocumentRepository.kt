/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.data.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ObjectBox-backed [DocumentRepository]. Vector retrieval uses the HNSW index on
 * [DocumentChunkEntity.embedding], post-filtered to a project. All ops run on
 * [Dispatchers.IO]; the generated `*_` query metadata lives in this package.
 */
class ObjectBoxDocumentRepository : DocumentRepository {

    private val documents = ObjectBox.store.boxFor(DocumentEntity::class.java)
    private val chunks = ObjectBox.store.boxFor(DocumentChunkEntity::class.java)

    override suspend fun createDocument(
        projectId: Long,
        title: String,
        uri: String,
        mime: String,
        pageCount: Int,
    ): Long = withContext(Dispatchers.IO) {
        documents.put(
            DocumentEntity().apply {
                this.projectId = projectId
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
        projectId: Long,
        chunks: List<DocumentChunkEntity>,
    ): Unit = withContext(Dispatchers.IO) {
        chunks.forEach {
            it.id = 0
            it.documentId = documentId
            it.projectId = projectId
        }
        this@ObjectBoxDocumentRepository.chunks.put(chunks)
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
        projectId: Long,
    ): List<ScoredChunk> = withContext(Dispatchers.IO) {
        require(queryEmbedding.size == DocumentChunkEntity.EMBEDDING_DIM) {
            "Query embedding dim ${queryEmbedding.size} != ${DocumentChunkEntity.EMBEDDING_DIM}"
        }
        chunks.query()
            .nearestNeighbors(DocumentChunkEntity_.embedding, queryEmbedding, k)
            .equal(DocumentChunkEntity_.projectId, projectId)
            .build()
            .use { query -> query.findWithScores().map { ScoredChunk(it.get(), it.score) } }
    }

    override suspend fun listDocuments(projectId: Long): List<DocumentEntity> =
        withContext(Dispatchers.IO) {
            documents.query()
                .equal(DocumentEntity_.projectId, projectId)
                .orderDesc(DocumentEntity_.createdAt)
                .build()
                .use { it.find() }
        }

    override suspend fun deleteDocument(documentId: Long): Unit = withContext(Dispatchers.IO) {
        // tx-split (landmine #3): remove HNSW-indexed chunks first, then the parent.
        chunks.query().equal(DocumentChunkEntity_.documentId, documentId).build().use { it.remove() }
        documents.remove(documentId)
    }

    override suspend fun deleteDocumentsOfProject(projectId: Long): Unit = withContext(Dispatchers.IO) {
        chunks.query().equal(DocumentChunkEntity_.projectId, projectId).build().use { it.remove() }
        documents.query().equal(DocumentEntity_.projectId, projectId).build().use { it.remove() }
    }
}
