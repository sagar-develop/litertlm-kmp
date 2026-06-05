/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.rag.extract

import android.content.Context
import android.net.Uri
import com.sagar.aicore.rag.FileStore
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * [FileStore] backed by the app's private files dir + ContentResolver.
 *
 * Keeps a durable, app-private copy of each imported source file so a citation
 * can reopen the original later. The SAF `content://` URI handed back by the
 * picker is only valid for the current import (we never take a persistable
 * grant), so without this copy a "tap citation → open PDF" would break the
 * moment the process ends. Copies live in `filesDir/docs/` and never leave the
 * app sandbox — consistent with NativeLM's no-upload promise.
 */
class AndroidDocumentFileStore(private val context: Context) : FileStore {

    private val dir: File by lazy { File(context.filesDir, DIR_NAME).apply { mkdirs() } }

    override suspend fun copyToLocal(uri: String, extension: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val ext = extension.lowercase().ifBlank { "bin" }
                val dest = File(dir, "${UUID.randomUUID()}.$ext")
                context.contentResolver.openInputStream(Uri.parse(uri)).use { input ->
                    requireNotNull(input) { "Cannot open source for copy: $uri" }
                    dest.outputStream().use { input.copyTo(it) }
                }
                dest.absolutePath
            }.onFailure {
                Napier.w(tag = TAG, throwable = it) { "Failed to copy source to local storage" }
            }.getOrNull()
        }

    override suspend fun delete(localPath: String) {
        if (localPath.isBlank()) return
        withContext(Dispatchers.IO) {
            runCatching { File(localPath).takeIf { it.exists() }?.delete() }
        }
    }

    override suspend fun deleteAll() {
        withContext(Dispatchers.IO) {
            runCatching { dir.listFiles()?.forEach { it.delete() } }
        }
    }

    private companion object {
        const val DIR_NAME = "docs"
        const val TAG = "DocFileStore"
    }
}
