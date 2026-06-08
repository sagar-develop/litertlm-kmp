/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.data.db

import com.sagar.aicore.rag.DocumentStore
import com.sagar.aicore.rag.NewChunk
import com.sagar.aicore.rag.ScoredChunk
import com.sagar.aicore.rag.StoredChunk
import com.sagar.aicore.rag.StoredDocument
import io.objectbox.Box
import io.objectbox.Property
import io.objectbox.query.QueryBuilder.StringOrder
import io.objectbox.query.QueryCondition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ObjectBox-backed [DocumentStore]. Vector retrieval uses HNSW, post-filtered to a
 * project. All ops run on [Dispatchers.IO]; the generated `*_` query metadata lives
 * in this package.
 *
 * **Dim routing:** chunks live in one of four HNSW entities by embedder dimension —
 * 100 (USE-Lite, legacy/fallback) or 128/256/512 (EmbeddingGemma Matryoshka tiers).
 * A device uses exactly one at a time, selected by [activeDim]; the others stay
 * empty. All four are structurally identical, so the per-dim ops are written once
 * over a generic [ChunkType] and dispatched by [active]. This class owns the only
 * mapping between ObjectBox entities and the engine's neutral DTOs.
 */
class ObjectBoxDocumentRepository : DocumentStore {

    private val documents = ObjectBox.store.boxFor(DocumentEntity::class.java)

    /**
     * The embedding dimension whose HNSW entity ingest/retrieval currently route to.
     * Set by the app when the active embedder changes (USE-Lite=100, Gemma=128/256/512).
     * Defaults to 100 so existing USE-Lite installs behave unchanged.
     */
    @Volatile
    var activeDim: Int = 100

    // ── per-dim entity bundles (box + generated properties + mappers) ──

    private class ChunkType<T : Any>(
        val dim: Int,
        val box: Box<T>,
        val emb: Property<T>,
        val projectId: Property<T>,
        val documentId: Property<T>,
        val chunkIndex: Property<T>,
        val text: Property<T>,
        val newEntity: (NewChunk, Long, Long) -> T,
        val toStored: (T) -> StoredChunk,
    )

    private val useType = ChunkType(
        100, ObjectBox.store.boxFor(DocumentChunkEntity::class.java),
        DocumentChunkEntity_.embedding, DocumentChunkEntity_.projectId,
        DocumentChunkEntity_.documentId, DocumentChunkEntity_.chunkIndex, DocumentChunkEntity_.text,
        { nc, doc, proj -> DocumentChunkEntity().apply { documentId = doc; projectId = proj; text = nc.text; pageNumber = nc.pageNumber; chunkIndex = nc.chunkIndex; embedding = nc.embedding } },
        { e -> StoredChunk(e.id, e.documentId, e.projectId, e.text, e.pageNumber, e.chunkIndex, e.embedding ?: FloatArray(0)) },
    )
    private val gemma128 = ChunkType(
        128, ObjectBox.store.boxFor(GemmaChunk128Entity::class.java),
        GemmaChunk128Entity_.embedding, GemmaChunk128Entity_.projectId,
        GemmaChunk128Entity_.documentId, GemmaChunk128Entity_.chunkIndex, GemmaChunk128Entity_.text,
        { nc, doc, proj -> GemmaChunk128Entity().apply { documentId = doc; projectId = proj; text = nc.text; pageNumber = nc.pageNumber; chunkIndex = nc.chunkIndex; embedding = nc.embedding } },
        { e -> StoredChunk(e.id, e.documentId, e.projectId, e.text, e.pageNumber, e.chunkIndex, e.embedding ?: FloatArray(0)) },
    )
    private val gemma256 = ChunkType(
        256, ObjectBox.store.boxFor(GemmaChunk256Entity::class.java),
        GemmaChunk256Entity_.embedding, GemmaChunk256Entity_.projectId,
        GemmaChunk256Entity_.documentId, GemmaChunk256Entity_.chunkIndex, GemmaChunk256Entity_.text,
        { nc, doc, proj -> GemmaChunk256Entity().apply { documentId = doc; projectId = proj; text = nc.text; pageNumber = nc.pageNumber; chunkIndex = nc.chunkIndex; embedding = nc.embedding } },
        { e -> StoredChunk(e.id, e.documentId, e.projectId, e.text, e.pageNumber, e.chunkIndex, e.embedding ?: FloatArray(0)) },
    )
    private val gemma512 = ChunkType(
        512, ObjectBox.store.boxFor(GemmaChunk512Entity::class.java),
        GemmaChunk512Entity_.embedding, GemmaChunk512Entity_.projectId,
        GemmaChunk512Entity_.documentId, GemmaChunk512Entity_.chunkIndex, GemmaChunk512Entity_.text,
        { nc, doc, proj -> GemmaChunk512Entity().apply { documentId = doc; projectId = proj; text = nc.text; pageNumber = nc.pageNumber; chunkIndex = nc.chunkIndex; embedding = nc.embedding } },
        { e -> StoredChunk(e.id, e.documentId, e.projectId, e.text, e.pageNumber, e.chunkIndex, e.embedding ?: FloatArray(0)) },
    )

