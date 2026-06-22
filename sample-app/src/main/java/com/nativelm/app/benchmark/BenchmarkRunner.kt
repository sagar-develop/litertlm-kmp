/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.benchmark

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.nativelm.app.llm.EngineHolder
import com.nativelm.app.metrics.MemoryMonitor
import com.sagar.aicore.AiEngineRequest
import com.sagar.aicore.ChatSession
import com.sagar.aicore.EngineState
import com.sagar.aicore.ModelCatalog
import com.sagar.aicore.ModelDescriptor
import com.sagar.aicore.ModelRole
import com.sagar.aicore.SessionState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** State the Benchmark screen renders; driven by [NativeLmViewModel]. */
sealed interface BenchmarkUiState {
    data object Idle : BenchmarkUiState
    data class Running(
        val label: String,
        val modelIndex: Int,
        val modelCount: Int,
        val liveTps: Float,
    ) : BenchmarkUiState
    data class Done(val report: BenchmarkReport) : BenchmarkUiState
    data class Failed(val message: String) : BenchmarkUiState
}

private const val SYS_INSTRUCTION = "You are a helpful assistant. Answer concisely."

/**
 * Runs the on-device benchmark matrix (issue #38). Loads each selected LLM into the
 * real LiteRT-LM engine, times prefill (TTFT) and steady-state decode via wall-clock
 * stream timing, samples peak process PSS, and records engine-reported metrics when
 * available. Pure logic — no Compose; progress is surfaced via [onProgress].
 *
 * The caller (ViewModel) must restore the user's active model afterward, since this
 * releases and reloads the engine repeatedly.
 *
 * IMPORTANT: peak PSS is read via [currentPssMb] — a cheap cached read of the app's
 * already-running MetricsRepository (sampled on its own coroutine). We must NOT call
 * Debug.getMemoryInfo() (an expensive /proc/self/smaps walk) inside the token loop:
 * doing so serializes with token streaming and would crush the measured decode rate.
 */
