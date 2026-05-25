/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore.di

import com.sagar.aicore.EmbeddingEngine
import com.sagar.aicore.EngineRegistry
import com.sagar.aicore.HardwareProvider
import com.sagar.aicore.ModelCatalog
import com.sagar.aicore.NetworkProvider
import com.sagar.aicore.LocalAiEngine
import com.sagar.aicore.ModelManager
import com.sagar.aicore.PlatformFolders
import io.ktor.client.HttpClient
import me.tatarka.inject.annotations.Provides

interface AiEngineComponent {
    val localAiEngine: LocalAiEngine
    val embeddingEngine: EmbeddingEngine
    val modelManager: ModelManager
    val platformFolders: PlatformFolders
    val hardwareProvider: HardwareProvider
    val networkProvider: NetworkProvider
    /**
     * Pluggable registry of available engines. Consumers that need to
     * select engines per-call (e.g. capability-gated UI) read from here;
     * the default [localAiEngine] binding goes through
     * [EngineRegistry.selectDefault] starting in step 7.
     */
    val engineRegistry: EngineRegistry
    /**
     * Catalog of downloadable models. Single source of truth for what the
     * consumer's setup flow can download. Defaults to [InMemoryModelCatalog]
     * (a sample with placeholder URLs); production consumers bind their own
     * implementation backed by a remote config / CDN.
     */
    val modelCatalog: ModelCatalog

    @Provides
    fun provideHttpClient(): HttpClient = HttpClient()
}
