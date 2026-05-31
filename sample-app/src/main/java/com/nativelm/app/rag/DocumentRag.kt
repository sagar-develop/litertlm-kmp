/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.rag

import kotlinx.coroutines.flow.Flow

/**
 * Ingests a picked file into the document store: extract → chunk → embed → persist.
 * Emits [IngestState] for UI progress; terminal state is [IngestState.Done] or
 * [IngestState.Failed].
 */
interface DocumentIngestor {
    fun ingest(uri: String, displayName: String?): Flow<IngestState>
}

/** Progress of a single ingestion. */
sealed interface IngestState {
    data object Extracting : IngestState
    data class Chunking(val total: Int) : IngestState
    data class Embedding(val done: Int, val total: Int) : IngestState
    data class Done(val documentId: Long, val chunkCount: Int) : IngestState
    data class Failed(val error: String) : IngestState
}

/**
 * Retrieves grounding context for a chat query: embeds the query, pulls the top-k
 * chunks, and returns a prompt-injection-safe, size-capped context block plus the
 * citations to render under the answer. Returns [RetrievedContext.EMPTY] when there
 * is nothing relevant (caller should then fall back to ordinary chat).
 */
interface DocumentRetriever {
    suspend fun retrieve(
        query: String,
        k: Int = 5,
        documentIds: List<Long>? = null,
    ): RetrievedContext
}

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

/** One source surfaced under a grounded answer. */
data class Citation(
    val documentTitle: String,
    val pageNumber: Int,
    val snippet: String,
)
