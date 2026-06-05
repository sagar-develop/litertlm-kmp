/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.llm

import android.app.Application
import com.nativelm.app.data.db.ObjectBoxDocumentRepository
import com.nativelm.app.rag.extract.AndroidDocumentFileStore
import com.nativelm.app.rag.extract.AndroidTextExtractor
import com.nativelm.app.rag.extract.MlKitOcrEngine
import com.sagar.aicore.MediaPipeEmbeddingEngine
import com.sagar.aicore.rag.DefaultDocumentIngestor
import com.sagar.aicore.rag.DefaultDocumentRetriever
import com.sagar.aicore.rag.DocumentIngestor
import com.sagar.aicore.rag.DocumentRetriever
import com.sagar.aicore.rag.IngestState
import com.sagar.aicore.rag.RetrievedContext
import com.sagar.aicore.rag.StoredChunk
import com.sagar.aicore.rag.StoredDocument
import com.sagar.aicore.rag.TextChunker
import kotlinx.coroutines.flow.Flow

/**
 * Wires the document-RAG stack — embedding engine, vector store, ingestion and
 * retrieval — in the same manual-DI style as [EngineHolder]. The ingestion and
 * retrieval *logic* lives in the engine (`com.sagar.aicore.rag`); this holder
 * supplies the Android-backed implementations of its interfaces (the ObjectBox
 * [com.sagar.aicore.rag.DocumentStore], the [com.sagar.aicore.rag.TextExtractor],
 * and the [com.sagar.aicore.rag.FileStore]) and exposes a small app-facing API.
 * All sources are project-scoped. Reuses EngineHolder's ModelManager to locate the
 * USE-Lite model.
 */
class RagHolder(app: Application, private val engineHolder: EngineHolder) {

    private val embeddingEngine = MediaPipeEmbeddingEngine(app)
    private val repository = ObjectBoxDocumentRepository()
    private val extractor = AndroidTextExtractor(app, MlKitOcrEngine())
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

    suspend fun documents(projectId: Long): List<StoredDocument> = repository.listDocuments(projectId)

    /**
     * Every chunk of a project (or one source when [documentId] > 0), in reading
     * order, for Studio's whole-source-set map-reduce. See
     * [com.sagar.aicore.rag.DocumentStore.chunksForProject].
     */
    suspend fun chunksForProject(projectId: Long, documentId: Long = 0): List<StoredChunk> =
        repository.chunksForProject(projectId, documentId)

    /** A single source's metadata (incl. [StoredDocument.localPath]) for the viewer. */
    suspend fun document(id: Long): StoredDocument? = repository.getDocument(id)

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
