/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore.rag

import kotlinx.coroutines.flow.Flow

/**
 * Persistence + project-scoped k-NN retrieval over source documents and their
 * embedded chunks. The engine's RAG pipeline depends only on this interface; the
 * host app provides the concrete implementation (e.g. ObjectBox + an HNSW index)
 * and maps its storage entities to/from the engine DTOs in [RagModels].
 *
 * Every source belongs to a project; retrieval is scoped to one project so a
 * notebook only answers from its own sources. Heavy ops are `suspend`.
 */
interface DocumentStore {

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
    suspend fun getDocument(documentId: Long): StoredDocument?

    /** Persist embedded chunks for [documentId] (stamped with [projectId]) and bump chunkCount. */
    suspend fun addChunks(documentId: Long, projectId: Long, chunks: List<NewChunk>)

    /** Top-[k] chunks by cosine similarity to [queryEmbedding], within [projectId]. */
    suspend fun findSimilarChunks(queryEmbedding: FloatArray, k: Int, projectId: Long): List<ScoredChunk>

    /**
     * Chunks in [projectId] whose text contains at least one of [terms]
     * (case-insensitive), capped at [limit] — the lexical-candidate set for the
     * keyword arm of hybrid retrieval.
     */
    suspend fun keywordCandidates(projectId: Long, terms: List<String>, limit: Int): List<StoredChunk>

    /** Sources in [projectId], newest first. */
    suspend fun listDocuments(projectId: Long): List<StoredDocument>

    /**
     * Every chunk in [projectId] (or just [documentId] when > 0), ordered by document
     * then chunk index. Backs whole-source-set passes (e.g. Studio); embeddings are
     * not needed here.
     */
    suspend fun chunksForProject(projectId: Long, documentId: Long = 0): List<StoredChunk>

    /** Delete a document and all its chunks. */
    suspend fun deleteDocument(documentId: Long)

    /** Delete every source (and chunks) of [projectId]. */
    suspend fun deleteDocumentsOfProject(projectId: Long)
}

/**
 * Ingests sources into a project: extract → chunk → embed → persist. Emits
 * [IngestState] for UI progress; terminal state is [IngestState.Done] or
 * [IngestState.Failed].
 */
interface DocumentIngestor {
    /** Import a picked file ([uri]) as a source of [projectId]. */
    fun ingest(projectId: Long, uri: String, displayName: String?): Flow<IngestState>

    /** Save raw [text] (e.g. a chat bubble) as a source of [projectId]. */
    fun ingestText(projectId: Long, title: String, text: String): Flow<IngestState>
}

/**
 * Retrieves grounding context for a chat query: embeds the query, pulls the top-k
 * chunks, and returns a prompt-injection-safe, size-capped context block plus the
 * citations to render under the answer. Returns [RetrievedContext.EMPTY] when there
 * is nothing relevant (caller should then fall back to ordinary chat).
 */
interface DocumentRetriever {
    suspend fun retrieve(projectId: Long, query: String, k: Int = 8): RetrievedContext
}

/**
 * Optional second-stage cross-encoder reranker. Given a query and candidate passages,
 * returns a relevance score per passage (higher = more relevant). Used by
 * [DefaultDocumentRetriever] to re-order the top fused candidates before the final
 * top-k. Recommended only on flagship-tier devices; absence simply skips reranking.
 */
interface Reranker {
    suspend fun initialize(modelPath: String)
    suspend fun scores(query: String, passages: List<String>): FloatArray
}

/**
 * Extracts plain text from an imported file (PDF, image, or text). The host app
 * provides the platform implementation (e.g. PDFBox + on-device OCR on Android,
 * PDFKit + Vision on iOS).
 *
 * Contract:
 *  - [uri] is a platform file/content URI string; its tail is opaque, so use
 *    [displayName] (the picker-provided name) for extension detection.
 *  - Throws on unreadable/corrupt input; never returns partial garbage. The output
 *    [ExtractedDoc.text] should separate pages with a form feed (code point 12) when
 *    page boundaries are known, so the chunker can attribute page numbers.
 */
interface TextExtractor {
    suspend fun extract(uri: String, displayName: String?): ExtractedDoc
}

/**
 * Keeps a durable, app-private copy of each imported source file so a citation can
 * reopen the original later. The host app provides the platform implementation.
 */
interface FileStore {
    /**
     * Copy the bytes behind [uri] into app-private storage, returning the absolute
     * path of the copy, or null if it couldn't be copied (ingestion still succeeds —
     * the source just won't be reopenable).
     */
    suspend fun copyToLocal(uri: String, extension: String): String?

    /** Delete one stored copy by its absolute [localPath]. No-op if blank/missing. */
    suspend fun delete(localPath: String)

    /** Remove every stored copy (used when clearing all app data). */
    suspend fun deleteAll()
}
