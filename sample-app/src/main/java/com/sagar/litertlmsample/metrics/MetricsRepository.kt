/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.litertlmsample.metrics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Combines CPU + memory + token-rate samples into a single hot flow updated
 * at [SAMPLE_INTERVAL_MS] cadence. Compose collects this and re-renders the
 * metrics overlay.
 */
class MetricsRepository(
    private val cpu: CpuMonitor = CpuMonitor(),
    private val memory: MemoryMonitor = MemoryMonitor(),
    val tokens: TokenRateMonitor = TokenRateMonitor(),
) {
    private val _snapshot = MutableStateFlow(EmptyMetrics)
    val snapshot: StateFlow<MetricsSnapshot> = _snapshot.asStateFlow()

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                val cpuSample = cpu.sample()
                val memSample = memory.sample()
                val tokSample = tokens.snapshot()
                if (cpuSample != null) {
                    _snapshot.value = MetricsSnapshot(cpuSample, memSample, tokSample)
                }
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        const val SAMPLE_INTERVAL_MS = 250L
    }
}
