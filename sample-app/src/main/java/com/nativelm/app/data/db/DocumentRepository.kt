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
 * Chunk vectors live in one of two HNSW stores selected by the active embedder's
 * dimension: 100-dim (USE-Lite, [DocumentChunkEntity]) or 256-dim (EmbeddingGemma,
 * [GemmaChunkEntity]). The [dim] parameter routes vector ops to the right store;
 * [findSimilarChunks] infers it from the query vector's length. Results are returned
 * as the neutral [Chunk]/[ScoredChunk] DTOs so callers don't depend on which store
 * is active.
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

    /** Persist embedded [chunks] for [documentId] into the [dim]-dim store and bump chunkCount. */
    suspend fun addChunks(documentId: Long, projectId: Long, dim: Int, chunks: List<ChunkInput>)

    /**
     * Top-[k] chunks by cosine similarity to [queryEmbedding], within [projectId].
     * The store is selected by `queryEmbedding.size` (100 → USE-Lite, 256 → Gemma).
     */
    suspend fun findSimilarChunks(
        queryEmbedding: FloatArray,
        k: Int,
        projectId: Long,
    ): List<ScoredChunk>

    /**
     * Chunks in [projectId] (in the [dim]-dim store) whose text contains at least one
     * of [terms] (case-insensitive), capped at [limit] — the lexical-candidate set for
     * the keyword arm of hybrid retrieval.
     */
    suspend fun keywordCandidates(
        projectId: Long,
        terms: List<String>,
        limit: Int,
        dim: Int,
    ): List<Chunk>

    /** Sources in [projectId], newest first. */
    suspend fun listDocuments(projectId: Long): List<DocumentEntity>

    /**
     * Every chunk in [projectId] (or just [documentId] when > 0) from the [dim]-dim
     * store, ordered by document then chunkIndex. Backs Studio's map-reduce. Embeddings
     * are not included.
     */
    suspend fun chunksForProject(projectId: Long, documentId: Long = 0, dim: Int): List<Chunk>

    /** Count of chunks for [projectId] in the [dim]-dim store (0 when not yet indexed there). */
    suspend fun chunkCount(projectId: Long, dim: Int): Long

    /** Distinct document ids that still have chunks in the [dim]-dim store — drives migration. */
    suspend fun documentIdsWithChunks(dim: Int): List<Long>

    /** Remove a document's chunks from the [dim]-dim store only (used after re-embedding). */
    suspend fun clearChunksOfDocument(documentId: Long, dim: Int)

    /** Delete a document and all its chunks from **both** stores (tx-split). */
    suspend fun deleteDocument(documentId: Long)

    /** Delete every source (and chunks, both stores) of [projectId]. */
    suspend fun deleteDocumentsOfProject(projectId: Long)
}

/** A chunk, decoupled from which HNSW store it came from. */
data class Chunk(
    val id: Long,
    val documentId: Long,
    val projectId: Long,
    val text: String,
    val pageNumber: Int,
    val chunkIndex: Int,
)

/** A chunk to insert: text + metadata + its embedding vector. */
data class ChunkInput(
    val text: String,
    val pageNumber: Int,
    val chunkIndex: Int,
    val embedding: FloatArray,
)

/** A retrieved [Chunk] with its cosine *distance* [score] (lower = closer; ordered closest-first). */
data class ScoredChunk(
    val chunk: Chunk,
    val score: Double,
)