class BenchmarkRunner(
    private val context: Context,
    private val engineHolder: EngineHolder,
    private val catalog: ModelCatalog,
    private val nameOf: (ModelDescriptor) -> String,
    /** RAM-expansion-corrected RAM (HardwareProvider), MB — fixed for the process. */
    private val effectiveRamMb: Long,
    /** Latest process PSS (MB) from the running MetricsRepository — O(1), no smaps walk. */
    private val currentPssMb: () -> Int,
) {

    private val mem = MemoryMonitor()

    suspend fun run(
        modelIds: List<String>,
        config: BenchConfig,
        appVersionName: String,
        appVersionCode: Int,
        onProgress: (label: String, modelIndex: Int, modelCount: Int, liveTps: Float) -> Unit,
        /** Optional RAG measurement (embedding throughput + retrieve latency), supplied by
         *  the ViewModel since it owns the RAG holder + project store. Runs after the models. */
        ragProbe: (suspend () -> RagResult?)? = null,
    ): BenchmarkReport {
        val descriptors = modelIds
            .mapNotNull { catalog.byId(it) }
            .filter { it.role == ModelRole.LLM_PRIMARY }

        val results = mutableListOf<ModelResult>()
        descriptors.forEachIndexed { index, d ->
            currentCoroutineContext().ensureActive()
            onProgress("Loading ${nameOf(d)}…", index, descriptors.size, 0f)
            results += benchmarkModel(d, index, descriptors.size, config, onProgress)
        }

        // RAG runs on the separate embedding engine, after the LLM model loop.
        currentCoroutineContext().ensureActive()
        val rag = ragProbe?.invoke()

        return BenchmarkReport(
            appVersionName = appVersionName,
            appVersionCode = appVersionCode,
            device = readDeviceInfo(),
            run = readRunInfo(),
            config = config,
            models = results,
            rag = rag,
        )
    }

    private suspend fun benchmarkModel(
        d: ModelDescriptor,
        index: Int,
        count: Int,
        config: BenchConfig,
        onProgress: (String, Int, Int, Float) -> Unit,
    ): ModelResult {
        val name = nameOf(d)

        // 1. Cold load: release any resident model, then time initializeEngine.
        engineHolder.release()
        val loadStart = SystemClock.elapsedRealtime()
        val loadState = engineHolder.initializeEngine(engineHolder.modelPath(d.fileName), d.supportsVision)
        val loadMs = SystemClock.elapsedRealtime() - loadStart
        if (loadState is EngineState.Error) {
            return failedModel(d, loadMs, loadState.fault.message ?: "Engine failed to load")
        }

        var peakPss = mem.sample().totalPssMb
        val loadedPss = peakPss

        var engineReportedAny = false
        val promptResults = mutableListOf<PromptResult>()
        // A FRESH session per prompt: each prompt is measured against a clean KV cache,
        // so a long prompt's repeats don't accumulate context (or overflow the window)
        // and the reasoning model's bounded <think> output doesn't carry across prompts.
        for (prompt in BenchmarkPrompts.ALL) {
            currentCoroutineContext().ensureActive()
            val session = engineHolder.openChatSession(emptyList(), SYS_INSTRUCTION, config.temperature)
            val ready = runCatching {
                session.state.first { it is SessionState.Ready || it is SessionState.Failed }
            }.getOrNull()
            if (ready is SessionState.Failed) {
                session.close()
                return failedModel(d, loadMs, ready.fault.message ?: "Session failed to warm")
            }
            try {
                // Warm-up runs are discarded (first run pays one-time JIT / cache costs).
                repeat(config.warmups) {
                    onProgress("$name · ${prompt.name} · warm-up", index, count, 0f)
                    runOne(session, prompt, config, onPeak = { peakPss = maxOf(peakPss, it) }, onLiveTps = {})
                }
                val runs = mutableListOf<RunMetric>()
                for (r in 1..config.repeats) {
                    currentCoroutineContext().ensureActive()
                    val metric = runOne(
                        session, prompt, config,
                        onPeak = { peakPss = maxOf(peakPss, it) },
                        onLiveTps = { onProgress("$name · ${prompt.name} · run $r/${config.repeats}", index, count, it) },
                    )
                    if (metric.engineDecodeTps != null) engineReportedAny = true
                    runs += metric
                }
                promptResults += aggregate(prompt, runs)
            } finally {
                session.close()
            }
        }

        return ModelResult(
            modelId = d.id,
            displayName = name,
            fileName = d.fileName,
            sizeBytes = d.sizeBytes,
            format = d.format.name,
            supportsVision = d.supportsVision,
            loadTimeMs = loadMs,
            loadedPssMb = loadedPss,
            peakPssMb = peakPss,
            engineReported = engineReportedAny,
            prompts = promptResults,
        )
    }

    /** One measured generation. TTFT and decode tok/s come from wall-clock token timing. */
    private suspend fun runOne(
        session: ChatSession,
        prompt: BenchPrompt,
        config: BenchConfig,
        onPeak: (Int) -> Unit,
        onLiveTps: (Float) -> Unit,
    ): RunMetric {
        val request = AiEngineRequest(
            formattedPrompt = prompt.text,
            temperature = config.temperature,
            maxTokens = config.maxTokens,
            seed = config.seed,
        )

        val tStart = System.nanoTime()
        var tFirst = -1L
        var tLast = tStart
        var tokens = 0
        var bounded = false

        session.sendTurn(request).collect { state ->
            when (state) {
                is EngineState.TokenGenerated<*> -> {
                    val now = System.nanoTime()
                    if (tFirst < 0L) tFirst = now
                    tLast = now
                    tokens++
                    // Live decode rate for the progress bar (after the first token).
                    if (tFirst > 0L && now > tFirst) {
                        onLiveTps(tokens * 1_000_000_000f / (now - tFirst))
                    }
                    // Cheap cached read (no smaps walk) — must stay off the timing path.
                    onPeak(currentPssMb())
                    // The engine ignores AiEngineRequest.maxTokens, so bound generation
                    // here: a fixed token budget is enough for a stable decode rate, and
                    // it tames reasoning models that otherwise ramble for thousands of
                    // tokens. cancel() stops the native decode → engine emits Idle → closes.
                    if (!bounded && tokens >= config.maxTokens) {
                        bounded = true
                        session.cancel()
                    }
                }
                // A cancel WE initiated surfaces as an Error("Process cancelled") — that's a
                // clean, intentional stop, not a failure. Only a genuine (un-bounded) error throws.
                is EngineState.Error -> if (!bounded) throw state.fault
                EngineState.Idle -> Unit // stream complete
                else -> Unit
            }
        }

        val ttftMs = if (tFirst > 0L) (tFirst - tStart) / 1_000_000.0 else 0.0
        val decodeMs = if (tFirst > 0L) (tLast - tFirst) / 1_000_000.0 else 0.0
        // Steady-state decode: the (tokens-1) intervals between the first and last token.
        val decodeTps = if (tokens > 1 && decodeMs > 0.0) (tokens - 1) * 1000.0 / decodeMs else 0.0
        val totalMs = (tLast - tStart) / 1_000_000.0

        val engine = runCatching { session.lastTurnMetrics() }.getOrNull()
        return RunMetric(
            ttftMs = ttftMs,
            decodeTps = decodeTps,
            decodeTokens = tokens,
            totalMs = totalMs,
            engineTtftSec = engine?.timeToFirstTokenSec,
            enginePrefillTps = engine?.prefillTokensPerSecond,
            engineDecodeTps = engine?.decodeTokensPerSecond,
            enginePrefillTokens = engine?.prefillTokenCount,
            engineDecodeTokens = engine?.decodeTokenCount,
        )
    }

    private fun aggregate(prompt: BenchPrompt, runs: List<RunMetric>): PromptResult {
        val ttfts = runs.map { it.ttftMs }
        // Only count decode rate from runs that actually decoded (the short prompt may
        // emit a single token, which has no steady-state rate).
        val decodeRates = runs.filter { it.decodeTokens > 1 }.map { it.decodeTps }
        return PromptResult(
            name = prompt.name,
            promptChars = prompt.text.length,
            ttftMsMedian = median(ttfts),
            ttftMsP90 = p90(ttfts),
            decodeTpsMedian = median(decodeRates),
            decodeTpsP90 = p90(decodeRates),
            tokensMean = if (runs.isEmpty()) 0.0 else runs.sumOf { it.decodeTokens }.toDouble() / runs.size,
            runs = runs,
        )
    }

    private fun failedModel(d: ModelDescriptor, loadMs: Long, message: String) = ModelResult(
        modelId = d.id,
        displayName = nameOf(d),
        fileName = d.fileName,
        sizeBytes = d.sizeBytes,
        format = d.format.name,
        supportsVision = d.supportsVision,
        loadTimeMs = loadMs,
        loadedPssMb = 0,
        peakPssMb = 0,
        engineReported = false,
        prompts = emptyList(),
        error = message,
    )

    private fun readDeviceInfo(): DeviceInfo {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL)
                .filter { it.isNotBlank() && it != Build.UNKNOWN }
                .joinToString(" ")
                .ifBlank { Build.HARDWARE }
        } else {
            Build.HARDWARE
        }
        return DeviceInfo(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            soc = soc,
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            totalRamMb = mi.totalMem / 1_048_576L,
            effectiveRamMb = effectiveRamMb,
            cores = Runtime.getRuntime().availableProcessors(),
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
        )
    }

    private fun readRunInfo(): RunInfo {
        val now = System.currentTimeMillis()
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalLabel(pm.currentThermalStatus)
        } else {
            "unknown"
        }
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date(now))
        return RunInfo(
            startedAtEpochMs = now,
            startedAtIso = iso,
            batteryPct = pct,
            charging = bm.isCharging,
            thermalStatus = thermal,
        )
    }

    private fun thermalLabel(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> "unknown"
    }
}
