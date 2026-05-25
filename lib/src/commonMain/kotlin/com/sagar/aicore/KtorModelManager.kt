/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore

import io.github.aakira.napier.Napier
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okio.*
import okio.Path.Companion.toPath
import me.tatarka.inject.annotations.Inject

@Inject
class KtorModelManager(
    private val httpClient: HttpClient,
    private val platformFolders: PlatformFolders
) : ModelManager {

    private val fileSystem = FileSystem.SYSTEM

    override fun isModelDownloaded(modelName: String): Boolean {
        val path = getModelPath(modelName).toPath()
        return fileSystem.exists(path)
    }

    override fun getModelPath(modelName: String): String {
        return (platformFolders.modelDir / modelName).toString()
    }

    override fun deleteModel(modelName: String) {
        Napier.d { "deleteModel: $modelName" }
        val path = getModelPath(modelName).toPath()
        if (fileSystem.exists(path)) {
            fileSystem.delete(path)
        }
    }

    override fun downloadModel(
        url: String,
        modelName: String,
        expectedSha256: String?,
    ): Flow<DownloadState> = flow {
        Napier.d { "downloadModel: $modelName from $url (sha256=${expectedSha256 ?: "none"})" }
        val destinationPath = getModelPath(modelName).toPath()
        val tempPath = destinationPath.parent!! / "$modelName.tmp"

        if (fileSystem.exists(destinationPath)) {
            emit(DownloadState.Success(destinationPath.toString()))
            return@flow
        }

        try {
            if (!fileSystem.exists(destinationPath.parent!!)) {
                fileSystem.createDirectories(destinationPath.parent!!)
            }

            val existingBytes = if (fileSystem.exists(tempPath)) {
                fileSystem.metadata(tempPath).size ?: 0L
            } else {
                0L
            }

            val response = httpClient.prepareGet(url) {
                if (existingBytes > 0) {
                    header(HttpHeaders.Range, "bytes=$existingBytes-")
                }
            }

            response.execute { httpResponse ->
                if (httpResponse.status == HttpStatusCode.RequestedRangeNotSatisfiable) {
                    fileSystem.delete(tempPath)
                    // Recursive call might be dangerous if not handled, but for 1 retry it's okay
                    // Better to just fail and let user retry
                    emit(DownloadState.Error("Range not satisfiable. Deleted temp file. Please retry."))
                    return@execute
                }

                if (!httpResponse.status.isSuccess() && httpResponse.status != HttpStatusCode.PartialContent) {
                    emit(DownloadState.Error("HTTP Error: ${httpResponse.status}"))
                    return@execute
                }

                val contentLength = httpResponse.contentLength() ?: 0L
                val totalBytes = if (httpResponse.status == HttpStatusCode.PartialContent) {
                    contentLength + existingBytes
                } else {
                    contentLength
                }

                var downloadedBytes = existingBytes
                val channel = httpResponse.bodyAsChannel()
                
                fileSystem.appendingSink(tempPath).buffer().use { sink ->
                    while (!channel.isClosedForRead) {
                        val packet = channel.readRemaining(8192)
                        if (packet.exhausted()) {
                            // Yield to avoid tight loop if channel is not closed but empty
                            kotlinx.coroutines.yield()
                            continue
                        }
                        while (!packet.exhausted()) {
                            val bytes = packet.readByteArray()
                            sink.write(bytes)
                            downloadedBytes += bytes.size
                            emit(DownloadState.Downloading(
                                progress = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f,
                                downloadedBytes = downloadedBytes,
                                totalBytes = totalBytes
                            ))
                        }
                    }
                }

                fileSystem.atomicMove(tempPath, destinationPath)
                Napier.i { "Model downloaded successfully: $modelName" }

                if (expectedSha256 != null) {
                    val actual = sha256OfFile(fileSystem, destinationPath).lowercase()
                    val expected = expectedSha256.lowercase()
                    if (actual != expected) {
                        Napier.e {
                            "Checksum mismatch for $modelName: expected=$expected actual=$actual"
                        }
                        fileSystem.delete(destinationPath)
                        emit(
                            DownloadState.Error(
                                "Checksum mismatch â€” model file may be corrupt or tampered. " +
                                    "The bad copy was deleted; please retry the download."
                            )
                        )
                        return@execute
                    }
                    Napier.i { "Checksum verified for $modelName" }
                }

                emit(DownloadState.Success(destinationPath.toString()))
            }
        } catch (e: Exception) {
            Napier.e(e) { "Download failed for $modelName" }
            emit(DownloadState.Error("Download failed: ${e.message}", e))
        }
    }
}
