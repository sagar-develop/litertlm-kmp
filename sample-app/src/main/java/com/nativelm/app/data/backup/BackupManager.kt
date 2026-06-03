/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.data.backup

import android.content.Context
import android.util.Base64
import com.nativelm.app.data.AppPreferences
import com.nativelm.app.data.ThemeMode
import com.nativelm.app.data.db.ConversationEntity
import com.nativelm.app.data.db.DocumentChunkEntity
import com.nativelm.app.data.db.DocumentEntity
import com.nativelm.app.data.db.MessageEntity
import com.nativelm.app.data.db.ObjectBox
import com.nativelm.app.data.db.ProjectEntity
import com.nativelm.app.data.db.StudioArtifactEntity
import com.nativelm.app.rag.CitationJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Local encrypted backup: export the entire on-device knowledge base (projects, chats,
 * sources + their files, embeddings, Studio artifacts, prefs) to a single `.nlmbak` zip
 * the user controls, and restore it — additively — onto any device with the passphrase.
 *
 * Container layout:
 * ```
 * manifest.json     plaintext (schema/version/date/counts + Argon2 salt & params)
 * data.json.enc     AES-256-GCM(entity graph JSON)        IV||ciphertext
 * files/<docId>.enc AES-256-GCM(original source file)     IV||ciphertext, one per source
 * ```
 * Import streams the zip so only one source file is held in memory at a time, and remaps
 * every ObjectBox id to a fresh one (never clobbering existing data) — rewriting all
 * foreign keys, including the documentId embedded in each message's citation JSON.
 */
class BackupManager(private val context: Context) {

    private val store get() = ObjectBox.store
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ---- Export ----

    suspend fun export(
        out: OutputStream,
        passphrase: CharArray,
        appVersion: String,
        createdAt: Long,
    ) = withContext(Dispatchers.IO) {
        val projects = store.boxFor(ProjectEntity::class.java).all
        val conversations = store.boxFor(ConversationEntity::class.java).all
        val messages = store.boxFor(MessageEntity::class.java).all
        val documents = store.boxFor(DocumentEntity::class.java).all
        val chunks = store.boxFor(DocumentChunkEntity::class.java).all
        val artifacts = store.boxFor(StudioArtifactEntity::class.java).all

        val prefsStore = AppPreferences(context)
        val theme = prefsStore.themeMode.first()
        val selectedModel = prefsStore.selectedModelId.first()

        val payload = BackupPayload(
            projects = projects.map { ProjectDto(it.id, s(it.name), it.createdAt, it.updatedAt) },
            conversations = conversations.map {
                ConversationDto(it.id, it.projectId, s(it.title), it.createdAt, it.updatedAt)
            },
            messages = messages.map {
                MessageDto(it.id, it.conversationId, s(it.role), s(it.text), it.createdAt, it.citationsJson)
            },
            documents = documents.map { doc ->
                val localPath = s(doc.localPath)
                val file = localPath.takeIf { it.isNotBlank() }?.let { File(it) }
                DocumentDto(
                    id = doc.id,
                    projectId = doc.projectId,
                    title = s(doc.title),
                    sourceUri = s(doc.sourceUri),
                    fileExt = localPath.substringAfterLast('.', ""),
                    hasFile = file?.exists() == true,
                    mimeType = s(doc.mimeType),
                    pageCount = doc.pageCount,
                    chunkCount = doc.chunkCount,
                    createdAt = doc.createdAt,
                )
            },
            chunks = chunks.map {
                ChunkDto(
                    it.id, it.documentId, it.projectId, s(it.text), it.pageNumber, it.chunkIndex,
                    encodeEmbedding(it.embedding),
                )
            },
            artifacts = artifacts.map {
                ArtifactDto(
                    it.id, it.projectId, s(it.type), s(it.title), s(it.content), it.sourceId,
                    s(it.scopeLabel), it.createdAt, it.updatedAt,
                )
            },
            prefs = PrefsDto(
                themeMode = theme.name.lowercase(),
                selectedModelId = selectedModel,
            ),
        )

        val filesToBundle = payload.documents.filter { it.hasFile }
            .mapNotNull { dto -> documents.firstOrNull { it.id == dto.id }?.let { dto to File(s(it.localPath)) } }
            .filter { it.second.exists() }

        val salt = BackupCrypto.randomBytes(BackupCrypto.SALT_LEN)
        val key = BackupCrypto.deriveKey(passphrase, salt)
        try {
            val manifest = BackupManifest(
                schemaVersion = BACKUP_SCHEMA_VERSION,
                appVersion = appVersion,
                createdAt = createdAt,
                embeddingDim = BACKUP_EMBEDDING_DIM,
                saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP),
                argon2MemKiB = BackupCrypto.ARGON2_MEM_KIB,
                argon2Iterations = BackupCrypto.ARGON2_ITERATIONS,
                argon2Parallelism = BackupCrypto.ARGON2_PARALLELISM,
                counts = BackupCounts(
                    projects = payload.projects.size,
                    conversations = payload.conversations.size,
                    messages = payload.messages.size,
                    documents = payload.documents.size,
                    chunks = payload.chunks.size,
                    artifacts = payload.artifacts.size,
                    files = filesToBundle.size,
                ),
            )

            val dataEnc = BackupCrypto.encrypt(key, json.encodeToString(BackupPayload.serializer(), payload).toByteArray())

            ZipOutputStream(out).use { zip ->
                zip.writeEntry("manifest.json", json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
                zip.writeEntry("data.json.enc", dataEnc)
                for ((dto, file) in filesToBundle) {
                    zip.writeEntry("files/${dto.id}.enc", BackupCrypto.encrypt(key, file.readBytes()))
                }
            }
        } finally {
            key.fill(0)
        }
    }

