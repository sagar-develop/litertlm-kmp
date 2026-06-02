/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.data.db

/**
 * Persistence + project-scoped k-NN retrieval over source documents and their
 * embedded chunks. Every source belongs to a [ProjectEntity]; retrieval is scoped
 * to one project so a notebook only answers from its own sources.
 *
 * Heavy ops are `suspend` (HNSW index / disk). Cascade-delete is explicit and
 * **tx-split**: a document's chunks are deleted in one transaction, the document
 * in another (combining them deadlocks the HNSW commit).
 */
interface DocumentRepository {

    /** Insert the parent document row in [projectId]; returns its new id. */
    suspend fun createDocument(
        projectId: Long,
        title: String,
        uri: String,
        localPath: String,
        mime: String,
        pageCount: Int,
    ): Long

    /** Fetch a single document by id, or null if it no longer exists. */
    suspend fun getDocument(documentId: Long): DocumentEntity?

    /** Persist embedded chunks for [documentId] (stamped with [projectId]) and bump chunkCount. */
    suspend fun addChunks(documentId: Long, projectId: Long, chunks: List<DocumentChunkEntity>)

    /** Top-[k] chunks by cosine similarity to [queryEmbedding], within [projectId]. */
    suspend fun findSimilarChunks(
        queryEmbedding: FloatArray,
        k: Int,
        projectId: Long,
    ): List<ScoredChunk>

    /**
     * Chunks in [projectId] whose text contains at least one of [terms]
     * (case-insensitive), capped at [limit]. The lexical-candidate set for the
     * keyword arm of hybrid retrieval — only chunks that could plausibly match are
     * loaded, so this stays cheap regardless of corpus size.
     */
    suspend fun keywordCandidates(
        projectId: Long,
        terms: List<String>,
        limit: Int,
    ): List<DocumentChunkEntity>

    /** Sources in [projectId], newest first. */
    suspend fun listDocuments(projectId: Long): List<DocumentEntity>

    /**
     * Every chunk in [projectId] (or just [documentId] when > 0), ordered by
     * document then [DocumentChunkEntity.chunkIndex] so a source reads top-to-bottom.
     * Backs Studio's map-reduce, which must see the *whole* source set rather than a
     * top-k retrieval slice. Embeddings are not needed here.
     */
    suspend fun chunksForProject(projectId: Long, documentId: Long = 0): List<DocumentChunkEntity>

    /** Delete a document and all its chunks (tx-split). */
    suspend fun deleteDocument(documentId: Long)

    /** Delete every source (and chunks) of [projectId] — used when deleting a project. */
    suspend fun deleteDocumentsOfProject(projectId: Long)
}

/** A retrieved chunk with its cosine *distance* [score] (lower = closer; ordered closest-first). */
data class ScoredChunk(
    val chunk: DocumentChunkEntity,
    val score: Double,
)