    private fun active(): ChunkType<*> = forDim(activeDim)
    private fun forDim(dim: Int): ChunkType<*> = when (dim) {
        128 -> gemma128
        256 -> gemma256
        512 -> gemma512
        else -> useType
    }

    /** Every dim's bundle — for cross-dim cleanup (delete must hit all entities). */
    private val allTypes = listOf(useType, gemma128, gemma256, gemma512)

    override suspend fun createDocument(
        projectId: Long,
        title: String,
        uri: String,
        localPath: String,
        mime: String,
        pageCount: Int,
    ): Long = withContext(Dispatchers.IO) {
        documents.put(
            DocumentEntity().apply {
                this.projectId = projectId
                this.title = title
                sourceUri = uri
                this.localPath = localPath
                mimeType = mime
                this.pageCount = pageCount
                chunkCount = 0
                createdAt = System.currentTimeMillis()
            },
        )
    }

    override suspend fun getDocument(documentId: Long): StoredDocument? =
        withContext(Dispatchers.IO) { documents.get(documentId)?.toStored() }

    override suspend fun addChunks(
        documentId: Long,
        projectId: Long,
        chunks: List<NewChunk>,
    ): Unit = withContext(Dispatchers.IO) {
        addChunksTo(active(), documentId, projectId, chunks)
    }

    private fun <T : Any> addChunksTo(ct: ChunkType<T>, documentId: Long, projectId: Long, chunks: List<NewChunk>) {
        ct.box.put(chunks.map { ct.newEntity(it, documentId, projectId) })
        documents.get(documentId)?.let { doc ->
            doc.chunkCount = ct.box.query().equal(ct.documentId, documentId).build().use { it.count().toInt() }
            documents.put(doc)
        }
    }

    override suspend fun findSimilarChunks(
        queryEmbedding: FloatArray,
        k: Int,
        projectId: Long,
    ): List<ScoredChunk> = withContext(Dispatchers.IO) {
        val ct = active()
        require(queryEmbedding.size == ct.dim) {
            "Query embedding dim ${queryEmbedding.size} != active index dim ${ct.dim}"
        }
        nearest(ct, queryEmbedding, k, projectId)
    }

    private fun <T : Any> nearest(ct: ChunkType<T>, q: FloatArray, k: Int, projectId: Long): List<ScoredChunk> {
        // ObjectBox applies projectId AFTER the HNSW k-NN, so over-fetch then filter.
        val searchK = maxOf(k * 30, 150)
        return ct.box.query()
            .nearestNeighbors(ct.emb, q, searchK)
            .equal(ct.projectId, projectId)
            .build()
            .use { query ->
                query.findWithScores().asSequence()
                    .map { ScoredChunk(ct.toStored(it.get()), it.score) }
                    .take(k)
                    .toList()
            }
    }

    override suspend fun keywordCandidates(
        projectId: Long,
        terms: List<String>,
        limit: Int,
    ): List<StoredChunk> = withContext(Dispatchers.IO) {
        if (terms.isEmpty()) return@withContext emptyList()
        keyword(active(), projectId, terms, limit)
    }

    private fun <T : Any> keyword(ct: ChunkType<T>, projectId: Long, terms: List<String>, limit: Int): List<StoredChunk> {
        fun contains(term: String) = ct.text.contains(term, StringOrder.CASE_INSENSITIVE)
        val anyTerm: QueryCondition<T> =
            terms.drop(1).fold<String, QueryCondition<T>>(contains(terms.first())) { acc, t -> acc.or(contains(t)) }
        return ct.box.query(ct.projectId.equal(projectId).and(anyTerm))
            .build()
            .use { it.find(0L, limit.toLong()) }
            .map { ct.toStored(it) }
    }

