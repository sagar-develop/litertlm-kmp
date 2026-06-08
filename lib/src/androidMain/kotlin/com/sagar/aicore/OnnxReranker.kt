/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.sagar.aicore.rag.Reranker
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.LongBuffer

/**
 * Cross-encoder reranker (ms-marco MiniLM-L6) via ONNX Runtime. Scores each
 * query↔passage pair with `[CLS] query [SEP] passage [SEP]`; the model's single
 * logit is the relevance score (higher = more relevant). Flagship-tier only.
 * Telemetry-free (Microsoft ORT); tokenization is pure-Kotlin [BertWordPieceTokenizer].
 *
 * @param tokenizerFileName the tokenizer.json companion, resolved in the same dir
 *   as the model passed to [initialize].
 */
class OnnxReranker(private val tokenizerFileName: String) : Reranker {

    private val mutex = Mutex()
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var tokenizer: BertWordPieceTokenizer? = null

    override suspend fun initialize(modelPath: String) = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (session != null) return@withContext
            val modelFile = File(modelPath)
            val tokFile = File(modelFile.parentFile, tokenizerFileName)
            if (!modelFile.exists()) throw HardwareFault.ModelNotLoaded("Reranker model missing: $modelPath")
            if (!tokFile.exists()) throw HardwareFault.ModelNotLoaded("Reranker tokenizer missing: ${tokFile.absolutePath}")
            val environment = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(THREADS)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            session = environment.createSession(modelFile.absolutePath, opts)
            tokenizer = BertWordPieceTokenizer.load(tokFile.absolutePath)
            env = environment
            Napier.i(tag = TAG) { "Reranker ready: inputs=${session!!.inputNames}" }
        }
    }

    override suspend fun scores(query: String, passages: List<String>): FloatArray =
        withContext(Dispatchers.Default) {
            val environment = env ?: error("Reranker not initialized")
            val s = session ?: error("Reranker not initialized")
            val t = tokenizer ?: error("Reranker not initialized")
            mutex.withLock {
                FloatArray(passages.size) { i -> scoreOne(environment, s, t, query, passages[i]) }
            }
        }

    private fun scoreOne(
        environment: OrtEnvironment,
        s: OrtSession,
        t: BertWordPieceTokenizer,
        query: String,
        passage: String,
    ): Float {
        var enc = t.encodePair(query, passage)
        var ids = enc.ids
        var types = enc.typeIds
        var mask = enc.mask
        if (ids.size > MAX_SEQ_LEN) {
            ids = ids.copyOf(MAX_SEQ_LEN)
            types = types.copyOf(MAX_SEQ_LEN)
            mask = mask.copyOf(MAX_SEQ_LEN)
        }
        val shape = longArrayOf(1, ids.size.toLong())
        val idT = OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), shape)
        val maskT = OnnxTensor.createTensor(environment, LongBuffer.wrap(mask), shape)
        val typeT = OnnxTensor.createTensor(environment, LongBuffer.wrap(types), shape)
        val feed = HashMap<String, OnnxTensor>()
        for (name in s.inputNames) {
            when {
                name.contains("input_ids", true) || name == "input.1" -> feed[name] = idT
                name.contains("attention", true) || name.contains("mask", true) -> feed[name] = maskT
                name.contains("type", true) || name.contains("token_type", true) -> feed[name] = typeT
            }
        }
        if (!feed.values.contains(idT)) feed[s.inputNames.first()] = idT
        try {
            s.run(feed).use { res ->
                val out = res.get(0) as? OnnxTensor ?: return 0f
                val buf = out.floatBuffer
                return if (buf != null && buf.remaining() > 0) buf.get(0) else firstFloat(out.value)
            }
        } finally {
            idT.close(); maskT.close(); typeT.close()
        }
    }

    private fun firstFloat(v: Any?): Float = when (v) {
        is FloatArray -> if (v.isNotEmpty()) v[0] else 0f
        is Array<*> -> firstFloat(v.firstOrNull())
        is Float -> v
        else -> 0f
    }

    fun release() {
        session?.close(); session = null
        tokenizer = null
        env = null
    }

    companion object {
        private const val TAG = "OnnxReranker"
        private const val MAX_SEQ_LEN = 512
        private const val THREADS = 4
    }
}
