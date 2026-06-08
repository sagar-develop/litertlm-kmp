/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore.rag

import com.sagar.aicore.EmbeddingEngine
import com.sagar.aicore.EmbeddingTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.coroutines.cancellation.CancellationException

/**
 * Ingestion pipeline: extract (files only) → chunk → embed each chunk → persist
 * into a project's vector store. Emits [IngestState] for UI progress; embedding
 * dominates, so progress is per chunk.
 */
class DefaultDocumentIngestor(
    private val extractor: TextExtractor,
    private val chunker: TextChunker,
    private val embeddingEngine: EmbeddingEngine,
    private val store: DocumentStore,
    private val fileStore: FileStore,
) : DocumentIngestor {

    override fun ingest(projectId: Long, uri: String, displayName: String?): Flow<IngestState> = flow {
        runCatching {
            emit(IngestState.Extracting)
            val extracted = extractor.extract(uri, displayName)
            val title = displayName?.substringBeforeLast('.')?.trim()?.ifBlank { null } ?: "Document"
            // Keep a durable copy so citations can reopen the source later; the
            // picked URI dies with this import. Best-effort: a failed copy leaves
            // localPath empty (source just isn't reopenable) but the import succeeds.
            val ext = (displayName?.substringAfterLast('.', "") ?: "").ifBlank { uri.substringAfterLast('.', "") }
            val localPath = fileStore.copyToLocal(uri, ext).orEmpty()
            store(projectId, title, uri, localPath, extracted.mimeType, extracted.pageCount, extracted.text)
        }.onFailure { emitFailure(it) }
    }.flowOn(Dispatchers.Default)

    override fun ingestText(projectId: Long, title: String, text: String): Flow<IngestState> = flow {
        runCatching {
            store(projectId, title, uri = "", localPath = "", mime = "text/plain", pageCount = 0, text = text)
        }.onFailure { emitFailure(it) }
    }.flowOn(Dispatchers.Default)

    /** Chunk → embed → persist, emitting progress. Shared by file + text ingestion. */
    private suspend fun FlowCollector<IngestState>.store(
        projectId: Long,
        title: String,
        uri: String,
        localPath: String,
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

        val documentId = store.createDocument(projectId, title, uri, localPath, mime, pageCount)
        val newChunks = ArrayList<NewChunk>(chunks.size)
        chunks.forEachIndexed { i, chunk ->
            emit(IngestState.Embedding(done = i, total = chunks.size))
            val vector = embeddingEngine.embed(chunk.text, EmbeddingTask.DOCUMENT, title)
            newChunks += NewChunk(
                text = chunk.text,
                pageNumber = chunk.pageNumber,
                chunkIndex = chunk.index,
                embedding = vector,
            )
        }
        store.addChunks(documentId, projectId, newChunks)
        emit(IngestState.Embedding(done = chunks.size, total = chunks.size))
        emit(IngestState.Done(documentId, newChunks.size))
    }

    private suspend fun FlowCollector<IngestState>.emitFailure(t: Throwable) {
        if (t is CancellationException) throw t // never swallow cancellation
        emit(IngestState.Failed(t.message ?: "Import failed."))
    }
}
