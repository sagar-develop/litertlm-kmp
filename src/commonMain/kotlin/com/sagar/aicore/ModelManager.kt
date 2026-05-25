package com.sagar.aicore

import kotlinx.coroutines.flow.Flow

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
     * the downloaded file is verified after the atomic temp→final move;
     * mismatch deletes the file and emits [DownloadState.Error]. Validation
     * is inert when null (current default for all descriptors that
     * predate Phase 7.12).
     */
    fun downloadModel(
        url: String,
        modelName: String,
        expectedSha256: String? = null,
    ): Flow<DownloadState>

    /**
     * Deletes the local model file.
     */
    fun deleteModel(modelName: String)
}