    override suspend fun listDocuments(projectId: Long): List<StoredDocument> =
        withContext(Dispatchers.IO) {
            documents.query()
                .equal(DocumentEntity_.projectId, projectId)
                .orderDesc(DocumentEntity_.createdAt)
                .build()
                .use { it.find() }
                .map { it.toStored() }
        }

    override suspend fun chunksForProject(
        projectId: Long,
        documentId: Long,
    ): List<StoredChunk> = withContext(Dispatchers.IO) {
        forProject(active(), projectId, documentId)
    }

    private fun <T : Any> forProject(ct: ChunkType<T>, projectId: Long, documentId: Long): List<StoredChunk> {
        val condition = if (documentId > 0L) ct.documentId.equal(documentId) else ct.projectId.equal(projectId)
        return ct.box.query(condition)
            .order(ct.documentId)
            .order(ct.chunkIndex)
            .build()
            .use { it.find() }
            .map { ct.toStored(it) }
    }

    override suspend fun deleteDocument(documentId: Long): Unit = withContext(Dispatchers.IO) {
        // tx-split (landmine #3): remove HNSW-indexed chunks first, then the parent.
        // Clear from every dim — a doc may have been embedded under a prior embedder.
        allTypes.forEach { removeForDoc(it, documentId) }
        documents.remove(documentId)
    }

    private fun <T : Any> removeForDoc(ct: ChunkType<T>, documentId: Long) {
        ct.box.query().equal(ct.documentId, documentId).build().use { it.remove() }
    }

    override suspend fun deleteDocumentsOfProject(projectId: Long): Unit = withContext(Dispatchers.IO) {
        allTypes.forEach { removeForProject(it, projectId) }
        documents.query().equal(DocumentEntity_.projectId, projectId).build().use { it.remove() }
    }

    private fun <T : Any> removeForProject(ct: ChunkType<T>, projectId: Long) {
        ct.box.query().equal(ct.projectId, projectId).build().use { it.remove() }
    }

    // ── migration / cross-dim helpers (Module E) ──

    /** Chunks for a project under a SPECIFIC dim's index (not necessarily the active one). */
    suspend fun chunksForProjectAtDim(dim: Int, projectId: Long, documentId: Long = 0): List<StoredChunk> =
        withContext(Dispatchers.IO) { forProject(forDim(dim), projectId, documentId) }

    /** Write already-embedded chunks into a SPECIFIC dim's index (used by re-index migration). */
    suspend fun addChunksAtDim(dim: Int, documentId: Long, projectId: Long, chunks: List<NewChunk>): Unit =
        withContext(Dispatchers.IO) { addChunksTo(forDim(dim), documentId, projectId, chunks) }

    /** Whether a project has any chunks under [dim]'s index. */
    suspend fun hasChunksAtDim(dim: Int, projectId: Long): Boolean = withContext(Dispatchers.IO) {
        countForProject(forDim(dim), projectId) > 0
    }

    /**
     * The distinct document ids that have chunks under [dim]'s index for a project.
     * Reads only the documentId column (no embeddings) so it's cheap to call on every
     * project open — used by the document-level migration check.
     */
    suspend fun documentIdsAtDim(dim: Int, projectId: Long): Set<Long> = withContext(Dispatchers.IO) {
        docIdsForProject(forDim(dim), projectId)
    }

    private fun <T : Any> docIdsForProject(ct: ChunkType<T>, projectId: Long): Set<Long> =
        ct.box.query().equal(ct.projectId, projectId).build().use { q ->
            q.property(ct.documentId).findLongs().toHashSet()
        }

    private fun <T : Any> countForProject(ct: ChunkType<T>, projectId: Long): Long =
        ct.box.query().equal(ct.projectId, projectId).build().use { it.count() }

    /** Delete a project's chunks under [dim]'s index (reclaim after migration). */
    suspend fun clearDimForProject(dim: Int, projectId: Long): Unit =
        withContext(Dispatchers.IO) { removeForProject(forDim(dim), projectId) }

    // ── entity ↔ engine DTO mapping for documents ──

    private fun DocumentEntity.toStored() = StoredDocument(
        id = id,
        projectId = projectId,
        title = title,
        sourceUri = sourceUri,
        localPath = localPath,
        mimeType = mimeType,
        pageCount = pageCount,
        chunkCount = chunkCount,
        createdAt = createdAt,
    )
}
