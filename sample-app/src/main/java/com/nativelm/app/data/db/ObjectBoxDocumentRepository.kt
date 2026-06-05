/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.data.db

import io.objectbox.query.QueryCondition
import io.objectbox.query.QueryBuilder.StringOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ObjectBox-backed [DocumentRepository]. Holds two parallel HNSW chunk stores —
 * 100-dim ([DocumentChunkEntity], USE-Lite) and 256-dim ([GemmaChunkEntity],
 * EmbeddingGemma) — and routes vector ops to the one matching the active embedder's
 * `dim`. Document metadata ([DocumentEntity]) is shared. All ops run on
 * [Dispatchers.IO]; generated `*_` query metadata lives in this package.
 */
class ObjectBoxDocumentRepository : DocumentRepository {

    private val documents = ObjectBox.store.boxFor(DocumentEntity::class.java)
    private val useChunks = ObjectBox.store.boxFor(DocumentChunkEntity::class.java)
    private val gemmaChunks = ObjectBox.store.boxFor(GemmaChunkEntity::class.java)

    override suspend fun createDocument(
        projectId: Long,
        title: String,
        uri: String,
        localPath: String,
        mime: String,
        pageCount: Int,
    ): Long = withContext(Dispatchers.IO) {
        documents.put(
            DocumentEntity().apply {
                this.projectId = projectId
                this.title = title
                sourceUri = uri
                this.localPath = localPath
                mimeType = mime
                this.pageCount = pageCount
                chunkCount = 0
                createdAt = System.currentTimeMillis()
            },
        )
    }

    override suspend fun getDocument(documentId: Long): DocumentEntity? =
        withContext(Dispatchers.IO) { documents.get(documentId) }

    override suspend fun addChunks(
        documentId: Long,
        projectId: Long,
        dim: Int,
        chunks: List<ChunkInput>,
    ): Unit = withContext(Dispatchers.IO) {
        if (dim == GemmaChunkEntity.EMBEDDING_DIM) {
            gemmaChunks.put(
                chunks.map { c ->
                    GemmaChunkEntity().apply {
                        this.documentId = documentId
                        this.projectId = projectId
                        text = c.text
                        pageNumber = c.pageNumber
                        chunkIndex = c.chunkIndex
                        embedding = c.embedding
                    }
                },
            )
        } else {
            useChunks.put(
                chunks.map { c ->
                    DocumentChunkEntity().apply {
                        this.documentId = documentId
                        this.projectId = projectId
                        text = c.text
                        pageNumber = c.pageNumber
                        chunkIndex = c.chunkIndex
                        embedding = c.embedding
                    }
                },
            )
        }
        documents.get(documentId)?.let { doc ->
            doc.chunkCount = countForDocument(documentId, dim).toInt()
            documents.put(doc)
        }
    }

    private fun countForDocument(documentId: Long, dim: Int): Long =
        if (dim == GemmaChunkEntity.EMBEDDING_DIM) {
            gemmaChunks.query().equal(GemmaChunkEntity_.documentId, documentId).build()
                .use { it.count() }
        } else {
            useChunks.query().equal(DocumentChunkEntity_.documentId, documentId).build()
                .use { it.count() }
        }

    override suspend fun findSimilarChunks(
        queryEmbedding: FloatArray,
        k: Int,
        projectId: Long,
    ): List<ScoredChunk> = withContext(Dispatchers.IO) {
        // ObjectBox applies the projectId condition AFTER the HNSW k-NN, not during
        // it. Over-fetch a wide candidate set, then filter to the project and keep the
        // k closest (searchK bounded by indexingSearchCount = 200 for recall).
        val searchK = maxOf(k * 30, 150)
        if (queryEmbedding.size == GemmaChunkEntity.EMBEDDING_DIM) {
            gemmaChunks.query()
                .nearestNeighbors(GemmaChunkEntity_.embedding, queryEmbedding, searchK)
                .equal(GemmaChunkEntity_.projectId, projectId)
                .build()
                .use { q ->
                    q.findWithScores().asSequence()
                        .map { ScoredChunk(it.get().toChunk(), it.score) }
                        .take(k).toList()
                }
        } else {
            require(queryEmbedding.size == DocumentChunkEntity.EMBEDDING_DIM) {
                "Query embedding dim ${queryEmbedding.size} matches neither store"
            }
            useChunks.query()
                .nearestNeighbors(DocumentChunkEntity_.embedding, queryEmbedding, searchK)
                .equal(DocumentChunkEntity_.projectId, projectId)
                .build()
                .use { q ->
                    q.findWithScores().asSequence()
                        .map { ScoredChunk(it.get().toChunk(), it.score) }
                        .take(k).toList()
                }
        }
    }

