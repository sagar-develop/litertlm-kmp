/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore.rag

/**
 * Engine-neutral document/chunk model for the on-device RAG orchestration layer.
 *
 * These DTOs are the boundary between the engine's RAG pipeline and whatever
 * persistence a consumer plugs in via [DocumentStore]. They deliberately carry no
 * ObjectBox / Android / platform types, so the ingestion + retrieval logic lives
 * in `commonMain` and a host app maps these to/from its own storage entities.
 */

/** A stored source document. Field names mirror a typical persistence entity so mapping is trivial. */
data class StoredDocument(
    val id: Long,
    val projectId: Long,
    val title: String,
    val sourceUri: String,
    val localPath: String,
    val mimeType: String,
    val pageCount: Int,
    val chunkCount: Int = 0,
    val createdAt: Long = 0,
)

/** A persisted, embedded chunk of a document. [embedding] may be empty when not needed by a query. */
data class StoredChunk(
    val id: Long,
    val documentId: Long,
    val projectId: Long,
    val text: String,
    val pageNumber: Int,
    val chunkIndex: Int,
    val embedding: FloatArray = FloatArray(0),
)

/** A chunk to be inserted (no id yet). The store assigns ids and owning keys. */
data class NewChunk(
    val text: String,
    val pageNumber: Int,
    val chunkIndex: Int,
    val embedding: FloatArray,
)

/** A retrieved chunk with its cosine *distance* [score] (lower = closer; ordered closest-first). */
data class ScoredChunk(
    val chunk: StoredChunk,
    val score: Double,
)

/** One source surfaced under a grounded answer. */
data class Citation(
    /** Owning [StoredDocument.id], so a tap can reopen the source. */
    val documentId: Long,
    val documentTitle: String,
    val pageNumber: Int,
    val snippet: String,
)

/**
 * The formatted context fed to `LocalAiEngine.formatPrompt(retrievedContext = ...)`,
 * plus the [citations] surfaced in the UI. [contextText] is already wrapped in
 * explicit boundary markers and capped — do not re-wrap it.
 */
data class RetrievedContext(
    val contextText: String,
    val citations: List<Citation>,
) {
    val isEmpty: Boolean get() = citations.isEmpty()

    companion object {
        val EMPTY = RetrievedContext("", emptyList())
    }
}

/** Progress of a single ingestion. Terminal state is [Done] or [Failed]. */
sealed interface IngestState {
    data object Extracting : IngestState
    data class Chunking(val total: Int) : IngestState
    data class Embedding(val done: Int, val total: Int) : IngestState
    data class Done(val documentId: Long, val chunkCount: Int) : IngestState
    data class Failed(val error: String) : IngestState
}

/**
 * Result of extraction. [text] is the full document text; when the source has
 * pages and the extractor preserves boundaries, pages are separated by a form
 * feed (code point 12) so the chunker can attribute a page number.
 */
data class ExtractedDoc(
    val text: String,
    val pageCount: Int,
    val mimeType: String,
)

/** A single chunk produced by [TextChunker]. [pageNumber] is 0 when unknown. */
data class Chunk(
    val text: String,
    val index: Int,
    val pageNumber: Int = 0,
)
