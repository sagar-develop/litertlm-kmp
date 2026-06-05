/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * On-device [EmbeddingEngine] for **EmbeddingGemma 300M** via ONNX Runtime.
 *
 * Pipeline per call: apply the task-specific instruction prefix → tokenize →
 * run the ONNX graph → mean-pool the token embeddings over the attention mask
 * (unless the graph already emits a pooled `sentence_embedding`) → **truncate to
 * [outputDim]** (Matryoshka) → **L2-normalize**. Everything stays on-device.
 *
 * The instruction prefixes are mandatory for EmbeddingGemma retrieval quality:
 *   - query   → `task: search result | query: {text}`
 *   - document→ `title: {title or "none"} | text: {text}`
 *
 * The [tokenizer] is created from the `tokenizer.json` companion downloaded next
 * to the model. Construct one instance with an Application-scoped lifetime.
 */
class OnnxEmbeddingEngine(
    /** Builds the tokenizer from the companion path resolved at [initialize] time. */
    private val tokenizerFactory: (modelDir: String) -> GemmaTokenizer,
    /** Matryoshka output dimension; must match the vector store's index dimension. */
    override val dimensions: Int = 256,
    private val tokenizerFileName: String = "tokenizer.json",
) : EmbeddingEngine {

    private val outputDim: Int get() = dimensions

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var tokenizer: GemmaTokenizer? = null
    private val mutex = Mutex()

    override suspend fun initialize(modelPath: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (session != null) return@withContext
            val modelFile = File(modelPath)
            require(modelFile.exists()) { "ONNX model not found: $modelPath" }
            try {
                val environment = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceAtMost(4))
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    // XNNPACK gives a solid CPU speedup; fall back silently if unavailable.
                    runCatching { addXnnpack(mapOf()) }
                }
                env = environment
                session = environment.createSession(modelPath, opts)
                tokenizer = tokenizerFactory(modelFile.parentFile?.absolutePath ?: ".")
                Napier.d(tag = TAG) { "EmbeddingGemma ONNX session ready (dim=$outputDim)" }
            } catch (e: Exception) {
                Napier.e(tag = TAG, throwable = e) { "Failed to load ONNX embedder" }
                throw HardwareFault.DelegateFailure("Failed to load ONNX embedder from $modelPath: ${e.message}")
            }
        }
    }

    override suspend fun embed(
        text: String,
        task: EmbeddingTask,
        title: String?,
    ): FloatArray = withContext(Dispatchers.IO) {
        val ortSession = session ?: throw HardwareFault.ModelNotLoaded("ONNX embedder not loaded. Call initialize() first.")
        val tok = tokenizer ?: throw HardwareFault.ModelNotLoaded("Tokenizer not loaded.")
        val environment = env ?: throw HardwareFault.ModelNotLoaded("ORT environment not loaded.")

        val prompt = instruction(text, task, title)
        val encoded = tok.encode(prompt)
        val seqLen = encoded.length
        val shape = longArrayOf(1, seqLen.toLong())

        val idsTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(encoded.ids), shape)
        val maskTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(encoded.attentionMask), shape)
        try {
            val inputs = HashMap<String, OnnxTensor>(2).apply {
                put("input_ids", idsTensor)
                put("attention_mask", maskTensor)
            }
            ortSession.run(inputs).use { result ->
                val pooled = pool(result, encoded.attentionMask)
                matryoshkaNormalize(pooled)
            }
        } finally {
            idsTensor.close()
            maskTensor.close()
        }
    }

    /** Prefer a pre-pooled `sentence_embedding`; otherwise mean-pool token embeddings. */
    private fun pool(result: OrtSession.Result, mask: LongArray): FloatArray {
        // Some sentence-transformers ONNX exports emit a pooled output directly.
        result.get("sentence_embedding").orElse(null)?.let { out ->
            val arr = (out as OnnxTensor).floatBuffer
            val v = FloatArray(arr.remaining())
            arr.get(v)
            return v
        }
        // Otherwise mean-pool last_hidden_state / token_embeddings: [1, seq, hidden].
        val tokenOut = (result.get("last_hidden_state").orElse(null)
            ?: result.get("token_embeddings").orElse(null)
            ?: result.get(0)) as OnnxTensor
        @Suppress("UNCHECKED_CAST")
        val hidden = (tokenOut.value as Array<Array<FloatArray>>)[0] // [seq][hidden]
        val dim = hidden.first().size
        val sum = FloatArray(dim)
        var count = 0f
        for (t in hidden.indices) {
            if (mask[t] == 0L) continue
            val row = hidden[t]
            for (d in 0 until dim) sum[d] += row[d]
            count += 1f
        }
        if (count > 0f) for (d in 0 until dim) sum[d] /= count
        return sum
    }

    /** Matryoshka: truncate to [outputDim] first, then L2-normalize. */
    private fun matryoshkaNormalize(full: FloatArray): FloatArray {
        val n = minOf(outputDim, full.size)
        val v = full.copyOf(n)
        var norm = 0.0
        for (x in v) norm += (x * x).toDouble()
        norm = sqrt(norm)
        if (norm > 0.0) for (i in v.indices) v[i] = (v[i] / norm).toFloat()
        return v
    }

    private fun instruction(text: String, task: EmbeddingTask, title: String?): String = when (task) {
        EmbeddingTask.QUERY -> "task: search result | query: $text"
        EmbeddingTask.DOCUMENT -> "title: ${title?.takeIf { it.isNotBlank() } ?: "none"} | text: $text"
    }

    fun close() {
        runCatching { session?.close() }
        session = null
    }

    private companion object {
        const val TAG = "OnnxEmbeddingEngine"
    }
}
