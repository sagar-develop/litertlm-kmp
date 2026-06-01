/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.llm

import android.content.Context
import com.sagar.aicore.AiEngineRequest
import com.sagar.aicore.AndroidHardwareProvider
import com.sagar.aicore.AndroidPlatformFolders
import com.sagar.aicore.ChatSession
import com.sagar.aicore.ChatTurn
import com.sagar.aicore.DownloadState
import com.sagar.aicore.EngineState
import com.sagar.aicore.KtorModelManager
import com.sagar.aicore.LiteRtLmLocalAiEngine
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow

/**
 * Wires the library together manually for NativeLM. Real consumers would use
 * kotlin-inject (the library's own DI graph) or Hilt/Koin; this is a
 * teaching-friendly direct-instantiation version. Generalized off any single
 * hardcoded model: downloads/initializes whatever [com.sagar.aicore.ModelDescriptor]
 * the Model Management UI selects, and supports auth headers (HF token).
 */
class EngineHolder(context: Context) {

    private val httpClient = HttpClient()
    private val hardwareProvider = AndroidHardwareProvider(context.applicationContext)
    private val platformFolders = AndroidPlatformFolders(context.applicationContext)

    val modelManager = KtorModelManager(httpClient, platformFolders)
    val engine = LiteRtLmLocalAiEngine(hardwareProvider)

    /** Total physical RAM in MB, for gating which model tiers a device can run. */
    val deviceRamMb: Long get() = hardwareProvider.getDeviceCapabilities().totalRamMb

    val descriptor get() = engine.descriptor

    fun isModelDownloaded(fileName: String): Boolean =
        modelManager.isModelDownloaded(fileName)

    fun modelPath(fileName: String): String =
        modelManager.getModelPath(fileName)

    fun deleteModel(fileName: String) = modelManager.deleteModel(fileName)

    fun downloadModel(
        url: String,
        fileName: String,
        sha256: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): Flow<DownloadState> = modelManager.downloadModel(url, fileName, sha256, headers)

    suspend fun initializeEngine(modelPath: String, supportsVision: Boolean): EngineState<Unit> =
        engine.initializeEngine(modelPath, supportsVision)

    fun generate(request: AiEngineRequest): Flow<EngineState<String>> =
        engine.generateStream(request)

    /** Opens a stateful chat session (KV-cache reuse); [history] seeds prior turns. */
    fun openChatSession(
        history: List<ChatTurn>,
        systemInstruction: String?,
        temperature: Float = 0.7f,
    ): ChatSession = engine.openChatSession(history, systemInstruction, temperature)

    fun release() = engine.releaseResources()
}
