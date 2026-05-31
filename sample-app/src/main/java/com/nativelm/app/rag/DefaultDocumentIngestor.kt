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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.coroutines.cancellation.CancellationException

/**
 * Ingestion pipeline: extract → chunk → embed each chunk → persist with the HNSW
 * vector index. Emits [IngestState] for UI progress. Embedding dominates the time,
 * so progress is reported per chunk.
 */
class DefaultDocumentIngestor(
    private val extractor: TextExtractor,
    private val chunker: TextChunker,
    private val embeddingEngine: EmbeddingEngine,
    private val repository: DocumentRepository,
) : DocumentIngestor {

    override fun ingest(uri: String, displayName: String?): Flow<IngestState> = flow {
        try {
            emit(IngestState.Extracting)
            val extracted = extractor.extract(uri, displayName)

            val chunks = chunker.chunk(extracted.text)
            if (chunks.isEmpty()) {
                emit(IngestState.Failed("No readable text found in this file."))
                return@flow
            }
            emit(IngestState.Chunking(chunks.size))

            val title = displayName?.substringBeforeLast('.')?.trim()?.ifBlank { null } ?: "Document"
            val documentId = repository.createDocument(title, uri, extracted.mimeType, extracted.pageCount)

            val entities = ArrayList<DocumentChunkEntity>(chunks.size)
            chunks.forEachIndexed { i, chunk ->
                emit(IngestState.Embedding(done = i, total = chunks.size))
                val vector = embeddingEngine.embed(chunk.text)
                entities += DocumentChunkEntity().apply {
                    this.documentId = documentId
                    text = chunk.text
                    pageNumber = chunk.pageNumber
                    chunkIndex = chunk.index
                    embedding = vector
                }
            }
            repository.addChunks(documentId, entities)
            emit(IngestState.Embedding(done = chunks.size, total = chunks.size))
            emit(IngestState.Done(documentId, entities.size))
        } catch (ce: CancellationException) {
            throw ce // never swallow cancellation (engine production tip / Lift 6)
        } catch (e: Exception) {
            emit(IngestState.Failed(e.message ?: "Import failed."))
        }
    }.flowOn(Dispatchers.Default)
}