    override suspend fun keywordCandidates(
        projectId: Long,
        terms: List<String>,
        limit: Int,
        dim: Int,
    ): List<Chunk> = withContext(Dispatchers.IO) {
        if (terms.isEmpty()) return@withContext emptyList()
        if (dim == GemmaChunkEntity.EMBEDDING_DIM) {
            fun contains(t: String) = GemmaChunkEntity_.text.contains(t, StringOrder.CASE_INSENSITIVE)
            val any: QueryCondition<GemmaChunkEntity> =
                terms.drop(1).fold<String, QueryCondition<GemmaChunkEntity>>(contains(terms.first())) { acc, t -> acc.or(contains(t)) }
            gemmaChunks.query(GemmaChunkEntity_.projectId.equal(projectId).and(any))
                .build().use { it.find(0L, limit.toLong()) }.map { it.toChunk() }
        } else {
            fun contains(t: String) = DocumentChunkEntity_.text.contains(t, StringOrder.CASE_INSENSITIVE)
            val any: QueryCondition<DocumentChunkEntity> =
                terms.drop(1).fold<String, QueryCondition<DocumentChunkEntity>>(contains(terms.first())) { acc, t -> acc.or(contains(t)) }
            useChunks.query(DocumentChunkEntity_.projectId.equal(projectId).and(any))
                .build().use { it.find(0L, limit.toLong()) }.map { it.toChunk() }
        }
    }

    override suspend fun listDocuments(projectId: Long): List<DocumentEntity> =
        withContext(Dispatchers.IO) {
            documents.query()
                .equal(DocumentEntity_.projectId, projectId)
                .orderDesc(DocumentEntity_.createdAt)
                .build()
                .use { it.find() }
        }

    override suspend fun chunksForProject(
        projectId: Long,
        documentId: Long,
        dim: Int,
    ): List<Chunk> = withContext(Dispatchers.IO) {
        if (dim == GemmaChunkEntity.EMBEDDING_DIM) {
            val cond = if (documentId > 0L) GemmaChunkEntity_.documentId.equal(documentId)
            else GemmaChunkEntity_.projectId.equal(projectId)
            gemmaChunks.query(cond)
                .order(GemmaChunkEntity_.documentId).order(GemmaChunkEntity_.chunkIndex)
                .build().use { it.find() }.map { it.toChunk() }
        } else {
            val cond = if (documentId > 0L) DocumentChunkEntity_.documentId.equal(documentId)
            else DocumentChunkEntity_.projectId.equal(projectId)
            useChunks.query(cond)
                .order(DocumentChunkEntity_.documentId).order(DocumentChunkEntity_.chunkIndex)
                .build().use { it.find() }.map { it.toChunk() }
        }
    }

    override suspend fun chunkCount(projectId: Long, dim: Int): Long = withContext(Dispatchers.IO) {
        if (dim == GemmaChunkEntity.EMBEDDING_DIM) {
            gemmaChunks.query().equal(GemmaChunkEntity_.projectId, projectId).build().use { it.count() }
        } else {
            useChunks.query().equal(DocumentChunkEntity_.projectId, projectId).build().use { it.count() }
        }
    }

    override suspend fun documentIdsWithChunks(dim: Int): List<Long> = withContext(Dispatchers.IO) {
        if (dim == GemmaChunkEntity.EMBEDDING_DIM) {
            gemmaChunks.query().build()
                .use { it.property(GemmaChunkEntity_.documentId).distinct().findLongs() }.toList()
        } else {
            useChunks.query().build()
                .use { it.property(DocumentChunkEntity_.documentId).distinct().findLongs() }.toList()
        }
    }

    override suspend fun clearChunksOfDocument(documentId: Long, dim: Int): Unit = withContext(Dispatchers.IO) {
        if (dim == GemmaChunkEntity.EMBEDDING_DIM) {
            gemmaChunks.query().equal(GemmaChunkEntity_.documentId, documentId).build().use { it.remove() }
        } else {
            useChunks.query().equal(DocumentChunkEntity_.documentId, documentId).build().use { it.remove() }
        }
    }

    override suspend fun deleteDocument(documentId: Long): Unit = withContext(Dispatchers.IO) {
        // tx-split (landmine #3): remove HNSW-indexed chunks (both stores) first, then the parent.
        useChunks.query().equal(DocumentChunkEntity_.documentId, documentId).build().use { it.remove() }
        gemmaChunks.query().equal(GemmaChunkEntity_.documentId, documentId).build().use { it.remove() }
        documents.remove(documentId)
    }

    override suspend fun deleteDocumentsOfProject(projectId: Long): Unit = withContext(Dispatchers.IO) {
        useChunks.query().equal(DocumentChunkEntity_.projectId, projectId).build().use { it.remove() }
        gemmaChunks.query().equal(GemmaChunkEntity_.projectId, projectId).build().use { it.remove() }
        documents.query().equal(DocumentEntity_.projectId, projectId).build().use { it.remove() }
    }

    private fun DocumentChunkEntity.toChunk() =
        Chunk(id, documentId, projectId, text, pageNumber, chunkIndex)

    private fun GemmaChunkEntity.toChunk() =
        Chunk(id, documentId, projectId, text, pageNumber, chunkIndex)
}
