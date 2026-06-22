/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.benchmark

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Result schema for the on-device benchmark suite (issue #38). Serialized to JSON
 * (and flattened to CSV) so the numbers are public, citable, and reproducible.
 *
 * All timing is **wall-clock stream timing** measured in the app process:
 *  - TTFT = time from `sendTurn()` to the first token (prefill latency).
 *  - decode tok/s = steady-state rate over the tokens after the first.
 * The engine's own `lastTurnMetrics()` is recorded too when LiteRT-LM populates it
 * (it is null in normal chat), so a reader can compare app-measured vs engine-reported.
 */
@Serializable
data class BenchmarkReport(
    val schemaVersion: Int = SCHEMA_VERSION,
    val appVersionName: String,
    val appVersionCode: Int,
    val device: DeviceInfo,
    val run: RunInfo,
    val config: BenchConfig,
    val models: List<ModelResult>,
    /** RAG pipeline timing (embedding throughput + end-to-end retrieve latency); null if not measured. */
    val rag: RagResult? = null,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/**
 * RAG pipeline performance: how fast the active embedder turns text into vectors, and
 * how long an end-to-end `retrieve()` (embed query + HNSW vector search + optional rerank
 * + format) takes against a freshly-indexed corpus. `error` is set (and the rest left at
 * zero) when no embedder is downloaded.
 */
@Serializable
data class RagResult(
    val embedderId: String,
    val embedderDim: Int,
    val reranker: Boolean,
    /** Texts embedded per second (single-text calls, the pipeline's real path). */
    val embedTextsPerSec: Double,
    /** Characters embedded per second — a length-normalised throughput view. */
    val embedCharsPerSec: Double,
    val embedSampleCount: Int,
    /** Chunks the benchmark corpus indexed into the vector store. */
    val indexedChunks: Int,
    /** End-to-end retrieve() latency, ms. */
    val retrieveMsMedian: Double,
    val retrieveMsP90: Double,
    val retrieveRuns: List<Double>,
    val error: String? = null,
)

@Serializable
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val soc: String,
    val androidRelease: String,
    val sdkInt: Int,
    /** Kernel MemTotal (ActivityManager.totalMem), MB. */
    val totalRamMb: Long,
    /** RAM-expansion-corrected RAM from HardwareProvider (drives the model tier), MB. */
    val effectiveRamMb: Long,
    val cores: Int,
    val abi: String,
)

@Serializable
data class RunInfo(
    val startedAtEpochMs: Long,
    val startedAtIso: String,
    val batteryPct: Int,
    val charging: Boolean,
    val thermalStatus: String,
)

@Serializable
data class BenchConfig(
    val warmups: Int,
    val repeats: Int,
    val maxTokens: Int,
    val temperature: Float,
    val seed: Long,
)

@Serializable
data class ModelResult(
    val modelId: String,
    val displayName: String,
    val fileName: String,
    val sizeBytes: Long,
    val format: String,
    val supportsVision: Boolean,
    /** Cold load time: release + initializeEngine, ms. */
    val loadTimeMs: Long,
    /** Process PSS right after the model loaded, MB. */
    val loadedPssMb: Int,
    /** Peak process PSS observed during load + all inference, MB. */
    val peakPssMb: Int,
    /** True when at least one run got engine-reported `lastTurnMetrics()`. */
    val engineReported: Boolean,
    val prompts: List<PromptResult>,
    /** Non-null when this model failed to load or run; its prompts list is then empty. */
    val error: String? = null,
)

@Serializable
data class PromptResult(
    val name: String,
    val promptChars: Int,
    val ttftMsMedian: Double,
    val ttftMsP90: Double,
    val decodeTpsMedian: Double,
    val decodeTpsP90: Double,
    val tokensMean: Double,
    val runs: List<RunMetric>,
)

@Serializable
data class RunMetric(
    val ttftMs: Double,
    val decodeTps: Double,
    val decodeTokens: Int,
    val totalMs: Double,
    // Engine-reported (LiteRT-LM getBenchmarkInfo); null when unavailable.
    val engineTtftSec: Double? = null,
    val enginePrefillTps: Double? = null,
    val engineDecodeTps: Double? = null,
    val enginePrefillTokens: Int? = null,
    val engineDecodeTokens: Int? = null,
)

/** One canonical benchmark prompt. The three cover different axes (issue §methodology). */
data class BenchPrompt(val name: String, val text: String)

/**
 * The fixed canonical prompt set. `short` probes minimum-prefill TTFT; `general`
 * is a normal instruction for steady-state decode; `long_context` carries a ~600-word
 * passage to show how prefill (TTFT) scales with input length. Text is original (no
 * copyright) so the set is freely reproducible.
 */
object BenchmarkPrompts {
    val PASSAGE = """
        On-device language models move inference from a remote data centre onto the phone or tablet
        in the user's hand. The shift changes the engineering problem in three ways. First, memory
        is scarce and shared: a handset must hold the operating system, the foreground app, and the
        model weights at once, so a model that needs four gigabytes of resident memory simply will
        not load on a device with six gigabytes of total RAM once the system's own footprint is
        subtracted. Quantisation — storing weights as four- or eight-bit integers rather than
        sixteen-bit floats — is therefore not an optimisation but a precondition. Second, the
        compute budget is thermal rather than monetary. A server can draw three hundred watts and
        spin its fans; a phone throttles its cores within a minute of sustained load to keep the
        chassis comfortable to hold, so the throughput a benchmark reports in its first ten seconds
        can be double what the same device sustains over ten minutes. Honest numbers report both the
        burst and the sustained rate. Third, latency is dominated by two distinct phases. Prefill
        reads the entire prompt and builds the key-value cache; its cost grows with the length of the
        input and is paid before the first token appears, which is why time-to-first-token rises
        sharply on long documents. Decode then emits one token at a time, reusing the cache, at a
        rate that is roughly constant for a given model and device and is the number users feel as
        "typing speed". A retrieval-augmented system adds a fourth cost: before generation it embeds
        the query, searches a vector index of the user's own documents, and folds the best passages
        into the prompt, trading a little latency for answers grounded in private data that never
        leaves the device. Measuring these phases separately — load time, prefill latency, decode
        throughput, peak memory, and sustained thermal behaviour — is what turns a vague impression
        of speed into a reproducible claim that a third party can check on their own hardware.
    """.trimIndent()

    val ALL: List<BenchPrompt> = listOf(
        BenchPrompt("short", "Reply with exactly one word: what is the capital of France?"),
        BenchPrompt(
            "general",
            "Explain how a lithium-ion battery stores and releases energy. Keep it under 120 words.",
        ),
        BenchPrompt(
            "long_context",
            "$PASSAGE\n\nSummarize the passage above in exactly three sentences.",
        ),
    )
}

/**
 * Inputs for the RAG benchmark. [EMBED_TEXTS] are varied-length sentences embedded to
 * measure throughput; [CORPUS] is indexed into a temp project and [QUERY] retrieved
 * against it to measure end-to-end latency.
 */
object RagBenchmark {
    val EMBED_TEXTS: List<String> = listOf(
        "Paris is the capital of France.",
        "Quantisation stores model weights as low-bit integers to fit on a phone.",
        "Time to first token grows with the length of the prompt.",
        "Decode throughput is roughly constant for a given model and device.",
        "A vector index lets the app find the passages most similar to a question.",
        "On-device inference keeps private documents on the user's hardware.",
        "Thermal throttling lowers sustained speed after a minute of heavy load.",
        "Retrieval-augmented generation grounds answers in the user's own files.",
        "The key-value cache is reused across decode steps to avoid recomputation.",
        "Embedding turns a sentence into a fixed-length list of numbers.",
        "Cosine similarity ranks how close two embedding vectors point.",
        "A cross-encoder reranker re-scores the top candidates for precision.",
        "Cold load time is how long the model takes to become ready in memory.",
        "Peak memory must stay within the device's RAM budget beside the OS.",
        "Prefill reads the whole prompt; decode emits one token at a time.",
        "Smaller models are not always faster on a given runtime.",
    )

    /** Indexed corpus = the same passage the long-context prompt uses. */
    val CORPUS: String get() = BenchmarkPrompts.PASSAGE

    const val QUERY = "How does time to first token change with the length of the input prompt?"
}

/** Median of a non-empty list; 0.0 for an empty list. */
fun median(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    val s = values.sorted()
    val mid = s.size / 2
    return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2.0
}

/** Nearest-rank p90 of a non-empty list; 0.0 for an empty list. */
fun p90(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    val s = values.sorted()
    // Nearest-rank: ceil(0.9 * n) clamped into [1, n], then 1-based → 0-based.
    val rank = Math.ceil(0.9 * s.size).toInt().coerceIn(1, s.size)
    return s[rank - 1]
}

private val benchmarkJson = Json { prettyPrint = true; encodeDefaults = true }

/** Pretty-printed JSON for the report file. */
fun BenchmarkReport.toJson(): String = benchmarkJson.encodeToString(this)

/**
 * Flatten the report to one CSV row per (model, prompt). Columns chosen to drop
 * straight into a spreadsheet or the README "Performance" table.
 */
fun BenchmarkReport.toCsv(): String {
    val sb = StringBuilder()
    sb.append(
        "device,soc,android,model,size_mb,load_ms,peak_pss_mb,prompt,prompt_chars," +
            "ttft_ms_median,ttft_ms_p90,decode_tps_median,decode_tps_p90,tokens_mean,engine_reported\n",
    )
    val dev = "${device.manufacturer} ${device.model}".csv()
    for (m in models) {
        val sizeMb = m.sizeBytes / 1_048_576L
        if (m.error != null || m.prompts.isEmpty()) {
            sb.append(
                "$dev,${device.soc.csv()},${device.androidRelease},${m.displayName.csv()},$sizeMb," +
                    "${m.loadTimeMs},${m.peakPssMb},ERROR,0,0,0,0,0,0,${m.error?.csv() ?: ""}\n",
            )
            continue
        }
        for (p in m.prompts) {
            sb.append(
                "$dev,${device.soc.csv()},${device.androidRelease},${m.displayName.csv()},$sizeMb," +
                    "${m.loadTimeMs},${m.peakPssMb},${p.name},${p.promptChars}," +
                    "${p.ttftMsMedian.r(1)},${p.ttftMsP90.r(1)}," +
                    "${p.decodeTpsMedian.r(2)},${p.decodeTpsP90.r(2)},${p.tokensMean.r(1)}," +
                    "${m.engineReported}\n",
            )
        }
    }
    rag?.let { r ->
        sb.append("\n# RAG\nembedder,dim,reranker,embed_texts_per_sec,embed_chars_per_sec,indexed_chunks,retrieve_ms_median,retrieve_ms_p90,error\n")
        sb.append(
            "${r.embedderId.csv()},${r.embedderDim},${r.reranker},${r.embedTextsPerSec.r(2)}," +
                "${r.embedCharsPerSec.r(1)},${r.indexedChunks},${r.retrieveMsMedian.r(1)}," +
                "${r.retrieveMsP90.r(1)},${r.error?.csv() ?: ""}\n",
        )
    }
    return sb.toString()
}

private fun Double.r(digits: Int): String = "%.${digits}f".format(this)

/** Minimal CSV escaping: wrap in quotes and double any embedded quotes if needed. */
private fun String.csv(): String =
    if (any { it == ',' || it == '"' || it == '\n' }) "\"${replace("\"", "\"\"")}\"" else this
