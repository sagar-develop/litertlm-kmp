/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.litertlmsample.metrics

import android.os.SystemClock
import java.util.ArrayDeque

/**
 * Tracks LLM generation throughput. Call [requestStarted] at request kickoff,
 * [tokenReceived] on each EngineState.TokenGenerated, [requestEnded] when the
 * stream completes or fails.
 *
 * Reports rolling tokens-per-second over the last [WINDOW_MS] of activity.
 */
class TokenRateMonitor {

    private var requestStartMs: Long = 0L
    private var firstTokenMs: Long = -1L
    private var cumulative: Int = 0
    private var isGenerating: Boolean = false

    // Window of (timestamp, tokenCount) entries; we count tokens within the
    // last WINDOW_MS to compute rolling rate.
    private val window: ArrayDeque<Pair<Long, Int>> = ArrayDeque()

    fun requestStarted() {
        requestStartMs = SystemClock.elapsedRealtime()
        firstTokenMs = -1L
        cumulative = 0
        isGenerating = true
        window.clear()
    }

    /** Call for each token (or string delta) received from the engine. */
    fun tokenReceived(approximateTokens: Int = 1) {
        val now = SystemClock.elapsedRealtime()
        if (firstTokenMs < 0L) firstTokenMs = now - requestStartMs
        cumulative += approximateTokens
        window.addLast(now to approximateTokens)
        // Evict entries older than the window.
        while (window.isNotEmpty() && now - window.first.first > WINDOW_MS) {
            window.removeFirst()
        }
    }

    fun requestEnded() {
        isGenerating = false
    }

    fun snapshot(): TokenSnapshot {
        val now = SystemClock.elapsedRealtime()
        // Evict stale entries before reading rate.
        while (window.isNotEmpty() && now - window.first.first > WINDOW_MS) {
            window.removeFirst()
        }
        val tokensInWindow = window.sumOf { it.second }
        val rate = if (tokensInWindow == 0) 0f else {
            val windowSpanMs = (now - (window.firstOrNull()?.first ?: now))
                .coerceAtLeast(1L)
            (tokensInWindow * 1000f / windowSpanMs)
        }
        return TokenSnapshot(
            tokensPerSecond = rate,
            cumulativeTokens = cumulative,
            timeToFirstTokenMs = firstTokenMs,
            isGenerating = isGenerating,
        )
    }

    companion object {
        private const val WINDOW_MS = 1000L
    }
}
