/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.benchmark
import io.github.aakira.napier.Napier

/** One backend's micro-benchmark numbers, straight from LiteRT-LM's BenchmarkInfo. */
data class BackendBenchmarkResult(
    val backend: String,
    val supported: Boolean,
    val initSec: Double = 0.0,
    val ttftSec: Double = 0.0,
    val prefillTps: Double = 0.0,
    val decodeTps: Double = 0.0,
    val prefillTokens: Int = 0,
    val decodeTokens: Int = 0,
    val error: String? = null,
)

/**
 * One-shot backend micro-benchmark over a `.litertlm` model. Loads the model
 * under each candidate [Backend] (CPU / GPU / NPU), runs a synthetic
 * prefill + decode, and reports LiteRT-LM's own [com.google.ai.edge.litertlm.BenchmarkInfo].
 *
 * Used to pick `EngineConfig.backend` per device. Each candidate loads the full
 * model, so call this only when the main [LocalAiEngine] is NOT resident
 * (otherwise two copies of the weights are in memory at once). Unsupported
 * backends on a given device are reported with `supported = false` rather than
 * throwing.
 */
object LiteRtLmBackendBenchmark {
    private const val TAG = "BackendBench"

    @OptIn(ExperimentalApi::class)
    fun run(
        modelPath: String,
        cacheDir: String,
        prefillTokens: Int = 128,
        decodeTokens: Int = 64,
    ): List<BackendBenchmarkResult> {
        val candidates: List<Pair<String, Backend>> = listOf(
            "CPU" to Backend.CPU(),
            "GPU" to Backend.GPU(),
            "NPU" to Backend.NPU(),
        )
        return candidates.map { (label, backend) ->
            try {
                Napier.i(tag = TAG) { "Benchmarking $label backend…" }
                val info = benchmark(modelPath, backend, prefillTokens, decodeTokens, cacheDir)
                BackendBenchmarkResult(
                    backend = label,
                    supported = true,
                    initSec = info.initTimeInSecond,
                    ttftSec = info.timeToFirstTokenInSecond,
                    prefillTps = info.lastPrefillTokensPerSecond,
                    decodeTps = info.lastDecodeTokensPerSecond,
                    prefillTokens = info.lastPrefillTokenCount,
                    decodeTokens = info.lastDecodeTokenCount,
                ).also { Napier.i(tag = TAG) { "$label → $it" } }
            } catch (t: Throwable) {
                Napier.w(t, tag = TAG) { "$label backend unavailable" }
                BackendBenchmarkResult(backend = label, supported = false, error = t.message)
            }
        }
    }
}
