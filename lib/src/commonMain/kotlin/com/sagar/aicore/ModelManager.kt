/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

sealed class DownloadState {
    data object Idle : DownloadState()
    data class Downloading(
        val progress: Float, 
        val downloadedBytes: Long, 
        val totalBytes: Long
    ) : DownloadState()
    data class Success(val localPath: String) : DownloadState()
    data class Error(val message: String, val cause: Throwable? = null) : DownloadState()
}

interface ModelManager {
    /**
     * Checks if the model exists in the local private storage.
     */
    fun isModelDownloaded(modelName: String): Boolean

    /**
     * Returns the absolute path to the model file.
     */
    fun getModelPath(modelName: String): String

    /**
     * Downloads the model from the given URL with resume support.
     * Updates are emitted as [DownloadState].
     *
     * @param expectedSha256 Optional lowercase-hex SHA-256. When non-null,
     * the downloaded file is verified after the atomic tempâ†’final move;
     * mismatch deletes the file and emits [DownloadState.Error]. Validation
     * is inert when null.
     * @param headers Optional extra request headers applied to every request
     * (including resume range requests). Use this to authenticate against a
     * gated host, e.g. mapOf("Authorization" to "Bearer hf_...") for a
     * license-gated Hugging Face repo. Empty by default.
     */
    fun downloadModel(
        url: String,
        modelName: String,
        expectedSha256: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): Flow<DownloadState>

    /**
     * Deletes the local model file.
     */
    fun deleteModel(modelName: String)

    /**
     * True only when [descriptor]'s primary file AND every
     * [ModelDescriptor.companions] file are present on disk. Single-file models
     * reduce to the primary-only check. Use this (not [isModelDownloaded] on the
     * primary alone) for multi-file models like ONNX (graph + weights blob +
     * tokenizer) — the primary `.onnx` graph is useless without its companions.
     */
    fun isModelFullyDownloaded(descriptor: ModelDescriptor): Boolean =
        isModelDownloaded(descriptor.fileName) &&
            descriptor.companions.all { isModelDownloaded(it.fileName) }

    /**
     * Downloads [descriptor]'s primary file and all its [ModelDescriptor.companions],
     * each into the model dir under its declared file name. Emits a single aggregate
     * [DownloadState.Downloading] stream across all files and only [DownloadState.Success]
     * once every file is present (and SHA-verified where pinned). Already-present files
     * are skipped, so this is resume-friendly. Any file's error ends the stream.
     *
     * [headers] (e.g. an HF `Authorization` bearer for a gated repo) are applied to
     * every file's request.
     */
    fun downloadModel(
        descriptor: ModelDescriptor,
        headers: Map<String, String> = emptyMap(),
    ): Flow<DownloadState> = flow {
        data class Item(val url: String, val name: String, val size: Long, val sha: String?)
        val items = buildList {
            add(Item(descriptor.url, descriptor.fileName, descriptor.sizeBytes, descriptor.sha256))
            descriptor.companions.forEach { add(Item(it.url, it.fileName, it.sizeBytes, it.sha256)) }
        }
        val grandTotal = items.sumOf { it.size }.coerceAtLeast(1L)
        var completedBytes = 0L
        for (item in items) {
            var itemBytes = 0L
            var failed = false
            downloadModel(item.url, item.name, item.sha, headers).collect { state ->
                when (state) {
                    is DownloadState.Downloading -> {
                        itemBytes = state.downloadedBytes
                        val done = completedBytes + itemBytes
                        emit(
                            DownloadState.Downloading(
                                progress = (done.toFloat() / grandTotal).coerceIn(0f, 1f),
                                downloadedBytes = done,
                                totalBytes = grandTotal,
                            )
                        )
                    }
                    is DownloadState.Error -> {
                        emit(state)
                        failed = true
                    }
                    // Per-file Success/Idle are internal; aggregate Success is emitted
                    // once below after all items complete.
                    is DownloadState.Success, DownloadState.Idle -> Unit
                }
            }
            if (failed) return@flow
            // Advance by the larger of the declared size and bytes actually seen
            // (already-present files emit no Downloading, so itemBytes stays 0).
            completedBytes += maxOf(item.size, itemBytes)
        }
        emit(DownloadState.Success(getModelPath(descriptor.fileName)))
    }
}
