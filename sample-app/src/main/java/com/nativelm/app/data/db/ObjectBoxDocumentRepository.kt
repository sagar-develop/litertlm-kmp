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
        // ObjectBox applies the projectId condition AFTER the HNSW k-NN, not during
        // it. So asking for just `k` neighbors globally can return zero rows for
        // this project when closer chunks from OTHER projects fill the k slots —
        // silently breaking project-scoped grounding. Over-fetch a wide candidate
        // set, then filter to the project and keep the k closest. searchK is bounded
        // by the index search ef (indexingSearchCount = 200) for recall.
        val searchK = maxOf(k * 30, 150)
        chunks.query()
            .nearestNeighbors(DocumentChunkEntity_.embedding, queryEmbedding, searchK)
            .equal(DocumentChunkEntity_.projectId, projectId)
            .build()
            .use { query ->
                query.findWithScores().asSequence()
                    .map { ScoredChunk(it.get(), it.score) }
                    .take(k)
                    .toList()
            }
    }

    override suspend fun keywordCandidates(
        projectId: Long,
        terms: List<String>,
        limit: Int,
    ): List<DocumentChunkEntity> = withContext(Dispatchers.IO) {
        if (terms.isEmpty()) return@withContext emptyList()
        // OR of case-insensitive "text contains term" across the query terms.
        // Seed the fold as QueryCondition so .or() (which widens from
        // PropertyQueryCondition) type-checks.
        fun contains(term: String) =
            DocumentChunkEntity_.text.contains(term, StringOrder.CASE_INSENSITIVE)
        val anyTerm: QueryCondition<DocumentChunkEntity> =
            terms.drop(1).fold<String, QueryCondition<DocumentChunkEntity>>(contains(terms.first())) { acc, t ->
                acc.or(contains(t))
            }
        chunks.query(DocumentChunkEntity_.projectId.equal(projectId).and(anyTerm))
            .build()
            .use { it.find(0L, limit.toLong()) }
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
