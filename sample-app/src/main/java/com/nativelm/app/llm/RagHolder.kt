/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.llm

import android.app.Application
import com.nativelm.app.data.db.ChunkInput
import com.nativelm.app.data.db.DocumentEntity
import com.nativelm.app.data.db.GemmaChunkEntity
import com.nativelm.app.data.db.ObjectBoxDocumentRepository
import com.nativelm.app.rag.DefaultDocumentIngestor
import com.nativelm.app.rag.DefaultDocumentRetriever
import com.nativelm.app.rag.DocumentIngestor
import com.nativelm.app.rag.DocumentRetriever
import com.nativelm.app.rag.IngestState
import com.nativelm.app.rag.RetrievedContext
import com.nativelm.app.rag.extract.AndroidDocumentFileStore
import com.nativelm.app.rag.extract.AndroidTextExtractor
import com.nativelm.app.rag.extract.MlKitOcrEngine
import com.nativelm.app.rag.extract.TextChunker
import com.sagar.aicore.EmbeddingEngine
import com.sagar.aicore.EmbeddingTask
import com.sagar.aicore.HfGemmaTokenizer
import com.sagar.aicore.MediaPipeEmbeddingEngine
import com.sagar.aicore.OnnxEmbeddingEngine
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Wires the document-RAG stack — embedding engine, vector store, ingestion and
 * retrieval — in the same manual-DI style as [EngineHolder]. All sources are
 * project-scoped.
 *
 * Two embedders coexist: **EmbeddingGemma** (256-dim ONNX — preferred on capable
 * devices) and **USE-Lite** (100-dim — the friction-free fallback). The active one
 * is chosen by [useGemma]; a [SwitchableEmbeddingEngine] forwards ingest/retrieve to
 * it so the pipeline always reads the current embedder's `dimensions`, routing
 * vectors to the matching HNSW store.
 */
class RagHolder(app: Application, private val engineHolder: EngineHolder) {

    private val useEngine = MediaPipeEmbeddingEngine(app)
    private val gemmaEngine = OnnxEmbeddingEngine(
        tokenizerFactory = { dir -> HfGemmaTokenizer(File(dir, GEMMA_TOKENIZER_FILE).absolutePath) },
        dimensions = GemmaChunkEntity.EMBEDDING_DIM,
    )

    /** Delegates to whichever embedder is currently active (so `dimensions` stays correct). */
    private val active: EmbeddingEngine = object : EmbeddingEngine {
        override val dimensions: Int get() = activeEngine().dimensions
        override suspend fun initialize(modelPath: String) {}
        override suspend fun embed(text: String, task: EmbeddingTask, title: String?) =
            activeEngine().embed(text, task, title)
    }

    private val repository = ObjectBoxDocumentRepository()
    private val extractor = AndroidTextExtractor(app, MlKitOcrEngine())
    private val fileStore = AndroidDocumentFileStore(app)

    private val ingestor: DocumentIngestor =
        DefaultDocumentIngestor(extractor, TextChunker(), active, repository, fileStore)
    private val retriever: DocumentRetriever =
        DefaultDocumentRetriever(active, repository)

    @Volatile private var useReady = false
    @Volatile private var gemmaReady = false

    /** Dimension of the active embedder — Studio needs it to read the right chunk store. */
    val activeDim: Int get() = if (useGemma()) GemmaChunkEntity.EMBEDDING_DIM else 100

    private fun gemmaFilesPresent(): Boolean =
        engineHolder.isModelDownloaded(GEMMA_FILE_NAME) && engineHolder.isModelDownloaded(GEMMA_TOKENIZER_FILE)

    private fun deviceCapableForGemma(): Boolean = engineHolder.deviceRamMb >= GEMMA_MIN_RAM_MB

    /** Prefer EmbeddingGemma when its files are present and the device clears the RAM floor. */
    fun useGemma(): Boolean = gemmaFilesPresent() && deviceCapableForGemma()

    private fun activeEngine(): EmbeddingEngine = if (useGemma()) gemmaEngine else useEngine

    /** The embedder model needed for first-run download: Gemma on capable devices, else USE. */
    val preferredModelId: String
        get() = if (deviceCapableForGemma()) GEMMA_MODEL_ID else USE_MODEL_ID

