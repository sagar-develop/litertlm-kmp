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
import com.sagar.aicore.EmbeddingEngine
import com.sagar.aicore.EmbeddingTask
import com.sagar.aicore.MediaPipeEmbeddingEngine
import com.sagar.aicore.ModelDescriptor
import com.sagar.aicore.ModelFormat
import com.sagar.aicore.OnnxEmbeddingEngine
import com.sagar.aicore.OnnxReranker
import com.sagar.aicore.rag.DefaultDocumentIngestor
import com.sagar.aicore.rag.DefaultDocumentRetriever
import com.sagar.aicore.rag.DocumentIngestor
import com.sagar.aicore.rag.DocumentRetriever
import com.sagar.aicore.rag.IngestState
import com.sagar.aicore.rag.RagConfig
import com.sagar.aicore.rag.Reranker
import com.sagar.aicore.rag.RetrievedContext
import com.sagar.aicore.rag.StoredChunk
import com.sagar.aicore.rag.StoredDocument
import com.sagar.aicore.rag.TextChunker
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.Flow

/**
 * Wires the document-RAG stack — embedding engine, vector store, ingestion and
 * retrieval — in the same manual-DI style as [EngineHolder]. The orchestration
 * *logic* lives in the engine (`com.sagar.aicore.rag`); this holder supplies the
 * Android-backed implementations and exposes a small app-facing API.
 *
 * **Pluggable embedder:** the active embedder (USE-Lite or an EmbeddingGemma
 * Matryoshka tier) is chosen by [configureEmbedder] — which rebuilds the engine,
 * routes the [ObjectBoxDocumentRepository] to the matching HNSW dim, and re-tunes
 * the retriever. On flagship tiers a cross-encoder [Reranker] is attached too.
 */
class RagHolder(private val app: Application, private val engineHolder: EngineHolder) {

    private val repository = ObjectBoxDocumentRepository()
    private val extractor = AndroidTextExtractor(app, MlKitOcrEngine())
    private val fileStore = AndroidDocumentFileStore(app)

    private var embeddingEngine: EmbeddingEngine? = null
    private var ingestor: DocumentIngestor? = null
    private var retriever: DocumentRetriever? = null
    private var reranker: Reranker? = null

    private var activeDescriptor: ModelDescriptor? = null
    private var rerankerDescriptor: ModelDescriptor? = null
    private var activeDim: Int = 100

    @Volatile
    private var embeddingReady = false

    val repositoryRef: ObjectBoxDocumentRepository get() = repository

    /**
     * Select the active embedder (and optional reranker). Rebuilds the pipeline and
     * routes the vector store to [dim]. Cheap; safe to call repeatedly — a no-op when
     * nothing changed. Does NOT download or initialize (see [ensureEmbeddingReady]).
     */
    fun configureEmbedder(descriptor: ModelDescriptor, dim: Int, rerankerDescriptor: ModelDescriptor?) {
        if (descriptor.id == activeDescriptor?.id && dim == activeDim &&
            rerankerDescriptor?.id == this.rerankerDescriptor?.id
        ) {
            return
        }
        Napier.i(tag = "RagHolder") { "configureEmbedder id=${descriptor.id} dim=$dim reranker=${rerankerDescriptor?.id}" }
        activeDescriptor = descriptor
        this.rerankerDescriptor = rerankerDescriptor
        activeDim = dim
        repository.activeDim = dim
        embeddingReady = false
        (embeddingEngine as? OnnxEmbeddingEngine)?.release()
        (reranker as? OnnxReranker)?.release()
        reranker = null

        embeddingEngine = when (descriptor.format) {
            ModelFormat.ONNX_EMBEDDER -> {
                val tok = descriptor.companions.first { it.fileName.endsWith("tokenizer.json") }.fileName
                OnnxEmbeddingEngine(dim, tok)
            }
            else -> MediaPipeEmbeddingEngine(app)
        }
        ingestor = DefaultDocumentIngestor(extractor, TextChunker(), embeddingEngine!!, repository, fileStore)
        retriever = DefaultDocumentRetriever(embeddingEngine!!, repository, ragConfigFor(descriptor), reranker = null)
    }

    val isEmbeddingModelDownloaded: Boolean
        get() = activeDescriptor?.let { engineHolder.isModelFullyDownloaded(it) } ?: false

    /** Initialize the active embedder (and reranker if present) if files are on disk. */
    suspend fun ensureEmbeddingReady(): Boolean {
        val desc = activeDescriptor ?: return false
        val engine = embeddingEngine ?: return false
        if (embeddingReady) return true
        if (!engineHolder.isModelFullyDownloaded(desc)) return false

        engine.initialize(engineHolder.modelPath(desc.fileName))

        // Attach the reranker only if its model is also present.
        val rdesc = rerankerDescriptor
        val rr = if (rdesc != null && engineHolder.isModelFullyDownloaded(rdesc)) {
            val tok = rdesc.companions.first { it.fileName.endsWith("tokenizer.json") }.fileName
            runCatching {
                OnnxReranker(tok).also { it.initialize(engineHolder.modelPath(rdesc.fileName)) }
            }.onFailure { Napier.e(tag = "RagHolder", throwable = it) { "Reranker init failed; continuing without" } }
                .getOrNull()
        } else {
            null
        }
        reranker = rr
        retriever = DefaultDocumentRetriever(engine, repository, ragConfigFor(desc), rr)
        embeddingReady = true
        return true
    }

