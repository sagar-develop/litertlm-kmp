package com.sagar.aicore

/**
 * Interface for generating vector embeddings from text.
 */
interface EmbeddingEngine {
    /**
     * Initializes the embedding engine with the model.
     */
    suspend fun initialize(modelPath: String)

    /**
     * Converts a string into a float array representing its vector embedding.
     */
    suspend fun embed(text: String): FloatArray
}