    val isEmbeddingModelDownloaded: Boolean
        get() = useGemma() || engineHolder.isModelDownloaded(USE_FILE_NAME)

    /** Initialize the active embedder if its model is on disk. Returns true once ready. */
    suspend fun ensureEmbeddingReady(): Boolean {
        if (useGemma()) {
            if (!gemmaReady) {
                gemmaEngine.initialize(engineHolder.modelPath(GEMMA_FILE_NAME))
                gemmaReady = true
            }
            return true
        }
        if (!engineHolder.isModelDownloaded(USE_FILE_NAME)) return false
        if (!useReady) {
            useEngine.initialize(engineHolder.modelPath(USE_FILE_NAME))
            useReady = true
        }
        return true
    }

    fun ingestFile(projectId: Long, uri: String, displayName: String?): Flow<IngestState> =
        ingestor.ingest(projectId, uri, displayName)

    fun ingestText(projectId: Long, title: String, text: String): Flow<IngestState> =
        ingestor.ingestText(projectId, title, text)

    suspend fun retrieve(projectId: Long, query: String): RetrievedContext =
        retriever.retrieve(projectId, query)

    suspend fun documents(projectId: Long): List<DocumentEntity> = repository.listDocuments(projectId)

    /** Every chunk of a project (or one source), in reading order, from the active store. */
    suspend fun chunksForProject(projectId: Long, documentId: Long = 0) =
        repository.chunksForProject(projectId, documentId, activeDim)

    suspend fun document(id: Long): DocumentEntity? = repository.getDocument(id)

    suspend fun deleteDocument(id: Long) {
        repository.getDocument(id)?.localPath?.let { fileStore.delete(it) }
        repository.deleteDocument(id)
    }

    suspend fun deleteDocumentsOfProject(projectId: Long) {
        repository.listDocuments(projectId).forEach { fileStore.delete(it.localPath) }
        repository.deleteDocumentsOfProject(projectId)
    }

    suspend fun deleteAllSourceFiles() = fileStore.deleteAll()

    /** True when Gemma is active but legacy 100-dim chunks still need re-embedding. */
    suspend fun needsMigration(): Boolean =
        useGemma() && repository.documentIdsWithChunks(100).isNotEmpty()

    /**
     * Re-embed every legacy USE-Lite (100-dim) document into the Gemma (256-dim) store,
     * then drop its old chunks. Document-scoped and idempotent: a re-run skips documents
     * already migrated (they no longer have 100-dim chunks). [onProgress] reports
     * (doneDocs, totalDocs). Returns the number of documents migrated.
     */
    suspend fun migrateToGemma(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Int {
        if (!useGemma()) return 0
        ensureEmbeddingReady()
        val docIds = repository.documentIdsWithChunks(100)
        docIds.forEachIndexed { i, docId ->
            val doc = repository.getDocument(docId) ?: return@forEachIndexed
            val chunks = repository.chunksForProject(doc.projectId, docId, dim = 100)
            val inputs = chunks.map { c ->
                ChunkInput(
                    text = c.text,
                    pageNumber = c.pageNumber,
                    chunkIndex = c.chunkIndex,
                    embedding = gemmaEngine.embed(c.text, EmbeddingTask.DOCUMENT, doc.title),
                )
            }
            if (inputs.isNotEmpty()) {
                repository.addChunks(docId, doc.projectId, GemmaChunkEntity.EMBEDDING_DIM, inputs)
                repository.clearChunksOfDocument(docId, 100)
            }
            onProgress(i + 1, docIds.size)
        }
        if (docIds.isNotEmpty()) Napier.d(tag = "RagHolder") { "Migrated ${docIds.size} docs to EmbeddingGemma" }
        return docIds.size
    }

    companion object {
        const val USE_MODEL_ID = "universal-sentence-encoder"
        const val USE_FILE_NAME = "universal_sentence_encoder.tflite"
        const val GEMMA_MODEL_ID = "embeddinggemma-300m-onnx"
        const val GEMMA_FILE_NAME = "embeddinggemma-300m-q.onnx"
        const val GEMMA_TOKENIZER_FILE = "embeddinggemma-tokenizer.json"
        const val GEMMA_MIN_RAM_MB = 4000L
    }
}
