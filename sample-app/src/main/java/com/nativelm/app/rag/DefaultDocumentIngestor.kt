/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.rag

import com.nativelm.app.data.db.DocumentChunkEntity
import com.nativelm.app.data.db.DocumentRepository
import com.nativelm.app.rag.extract.TextChunker
import com.nativelm.app.rag.extract.TextExtractor
import com.sagar.aicore.EmbeddingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.coroutines.cancellation.CancellationException

/**
 * Ingestion pipeline: extract (files only) → chunk → embed each chunk → persist
 * into a project's HNSW vector store. Emits [IngestState] for UI progress;
 * embedding dominates, so progress is per chunk.
 */
class DefaultDocumentIngestor(
    private val extractor: TextExtractor,
    private val chunker: TextChunker,
    private val embeddingEngine: EmbeddingEngine,
    private val repository: DocumentRepository,
) : DocumentIngestor {

    override fun ingest(projectId: Long, uri: String, displayName: String?): Flow<IngestState> = flow {
        runCatching {
            emit(IngestState.Extracting)
            val extracted = extractor.extract(uri, displayName)
            val title = displayName?.substringBeforeLast('.')?.trim()?.ifBlank { null } ?: "Document"
            store(projectId, title, uri, extracted.mimeType, extracted.pageCount, extracted.text)
        }.onFailure { emitFailure(it) }
    }.flowOn(Dispatchers.Default)

    override fun ingestText(projectId: Long, title: String, text: String): Flow<IngestState> = flow {
        runCatching {
            store(projectId, title, uri = "", mime = "text/plain", pageCount = 0, text = text)
        }.onFailure { emitFailure(it) }
    }.flowOn(Dispatchers.Default)

    /** Chunk → embed → persist, emitting progress. Shared by file + text ingestion. */
    private suspend fun FlowCollector<IngestState>.store(
        projectId: Long,
        title: String,
        uri: String,
        mime: String,
        pageCount: Int,
        text: String,
    ) {
        val chunks = chunker.chunk(text)
        if (chunks.isEmpty()) {
            emit(IngestState.Failed("No readable text found."))
            return
        }
        emit(IngestState.Chunking(chunks.size))

        val documentId = repository.createDocument(projectId, title, uri, mime, pageCount)
        val entities = ArrayList<DocumentChunkEntity>(chunks.size)
        chunks.forEachIndexed { i, chunk ->
            emit(IngestState.Embedding(done = i, total = chunks.size))
            val vector = embeddingEngine.embed(chunk.text)
            entities += DocumentChunkEntity().apply {
                this.documentId = documentId
                this.projectId = projectId
                this.text = chunk.text
                pageNumber = chunk.pageNumber
                chunkIndex = chunk.index
                embedding = vector
            }
        }
        repository.addChunks(documentId, projectId, entities)
        emit(IngestState.Embedding(done = chunks.size, total = chunks.size))
        emit(IngestState.Done(documentId, entities.size))
    }

    private suspend fun FlowCollector<IngestState>.emitFailure(t: Throwable) {
        if (t is CancellationException) throw t // never swallow cancellation (Lift 6)
        emit(IngestState.Failed(t.message ?: "Import failed."))
    }
}
