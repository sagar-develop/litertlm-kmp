/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.rag

import kotlinx.coroutines.flow.Flow

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
    /** Retrieve grounding context for [query] from the sources of [projectId]. */
    suspend fun retrieve(
        projectId: Long,
        query: String,
        k: Int = 5,
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
