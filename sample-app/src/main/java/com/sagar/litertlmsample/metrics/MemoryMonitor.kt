/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.litertlmsample.metrics

import android.os.Debug

/**
 * Process memory usage. Reads Android's Debug.MemoryInfo which is a snapshot
 * of /proc/self/smaps — accurate at the PSS level (proportional set size,
 * shared pages counted proportionally), the canonical metric Android uses
 * for OOM-killer scoring.
 *
 * For an on-device LLM the load is in nativePss (model weights + LiteRT-LM
 * runtime); dalvikPss is the Kotlin/Java overhead.
 */
class MemoryMonitor {

    fun sample(): MemorySnapshot {
        val info = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        // Debug.MemoryInfo reports values in KB; convert to MB.
        return MemorySnapshot(
            totalPssMb = info.totalPss / 1024,
            dalvikPssMb = info.dalvikPss / 1024,
            nativePssMb = info.nativePss / 1024,
        )
    }
}
