/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * EmbeddingGemma embedder via ONNX Runtime. Deliberately NOT the MediaPipe path —
 * no Google/Play deps, protecting the zero-telemetry stance (ONNX Runtime is
 * Microsoft, telemetry-free). Tokenization is a pure-Kotlin [GemmaBpeTokenizer]
 * (the onnxruntime-extensions in-graph tokenizer doesn't support GemmaTokenizer),
 * so no extra native lib is needed.
 *
 * Output handling: EmbeddingGemma's sentence-transformers ONNX exposes a pooled,
 * L2-normalized `sentence_embedding` (768-dim). We **Matryoshka-truncate** that to
 * [targetDim] then **re-normalize** (truncate first, normalize second). If only
 * token embeddings are exposed, we mean-pool over the attention mask first.
 *
 * @param targetDim Matryoshka output dim (128 / 256 / 512) — must match the HNSW
 *   index the vectors are written to.
 * @param tokenizerFileName the `tokenizer.json` companion file name; resolved in the
 *   same model directory as the model passed to [initialize].
 */
class OnnxEmbeddingEngine(
    private val targetDim: Int,
    private val tokenizerFileName: String,
) : EmbeddingEngine {

    override val dimensions: Int = targetDim

    private val mutex = Mutex()
    private var env: OrtEnvironment? = null
    private var modelSession: OrtSession? = null
    private var tokenizer: GemmaBpeTokenizer? = null

    override suspend fun initialize(modelPath: String) = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (modelSession != null) return@withContext
            val modelFile = File(modelPath)
            val tokFile = File(modelFile.parentFile, tokenizerFileName)
            Napier.d(tag = TAG) { "init model=${modelFile.name}(${modelFile.length()}B) tok=${tokFile.name} dim=$targetDim" }
            if (!modelFile.exists()) throw HardwareFault.ModelNotLoaded("ONNX model missing: $modelPath")
            if (!tokFile.exists()) throw HardwareFault.ModelNotLoaded("Tokenizer missing: ${tokFile.absolutePath}")

            try {
                val environment = OrtEnvironment.getEnvironment()
                val modelOpts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(EMBED_THREADS)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                // External-data (.onnx_data) is resolved relative to the graph file.
                val model = environment.createSession(modelFile.absolutePath, modelOpts)
                val tok = GemmaBpeTokenizer.load(tokFile.absolutePath)

                env = environment
                modelSession = model
                tokenizer = tok
                Napier.i(tag = TAG) { "ONNX embedder ready: inputs=${model.inputNames} outputs=${model.outputNames}" }
            } catch (e: Throwable) {
                Napier.e(tag = TAG, throwable = e) { "Failed to load ONNX embedder" }
                throw HardwareFault.DelegateFailure("Failed to load ONNX embedder: ${e.message}")
            }
        }
    }

    override suspend fun embed(text: String, task: EmbeddingTask, title: String?): FloatArray =
        withContext(Dispatchers.Default) {
            val environment = env
            val model = modelSession
            val tok = tokenizer
            if (environment == null || model == null || tok == null) {
                throw HardwareFault.ModelNotLoaded("ONNX embedder not initialized. Call initialize() first.")
            }
            val prompt = applyTaskPrefix(text, task, title)
            var ids = tok.encode(prompt)
            if (ids.size > MAX_SEQ_LEN) ids = ids.copyOf(MAX_SEQ_LEN)
            val mask = LongArray(ids.size) { 1L }
            mutex.withLock { runModel(environment, model, ids, mask) }
        }

    /** Run the model and produce the final [targetDim] vector. */
    private fun runModel(environment: OrtEnvironment, model: OrtSession, ids: LongArray, mask: LongArray): FloatArray {
        val seq = ids.size
        val shape = longArrayOf(1, seq.toLong())
        val idTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), shape)
        val maskTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(mask), shape)
        val feed = HashMap<String, OnnxTensor>()
        val inputs = model.inputNames
        feed[firstMatch(inputs, listOf("input_ids", "input.1", "ids"))] = idTensor
        val maskName = inputs.firstOrNull { it.contains("mask", ignoreCase = true) }
        if (maskName != null) feed[maskName] = maskTensor
        // Some exports also require position_ids / token_type_ids; supply sensible values.
        val extra = ArrayList<OnnxTensor>()
        for (name in inputs) {
            if (feed.containsKey(name)) continue
            val data = if (name.contains("position", ignoreCase = true)) {
                LongArray(seq) { it.toLong() }
            } else {
                LongArray(seq) { 0L }
            }
            val t = OnnxTensor.createTensor(environment, LongBuffer.wrap(data), shape)
            feed[name] = t
            extra += t
        }

        try {
            model.run(feed).use { res ->
                val pooled = pooledOrNull(res) ?: meanPool(tokenEmbeddings(res), mask)
                return matryoshka(pooled)
            }
        } finally {
            idTensor.close(); maskTensor.close(); extra.forEach { it.close() }
        }
    }

    /** Truncate to [targetDim] (Matryoshka) then L2-normalize. */
    private fun matryoshka(vec: FloatArray): FloatArray {
        val out = if (vec.size > targetDim) vec.copyOf(targetDim) else vec
        var norm = 0.0
        for (v in out) norm += v.toDouble() * v
        val inv = if (norm > 0) (1.0 / sqrt(norm)).toFloat() else 1f
        for (i in out.indices) out[i] = out[i] * inv
        return out
    }

    /** A pooled [dim] or [1, dim] output if the graph exposes one. */
    private fun pooledOrNull(res: OrtSession.Result): FloatArray? {
        val names = listOf("sentence_embedding", "sentence_embeddings", "pooler_output", "embeddings", "embedding")
        for (n in names) {
            val t = res.get(n).orElse(null) as? OnnxTensor ?: continue
            val dims = (t.info as? TensorInfo)?.shape ?: continue
            if (dims.size <= 2) return flatFloats(t) // [dim] or [1, dim]; not token-level [1, seq, dim]
        }
        return null
    }

    /** Token-level [1, seq, hidden] embeddings for mean pooling. */
    private fun tokenEmbeddings(res: OrtSession.Result): Pair<FloatArray, Int> {
        val names = listOf("token_embeddings", "last_hidden_state", "hidden_states", "output")
        for (n in names) {
            val t = res.get(n).orElse(null) as? OnnxTensor ?: continue
            val dims = (t.info as? TensorInfo)?.shape ?: continue
            if (dims.size == 3) return flatFloats(t) to dims[2].toInt()
        }
        for (i in 0 until res.size()) {
            val t = res.get(i) as? OnnxTensor ?: continue
            val dims = (t.info as? TensorInfo)?.shape ?: continue
            if (dims.size == 3) return flatFloats(t) to dims[2].toInt()
        }
        throw HardwareFault.DelegateFailure("ONNX embedder produced no usable embedding output")
    }

    /** Mean-pool [seq, hidden] (row-major) over the attention mask → [hidden]. */
    private fun meanPool(flatAndHidden: Pair<FloatArray, Int>, mask: LongArray): FloatArray {
        val (flat, hidden) = flatAndHidden
        val seq = flat.size / hidden
        val out = FloatArray(hidden)
        var counted = 0
        for (s in 0 until seq) {
            if (s < mask.size && mask[s] == 0L) continue
            val base = s * hidden
            for (h in 0 until hidden) out[h] += flat[base + h]
            counted++
        }
        if (counted > 0) {
            val inv = 1f / counted
            for (h in 0 until hidden) out[h] *= inv
        }
        return out
    }

    private fun applyTaskPrefix(text: String, task: EmbeddingTask, title: String?): String = when (task) {
        // EmbeddingGemma instruction prompts (see model card).
        EmbeddingTask.QUERY -> "task: search result | query: $text"
        EmbeddingTask.DOCUMENT -> "title: ${title?.ifBlank { null } ?: "none"} | text: $text"
    }

    private fun flatFloats(t: OnnxTensor): FloatArray {
        val buf = t.floatBuffer
        if (buf != null) {
            val out = FloatArray(buf.remaining())
            buf.get(out)
            return out
        }
        val out = ArrayList<Float>()
        fun walk(a: Any?) {
            when (a) {
                is FloatArray -> a.forEach { out.add(it) }
                is Array<*> -> a.forEach { walk(it) }
                is Float -> out.add(a)
            }
        }
        walk(t.value)
        return out.toFloatArray()
    }

    private fun firstMatch(names: Set<String>, prefs: List<String>): String {
        for (p in prefs) if (names.contains(p)) return p
        return names.firstOrNull() ?: prefs.first()
    }

    fun release() {
        modelSession?.close(); modelSession = null
        tokenizer = null
        env = null
    }

    companion object {
        private const val TAG = "OnnxEmbeddingEngine"
        private const val MAX_SEQ_LEN = 512
        private const val EMBED_THREADS = 4
    }
}
