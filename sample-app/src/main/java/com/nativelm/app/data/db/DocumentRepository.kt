/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.data.db

/**
 * Persistence + k-NN retrieval over imported documents and their embedded chunks.
 *
 * Heavy operations are `suspend` (they hit the HNSW index / disk) and implementations
 * must move work off the main thread. Cascade-delete is explicit and **tx-split**:
 * delete a document's chunks in one transaction, then the document in another —
 * combining them deadlocks the HNSW commit (predecessor landmine).
 */
interface DocumentRepository {

    /** Insert the parent document row; returns its new id. */
    suspend fun createDocument(title: String, uri: String, mime: String, pageCount: Int): Long

    /** Persist embedded chunks for [documentId] and bump the document's chunkCount. */
    suspend fun addChunks(documentId: Long, chunks: List<DocumentChunkEntity>)

    /**
     * Top-[k] chunks by cosine similarity to [queryEmbedding]. When [documentIds]
     * is non-null, restrict the search to those documents.
     */
    suspend fun findSimilarChunks(
        queryEmbedding: FloatArray,
        k: Int,
        documentIds: List<Long>? = null,
    ): List<ScoredChunk>

    /** All documents, newest first, for the management screen. */
    suspend fun listDocuments(): List<DocumentEntity>

    /** Delete a document and all its chunks (tx-split). */
    suspend fun deleteDocument(documentId: Long)
}

/**
 * A retrieved chunk with its [score]. For the COSINE index this is ObjectBox's
 * cosine *distance* (range ~0..2, **lower = closer**); results come back ordered
 * closest-first.
 */
data class ScoredChunk(
    val chunk: DocumentChunkEntity,
    val score: Double,
)