    /** Per-embedder retrieval tuning. EmbeddingGemma's cosine spread differs from USE-Lite's. */
    private fun ragConfigFor(descriptor: ModelDescriptor): RagConfig = when (descriptor.format) {
        // Gemma is a far stronger embedder — tighten the distance gate, and enable the
        // document-level relevance gate (margin 0.08) so the keyword arm can't ground
        // on a lexically-similar but semantically wrong document.
        ModelFormat.ONNX_EMBEDDER -> RagConfig(relevanceMaxDistance = 0.62, docRelevanceMargin = 0.08)
        // USE-Lite (coarse 100-dim): keep prior behavior; doc-gate effectively off.
        else -> RagConfig(docRelevanceMargin = 2.0)
    }

    fun ingestFile(projectId: Long, uri: String, displayName: String?): Flow<IngestState> =
        requireIngestor().ingest(projectId, uri, displayName)

    fun ingestText(projectId: Long, title: String, text: String): Flow<IngestState> =
        requireIngestor().ingestText(projectId, title, text)

    suspend fun retrieve(projectId: Long, query: String): RetrievedContext =
        (retriever ?: return RetrievedContext.EMPTY).retrieve(projectId, query)

    /** Active embedding vector dimension (0 until an embedder is configured). */
    val activeEmbeddingDim: Int get() = activeDim

    /** Whether a cross-encoder reranker is attached to the active retriever. */
    val hasReranker: Boolean get() = reranker != null

    /**
     * Embed one text with the active embedder, returning its vector (or null if no
     * embedder is ready). For the benchmark harness to measure embedding throughput.
     */
    suspend fun embedProbe(text: String): FloatArray? =
        embeddingEngine?.embed(text, EmbeddingTask.QUERY, null)

    /**
     * Whether [projectId] needs re-indexing into the active embedder's dim. Checked
     * per-DOCUMENT, not per-project: returns true if ANY document has chunks under a
     * different dim but none under the active one. A project-level check would wrongly
     * skip a half-migrated project (e.g. one doc added after the upgrade, or a prior
     * migration that crashed partway) — leaving the un-migrated documents invisible to
     * the active vector index, which then grounds answers on the wrong source.
     */
    suspend fun needsMigration(projectId: Long): Boolean {
        val dim = activeDim
        if (dim == 100) return false
        val present = repository.documentIdsAtDim(dim, projectId)
        for (sd in SOURCE_DIMS) {
            if (sd == dim) continue
            if (repository.documentIdsAtDim(sd, projectId).any { it !in present }) return true
        }
        return false
    }

    /**
     * Re-index a project into the active embedder's dim by re-embedding the stored chunk
     * text of every document not yet present at the active dim (embeddings are derived
     * data — no re-extraction/OCR needed). Document-level and self-healing: a partially
     * migrated project is completed on the next open. The old index is kept so the
     * USE-Lite fallback still works. Emits per-chunk progress.
     */
    suspend fun migrateProject(projectId: Long, progress: (done: Int, total: Int) -> Unit = { _, _ -> }): Boolean {
        val dim = activeDim
        if (dim == 100) return true
        if (!ensureEmbeddingReady()) return false
        val engine = embeddingEngine ?: return false

        val present = repository.documentIdsAtDim(dim, projectId).toHashSet()
        // Gather documents needing migration from each source dim, deduped by documentId.
        val pending = LinkedHashMap<Long, List<StoredChunk>>()
        for (sd in SOURCE_DIMS) {
            if (sd == dim) continue
            for ((docId, docChunks) in repository.chunksForProjectAtDim(sd, projectId).groupBy { it.documentId }) {
                if (docId in present || docId in pending) continue
                pending[docId] = docChunks
            }
        }
        if (pending.isEmpty()) return true

        val total = pending.values.sumOf { it.size }
        var done = 0
        for ((docId, docChunks) in pending) {
            val reembedded = docChunks.map { c ->
                val vec = engine.embed(c.text, EmbeddingTask.DOCUMENT, null)
                progress(++done, total)
                com.sagar.aicore.rag.NewChunk(c.text, c.pageNumber, c.chunkIndex, vec)
            }
            repository.addChunksAtDim(dim, docId, projectId, reembedded)
        }
        Napier.i(tag = "RagHolder") { "Migrated project $projectId: $total chunks across ${pending.size} doc(s) → $dim" }
        return true
    }

    private fun requireIngestor(): DocumentIngestor =
        ingestor ?: error("Embedder not configured. Call configureEmbedder() first.")

    suspend fun documents(projectId: Long): List<StoredDocument> = repository.listDocuments(projectId)

    suspend fun chunksForProject(projectId: Long, documentId: Long = 0): List<StoredChunk> =
        repository.chunksForProject(projectId, documentId)

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
        private val SOURCE_DIMS = listOf(100, 128, 256, 512)
    }
}
