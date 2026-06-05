/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore

import android.content.Context
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import com.sagar.aicore.di.AppScope
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import java.io.File

/**
 * Android implementation of EmbeddingEngine using MediaPipe TextEmbedder.
 */
@AppScope
@Inject
class MediaPipeEmbeddingEngine(
    private val context: Context
) : EmbeddingEngine {

    init {
        Napier.d(tag = "EmbeddingEngine") { "INSTANCE_CREATED hash=${System.identityHashCode(this)}" }
    }

    private var textEmbedder: TextEmbedder? = null
    private val mutex = Mutex()

    /** USE-Lite is a fixed 100-dim embedder; [task]/title are ignored (it is symmetric). */
    override val dimensions: Int = 100

    /**
     * Initializes the embedder with a model path.
     */
    override suspend fun initialize(modelPath: String) = withContext(Dispatchers.IO) {
        Napier.d(tag = "EmbeddingEngine") { "initialize START hash=${System.identityHashCode(this@MediaPipeEmbeddingEngine)} modelPath=$modelPath" }
        mutex.withLock {
            if (textEmbedder != null) {
                Napier.d(tag = "EmbeddingEngine") { "Already initialized, skipping" }
                return@withContext
            }
            
            val modelFile = File(modelPath)
            Napier.d(tag = "EmbeddingEngine") { "Model file exists: ${modelFile.exists()}, size: ${modelFile.length()} bytes" }
            
            try {
                Napier.d(tag = "EmbeddingEngine") { "Creating TextEmbedder from file..." }
                textEmbedder = TextEmbedder.createFromFile(context, modelFile)
                Napier.d(tag = "EmbeddingEngine") { "TextEmbedder created successfully" }
            } catch (e: Exception) {
                Napier.e(tag = "EmbeddingEngine", throwable = e) { "Failed to load embedding model" }
                throw HardwareFault.DelegateFailure("Failed to load embedding model from $modelPath: ${e.message}")
            }
        }
    }

    override suspend fun embed(
        text: String,
        task: EmbeddingTask,
        title: String?,
    ): FloatArray = withContext(Dispatchers.IO) {
        Napier.d(tag = "EmbeddingEngine") { "embed START hash=${System.identityHashCode(this@MediaPipeEmbeddingEngine)} text_len=${text.length} first50=${text.take(50)}" }
        val embedder = mutex.withLock { textEmbedder } ?: run {
            Napier.e(tag = "EmbeddingEngine") { "Embedding model not loaded! hash=${System.identityHashCode(this@MediaPipeEmbeddingEngine)}" }
            throw HardwareFault.ModelNotLoaded(
                "Embedding model not loaded. Call initialize() first."
            )
        }
        
        try {
            val result = embedder.embed(text)
            val embeddings = result.embeddingResult().embeddings()
            Napier.d(tag = "EmbeddingEngine") { "Embed result: ${embeddings.size} embedding(s)" }
            val floatEmbedding = embeddings.first().floatEmbedding()
            Napier.d(tag = "EmbeddingEngine") { "embed COMPLETE: vector size=${floatEmbedding.size}" }
            floatEmbedding
        } catch (e: Exception) {
            Napier.e(tag = "EmbeddingEngine", throwable = e) { "Embedding execution failed" }
            throw HardwareFault.DelegateFailure("Embedding execution failed: ${e.message}")
        }
    }
}
