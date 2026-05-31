/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.llm

import android.app.Application
import com.nativelm.app.data.db.DocumentEntity
import com.nativelm.app.data.db.ObjectBoxDocumentRepository
import com.nativelm.app.rag.DefaultDocumentIngestor
import com.nativelm.app.rag.DefaultDocumentRetriever
import com.nativelm.app.rag.DocumentIngestor
import com.nativelm.app.rag.DocumentRetriever
import com.nativelm.app.rag.RetrievedContext
import com.nativelm.app.rag.extract.AndroidTextExtractor
import com.nativelm.app.rag.extract.TextChunker
import com.sagar.aicore.MediaPipeEmbeddingEngine

/**
 * Wires the document-RAG stack — embedding engine, vector store, ingestion and
 * retrieval — in the same manual-DI style as [EngineHolder]. Reuses EngineHolder's
 * ModelManager to locate the USE-Lite embedding model on disk.
 */
class RagHolder(app: Application, private val engineHolder: EngineHolder) {

    private val embeddingEngine = MediaPipeEmbeddingEngine(app)
    private val repository = ObjectBoxDocumentRepository()
    private val extractor = AndroidTextExtractor(app)

    val ingestor: DocumentIngestor =
        DefaultDocumentIngestor(extractor, TextChunker(), embeddingEngine, repository)
    private val retriever: DocumentRetriever =
        DefaultDocumentRetriever(embeddingEngine, repository)

    @Volatile
    private var embeddingReady = false

    val isEmbeddingModelDownloaded: Boolean
        get() = engineHolder.isModelDownloaded(USE_FILE_NAME)

    /** Initialize the embedder if its model is on disk. Returns true once ready. */
    suspend fun ensureEmbeddingReady(): Boolean {
        if (embeddingReady) return true
        if (!isEmbeddingModelDownloaded) return false
        embeddingEngine.initialize(engineHolder.modelPath(USE_FILE_NAME))
        embeddingReady = true
        return true
    }

    suspend fun documents(): List<DocumentEntity> = repository.listDocuments()

    suspend fun deleteDocument(id: Long) = repository.deleteDocument(id)

    suspend fun retrieve(query: String): RetrievedContext = retriever.retrieve(query)

    companion object {
        const val USE_MODEL_ID = "universal-sentence-encoder"
        const val USE_FILE_NAME = "universal_sentence_encoder.tflite"
    }
}
