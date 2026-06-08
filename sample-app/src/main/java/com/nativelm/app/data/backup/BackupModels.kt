/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.data.backup

import kotlinx.serialization.Serializable

/** Bump when the on-disk backup format changes incompatibly. Import rejects newer. */
const val BACKUP_SCHEMA_VERSION = 2

/**
 * Manifest embedding-dim field, kept for back-compat of the format. Since v2 each
 * [ChunkDto] carries its own [ChunkDto.dim], a manifest-level dim mismatch is no
 * longer fatal — cross-embedder backups re-index from the included chunk text.
 */
const val BACKUP_EMBEDDING_DIM = 100

/** Suggested file extension / container name for a backup. */
const val BACKUP_EXTENSION = "nlmbak"

/**
 * Plaintext manifest stored as `manifest.json` in the backup zip. Holds no secrets, so the
 * UI can show "backup from <date>, N projects" before asking for the passphrase. Carries the
 * Argon2 salt + cost params needed to re-derive the key on import.
 */
@Serializable
data class BackupManifest(
    val schemaVersion: Int,
    val appVersion: String,
    val createdAt: Long,
    val embeddingDim: Int,
    val saltB64: String,
    val argon2MemKiB: Int,
    val argon2Iterations: Int,
    val argon2Parallelism: Int,
    val counts: BackupCounts,
)

@Serializable
data class BackupCounts(
    val projects: Int,
    val conversations: Int,
    val messages: Int,
    val documents: Int,
    val chunks: Int,
    val artifacts: Int,
    val files: Int,
)

/**
 * The full entity graph, serialized to JSON and AES-GCM-encrypted as `data.json.enc`.
 * IDs here are the *original* device IDs; import remaps them to fresh IDs.
 */
@Serializable
data class BackupPayload(
    val projects: List<ProjectDto>,
    val conversations: List<ConversationDto>,
    val messages: List<MessageDto>,
    val documents: List<DocumentDto>,
    val chunks: List<ChunkDto>,
    val artifacts: List<ArtifactDto>,
    val prefs: PrefsDto,
)

@Serializable
data class ProjectDto(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class ConversationDto(
    val id: Long,
    val projectId: Long,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class MessageDto(
    val id: Long,
    val conversationId: Long,
    val role: String,
    val text: String,
    val createdAt: Long,
    val citationsJson: String? = null,
)

@Serializable
data class DocumentDto(
    val id: Long,
    val projectId: Long,
    val title: String,
    val sourceUri: String,
    /** Extension of the bundled source file (so import re-materializes `docs/<uuid>.<ext>`). */
    val fileExt: String,
    /** Whether a `files/<id>.enc` entry exists for this document. */
    val hasFile: Boolean,
    val mimeType: String,
    val pageCount: Int,
    val chunkCount: Int,
    val createdAt: Long,
)

@Serializable
data class ChunkDto(
    val id: Long,
    val documentId: Long,
    val projectId: Long,
    val text: String,
    val pageNumber: Int,
    val chunkIndex: Int,
    /** Base64 of little-endian float32 embedding bytes; null for un-embedded chunks. */
    val embeddingB64: String? = null,
    /**
     * Embedding dimension / HNSW index this chunk belongs to: 100 (USE-Lite),
     * 128/256/512 (EmbeddingGemma tiers). Defaults to 100 for v1 backups. On import,
     * chunks restore into the matching index; if the device's active embedder differs,
     * the project re-indexes from this chunk's [text] on next open.
     */
    val dim: Int = 100,
)

@Serializable
data class ArtifactDto(
    val id: Long,
    val projectId: Long,
    val type: String,
    val title: String,
    val content: String,
    val sourceId: Long,
    val scopeLabel: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class PrefsDto(
    val themeMode: String? = null,
    val selectedModelId: String? = null,
)

/** Outcome of a successful import, for the confirmation toast. */
data class ImportResult(
    val projects: Int,
    val conversations: Int,
    val documents: Int,
)

/** User-facing backup failure (wrong passphrase, incompatible format, I/O). */
class BackupException(message: String) : Exception(message)