    // ---- Import (additive; remaps all ids) ----

    suspend fun import(
        input: InputStream,
        passphrase: CharArray,
        prefs: AppPreferences,
    ): ImportResult = withContext(Dispatchers.IO) {
        var manifest: BackupManifest? = null
        var key: ByteArray? = null
        var payload: BackupPayload? = null
        // oldDocId -> inserted document + its file extension, so a later files/ entry can
        // re-materialize the source file and set the entity's localPath.
        val pendingFiles = HashMap<Long, PendingFile>()
        var result = ImportResult(0, 0, 0)

        try {
            ZipInputStream(input).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    when {
                        name == "manifest.json" -> {
                            manifest = json.decodeFromString(BackupManifest.serializer(), zip.readBytes().decodeToString())
                            validate(manifest!!)
                        }

                        name == "data.json.enc" -> {
                            val m = manifest ?: throw BackupException("Malformed backup: manifest missing.")
                            val salt = Base64.decode(m.saltB64, Base64.NO_WRAP)
                            key = BackupCrypto.deriveKey(
                                passphrase, salt, m.argon2MemKiB, m.argon2Iterations, m.argon2Parallelism,
                            )
                            val plain = try {
                                BackupCrypto.decrypt(key!!, zip.readBytes())
                            } catch (e: Exception) {
                                throw BackupException("Wrong passphrase, or the backup is corrupted.")
                            }
                            payload = json.decodeFromString(BackupPayload.serializer(), plain.decodeToString())
                            result = insertGraph(payload!!, pendingFiles)
                        }

                        name.startsWith("files/") && name.endsWith(".enc") -> {
                            val k = key ?: throw BackupException("Malformed backup: data before key.")
                            val oldId = name.removePrefix("files/").removeSuffix(".enc").toLongOrNull()
                            val pending = oldId?.let { pendingFiles[it] }
                            if (pending != null) {
                                val bytes = BackupCrypto.decrypt(k, zip.readBytes())
                                val docsDir = File(context.filesDir, "docs").apply { mkdirs() }
                                val dest = File(docsDir, "${UUID.randomUUID()}.${pending.ext}")
                                dest.writeBytes(bytes)
                                pending.entity.localPath = dest.absolutePath
                                store.boxFor(DocumentEntity::class.java).put(pending.entity)
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            val p = payload ?: throw BackupException("Malformed backup: no data found.")
            applyPrefs(p.prefs, prefs)
            result
        } finally {
            key?.fill(0)
        }
    }

    private fun validate(m: BackupManifest) {
        if (m.schemaVersion > BACKUP_SCHEMA_VERSION) {
            throw BackupException("This backup was made by a newer version of NativeLM. Update the app and try again.")
        }
        if (m.embeddingDim != BACKUP_EMBEDDING_DIM) {
            throw BackupException("This backup uses an incompatible embedding format and can't be restored by this version.")
        }
    }

    /** A document awaiting its source-file bytes from a `files/` zip entry. */
    private class PendingFile(val entity: DocumentEntity, val ext: String)

    /** Inserts the whole graph with fresh ids, returning counts. Files are materialized later. */
    private fun insertGraph(payload: BackupPayload, pendingFiles: HashMap<Long, PendingFile>): ImportResult {
        val projectBox = store.boxFor(ProjectEntity::class.java)
        val convBox = store.boxFor(ConversationEntity::class.java)
        val msgBox = store.boxFor(MessageEntity::class.java)
        val docBox = store.boxFor(DocumentEntity::class.java)
        val chunkBox = store.boxFor(DocumentChunkEntity::class.java)
        val artBox = store.boxFor(StudioArtifactEntity::class.java)

        val projMap = HashMap<Long, Long>()
        payload.projects.forEach { dto ->
            val e = ProjectEntity().apply {
                name = dto.name; createdAt = dto.createdAt; updatedAt = dto.updatedAt
            }
            projMap[dto.id] = projectBox.put(e)
        }

        val docMap = HashMap<Long, Long>()
        payload.documents.forEach { dto ->
            val e = DocumentEntity().apply {
                projectId = projMap[dto.projectId] ?: 0L
                title = dto.title
                sourceUri = dto.sourceUri
                localPath = "" // set when the matching files/ entry streams in (if any)
                mimeType = dto.mimeType
                pageCount = dto.pageCount
                chunkCount = dto.chunkCount
                createdAt = dto.createdAt
            }
            val newId = docBox.put(e)
            docMap[dto.id] = newId
            if (dto.hasFile) {
                val ext = dto.fileExt.ifBlank { e.mimeExtFallback() }
                pendingFiles[dto.id] = PendingFile(e, ext)
            }
        }

        val convMap = HashMap<Long, Long>()
        payload.conversations.forEach { dto ->
            val e = ConversationEntity().apply {
                projectId = if (dto.projectId == 0L) 0L else (projMap[dto.projectId] ?: 0L)
                title = dto.title; createdAt = dto.createdAt; updatedAt = dto.updatedAt
            }
            convMap[dto.id] = convBox.put(e)
        }

        val newMessages = payload.messages.mapNotNull { dto ->
            val newConvId = convMap[dto.conversationId] ?: return@mapNotNull null
            MessageEntity().apply {
                conversationId = newConvId
                role = dto.role
                text = dto.text
                createdAt = dto.createdAt
                citationsJson = remapCitations(dto.citationsJson, docMap)
            }
        }
        msgBox.put(newMessages)

        val newChunks = payload.chunks.map { dto ->
            DocumentChunkEntity().apply {
                documentId = docMap[dto.documentId] ?: 0L
                projectId = projMap[dto.projectId] ?: 0L
                text = dto.text
                pageNumber = dto.pageNumber
                chunkIndex = dto.chunkIndex
                embedding = dto.embeddingB64?.let { decodeEmbedding(it) }
            }
        }
        chunkBox.put(newChunks)

        val newArtifacts = payload.artifacts.map { dto ->
            StudioArtifactEntity().apply {
                projectId = projMap[dto.projectId] ?: 0L
                type = dto.type
                title = dto.title
                content = dto.content
                sourceId = if (dto.sourceId == 0L) 0L else (docMap[dto.sourceId] ?: 0L)
                scopeLabel = dto.scopeLabel
                createdAt = dto.createdAt
                updatedAt = dto.updatedAt
            }
        }
        artBox.put(newArtifacts)

        return ImportResult(
            projects = payload.projects.size,
            conversations = payload.conversations.size,
            documents = payload.documents.size,
        )
    }

    private suspend fun applyPrefs(dto: PrefsDto, prefs: AppPreferences) {
        when (dto.themeMode) {
            "light" -> prefs.setThemeMode(ThemeMode.LIGHT)
            "dark" -> prefs.setThemeMode(ThemeMode.DARK)
            "system" -> prefs.setThemeMode(ThemeMode.SYSTEM)
        }
        // Only adopt the backed-up model if this device hasn't already chosen one,
        // so a restore never silently switches an in-use model out from under the user.
        if (!dto.selectedModelId.isNullOrBlank() && prefs.selectedModelId.first().isNullOrBlank()) {
            prefs.setSelectedModelId(dto.selectedModelId)
        }
    }

    /** Rewrite the documentId inside a message's citation JSON to the remapped id. */
    private fun remapCitations(citationsJson: String?, docMap: Map<Long, Long>): String? {
        if (citationsJson.isNullOrBlank()) return citationsJson
        val citations = CitationJson.decode(citationsJson)
        if (citations.isEmpty()) return citationsJson
        val remapped = citations.map { c -> c.copy(documentId = docMap[c.documentId] ?: c.documentId) }
        return CitationJson.encode(remapped)
    }

    private fun DocumentEntity.mimeExtFallback(): String {
        // Prefer the original file extension carried on the DTO via docs/<uuid>.<ext>; here we
        // only have the entity, so map common mimes, defaulting to "bin".
        return when {
            mimeType.contains("pdf") -> "pdf"
            mimeType.startsWith("image/") -> mimeType.substringAfter('/').ifBlank { "img" }
            mimeType.startsWith("text/") -> "txt"
            else -> "bin"
        }
    }

    /**
     * Coerce a possibly-null entity String to "". ObjectBox returns null (not the Kotlin
     * default) for a String column on rows written before that column existed, so a field
     * typed non-null `String` can still be null at runtime — calling a String extension on
     * it would NPE. Every entity String read on export goes through this.
     */
    private fun s(value: String?): String = value ?: ""

    private fun encodeEmbedding(embedding: FloatArray?): String? {
        if (embedding == null) return null
        val bb = ByteBuffer.allocate(embedding.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        embedding.forEach { bb.putFloat(it) }
        return Base64.encodeToString(bb.array(), Base64.NO_WRAP)
    }

    private fun decodeEmbedding(b64: String): FloatArray {
        val bytes = Base64.decode(b64, Base64.NO_WRAP)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { bb.float }
    }

    private fun ZipOutputStream.writeEntry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }
}
