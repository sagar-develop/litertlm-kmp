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
import com.nativelm.app.rag.IngestState
import com.nativelm.app.rag.RetrievedContext
import com.nativelm.app.rag.extract.AndroidDocumentFileStore
import com.nativelm.app.rag.extract.AndroidTextExtractor
import com.nativelm.app.rag.extract.TextChunker
import com.sagar.aicore.MediaPipeEmbeddingEngine
import kotlinx.coroutines.flow.Flow

/**
 * Wires the document-RAG stack — embedding engine, vector store, ingestion and
 * retrieval — in the same manual-DI style as [EngineHolder]. All sources are
 * project-scoped. Reuses EngineHolder's ModelManager to locate the USE-Lite model.
 */
class RagHolder(app: Application, private val engineHolder: EngineHolder) {

    private val embeddingEngine = MediaPipeEmbeddingEngine(app)
    private val repository = ObjectBoxDocumentRepository()
    private val extractor = AndroidTextExtractor(app)
    private val fileStore = AndroidDocumentFileStore(app)

    private val ingestor: DocumentIngestor =
        DefaultDocumentIngestor(extractor, TextChunker(), embeddingEngine, repository, fileStore)
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

    fun ingestFile(projectId: Long, uri: String, displayName: String?): Flow<IngestState> =
        ingestor.ingest(projectId, uri, displayName)

    fun ingestText(projectId: Long, title: String, text: String): Flow<IngestState> =
        ingestor.ingestText(projectId, title, text)

    suspend fun retrieve(projectId: Long, query: String): RetrievedContext =
        retriever.retrieve(projectId, query)

    suspend fun documents(projectId: Long): List<DocumentEntity> = repository.listDocuments(projectId)

    /** A single source's metadata (incl. [DocumentEntity.localPath]) for the viewer. */
    suspend fun document(id: Long): DocumentEntity? = repository.getDocument(id)

    suspend fun deleteDocument(id: Long) {
        repository.getDocument(id)?.localPath?.let { fileStore.delete(it) }
        repository.deleteDocument(id)
    }

    suspend fun deleteDocumentsOfProject(projectId: Long) {
        repository.listDocuments(projectId).forEach { fileStore.delete(it.localPath) }
        repository.deleteDocumentsOfProject(projectId)
    }

    /** Wipe every stored source copy (used by Settings → clear all data). */
    suspend fun deleteAllSourceFiles() = fileStore.deleteAll()

    companion object {
        const val USE_MODEL_ID = "universal-sentence-encoder"
        const val USE_FILE_NAME = "universal_sentence_encoder.tflite"
    }
}
