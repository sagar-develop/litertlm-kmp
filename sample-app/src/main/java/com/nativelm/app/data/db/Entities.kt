/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.data.db

import io.objectbox.annotation.Entity
import io.objectbox.annotation.HnswIndex
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import io.objectbox.annotation.VectorDistanceType

/**
 * A Project (a NotebookLM-style notebook): a named container that owns a set of
 * source [DocumentEntity]s and exactly one chat [ConversationEntity], whose
 * answers are grounded in those sources.
 */
@Entity
class ProjectEntity {
    @Id var id: Long = 0
    var name: String = ""
    var createdAt: Long = 0
    var updatedAt: Long = 0
}

/**
 * A saved conversation (one chat thread in the drawer). Messages are linked by
 * [MessageEntity.conversationId] rather than an ObjectBox relation — a plain
 * indexed foreign key keeps the model simple and cascade-delete explicit.
 *
 * [projectId] is 0 for default general chats, or the owning [ProjectEntity.id]
 * for a project's (single) grounded chat.
 */
@Entity
class ConversationEntity {
    @Id var id: Long = 0

    @Index
    var projectId: Long = 0
    var title: String = ""
    var createdAt: Long = 0
    var updatedAt: Long = 0
}

/** One message in a conversation. [role] is "user" or "assistant". */
@Entity
class MessageEntity {
    @Id var id: Long = 0

    @Index
    var conversationId: Long = 0
    var role: String = ROLE_USER
    var text: String = ""
    var createdAt: Long = 0

    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}

/**
 * A document imported for retrieval-augmented chat. The extracted text is split
 * into [DocumentChunkEntity] rows; this entity is just the parent metadata shown
 * in the document list. Chunks link back by [DocumentChunkEntity.documentId] (a
 * plain indexed FK, cascade-delete handled explicitly — see DocumentRepository).
 */
@Entity
class DocumentEntity {
    @Id var id: Long = 0

    /** Owning [ProjectEntity.id]. Every source belongs to a project. */
    @Index
    var projectId: Long = 0
    var title: String = ""
    var sourceUri: String = ""
    var mimeType: String = ""
    var pageCount: Int = 0
    var chunkCount: Int = 0
    var createdAt: Long = 0
}

/**
 * One embedded chunk of a document, stored with its USE-Lite embedding for
 * HNSW k-NN retrieval. Embedding is 100-dim (USE-Lite); reserve a 256-dim
 * migration path for EmbeddingGemma in a later version — changing [EMBEDDING_DIM]
 * requires updating the [HnswIndex] dimensions in lockstep.
 */
@Entity
class DocumentChunkEntity {
    @Id var id: Long = 0

    @Index
    var documentId: Long = 0

    /** Denormalized owning [ProjectEntity.id] so retrieval can filter the HNSW query directly. */
    @Index
    var projectId: Long = 0
    var text: String = ""
    var pageNumber: Int = 0
    var chunkIndex: Int = 0

    // NOTE: dimensions must be a literal here (annotation constant). Keep it in
    // lockstep with [EMBEDDING_DIM] below — both are 100 (USE-Lite).
    @HnswIndex(
        dimensions = 100L,
        distanceType = VectorDistanceType.COSINE,
        neighborsPerNode = 48,
        indexingSearchCount = 200,
    )
    var embedding: FloatArray? = null

    companion object {
        const val EMBEDDING_DIM: Int = 100
    }
}
