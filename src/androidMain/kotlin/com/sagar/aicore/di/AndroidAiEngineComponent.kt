package com.sagar.aicore.di

import com.sagar.aicore.AndroidHardwareProvider
import com.sagar.aicore.AndroidNetworkProvider
import com.sagar.aicore.AndroidPlatformFolders
import com.sagar.aicore.AndroidSpeechRecognizer
import com.sagar.aicore.DefaultEngineRegistry
import com.sagar.aicore.SpeechRecognizer
import com.sagar.aicore.EmbeddingEngine
import com.sagar.aicore.EngineRegistry
import com.sagar.aicore.HardwareProvider
import com.sagar.aicore.InMemoryModelCatalog
import com.sagar.aicore.ModelCatalog
import com.sagar.aicore.NetworkProvider
import com.sagar.aicore.KtorModelManager
import com.sagar.aicore.LocalAiEngine
import com.sagar.aicore.MediaPipeEmbeddingEngine
import com.sagar.aicore.ModelManager
import com.sagar.aicore.PlatformFolders
import me.tatarka.inject.annotations.Provides

interface AndroidAiEngineComponent : AiEngineComponent {
    // Production orchestrator + agents inject EngineRegistry directly and
    // read the active engine via `registry.active()`. This binding stays for
    // any future consumer that injects LocalAiEngine — they get the
    // currently-active engine selected by the registry's RAM-tier + on-disk
    // policy.
    @Provides
    fun provideLocalAiEngine(registry: EngineRegistry): LocalAiEngine = registry.active()

    @Provides
    fun provideEngineRegistry(impl: DefaultEngineRegistry): EngineRegistry = impl

    @Provides
    fun provideModelCatalog(impl: InMemoryModelCatalog): ModelCatalog = impl

    @Provides
    fun provideEmbeddingEngine(impl: MediaPipeEmbeddingEngine): EmbeddingEngine = impl

    @Provides
    fun provideModelManager(impl: KtorModelManager): ModelManager = impl

    @Provides
    fun providePlatformFolders(impl: AndroidPlatformFolders): PlatformFolders = impl

    @Provides
    fun provideHardwareProvider(impl: AndroidHardwareProvider): HardwareProvider = impl

    @Provides
    fun provideNetworkProvider(impl: AndroidNetworkProvider): NetworkProvider = impl

    @Provides
    fun provideSpeechRecognizer(impl: AndroidSpeechRecognizer): SpeechRecognizer = impl
}
