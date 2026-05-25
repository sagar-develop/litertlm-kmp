/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.litertlmsample.metrics

data class MetricsSnapshot(
    val cpu: CpuSnapshot,
    val memory: MemorySnapshot,
    val tokens: TokenSnapshot,
)

data class CpuSnapshot(
    /** Aggregate CPU usage 0..100. */
    val totalUsagePct: Float,
    /** Per-core usage 0..100 in physical-core order (cpu0..cpuN). */
    val perCoreUsagePct: List<Float>,
)

data class MemorySnapshot(
    /** Total proportional set size MB — RSS-equivalent for this process. */
    val totalPssMb: Int,
    /** JVM/Dalvik heap PSS MB. */
    val dalvikPssMb: Int,
    /** Native heap PSS MB (where LiteRT-LM weights live). */
    val nativePssMb: Int,
)

data class TokenSnapshot(
    /** Rolling tokens-per-second over the last second of generation. */
    val tokensPerSecond: Float,
    /** Cumulative tokens for the in-flight generation; resets on each request. */
    val cumulativeTokens: Int,
    /** Time from request start to first token, ms. -1 until first token arrives. */
    val timeToFirstTokenMs: Long,
    /** True while a generation is in flight. */
    val isGenerating: Boolean,
)

val EmptyMetrics = MetricsSnapshot(
    cpu = CpuSnapshot(0f, emptyList()),
    memory = MemorySnapshot(0, 0, 0),
    tokens = TokenSnapshot(0f, 0, -1, false),
)
