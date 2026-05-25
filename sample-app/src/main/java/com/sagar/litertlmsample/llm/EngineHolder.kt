/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.litertlmsample.llm

import android.content.Context
import com.sagar.aicore.AiEngineRequest
import com.sagar.aicore.AndroidHardwareProvider
import com.sagar.aicore.AndroidPlatformFolders
import com.sagar.aicore.DownloadState
import com.sagar.aicore.EngineState
import com.sagar.aicore.KtorModelManager
import com.sagar.aicore.LiteRtLmLocalAiEngine
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow

/**
 * Wires the library together manually for the sample. Real consumers would
 * use kotlin-inject (the library's own DI graph) or their preferred DI
 * framework (Hilt / Koin / etc). The library's `AiEngineComponent` interface
 * is the formal contract; this is a teaching-friendly direct-instantiation
 * version that shows what the dependency chain looks like.
 */
class EngineHolder(context: Context) {

    private val httpClient = HttpClient()
    private val hardwareProvider = AndroidHardwareProvider(context.applicationContext)
    private val platformFolders = AndroidPlatformFolders(context.applicationContext)

    val modelManager = KtorModelManager(httpClient, platformFolders)
    val engine = LiteRtLmLocalAiEngine(hardwareProvider)

    val descriptor get() = engine.descriptor

    /** True if the model file exists on disk; safe to call any time. */
    fun isModelDownloaded(fileName: String): Boolean =
        modelManager.isModelDownloaded(fileName)

    /** Absolute path to the model file (whether or not it exists). */
    fun modelPath(fileName: String): String =
        modelManager.getModelPath(fileName)

    fun downloadModel(url: String, fileName: String): Flow<DownloadState> =
        modelManager.downloadModel(url, fileName)

    suspend fun initializeEngine(modelPath: String): EngineState<Unit> =
        engine.initializeEngine(modelPath)

    fun generate(request: AiEngineRequest): Flow<EngineState<String>> =
        engine.generateStream(request)
}
