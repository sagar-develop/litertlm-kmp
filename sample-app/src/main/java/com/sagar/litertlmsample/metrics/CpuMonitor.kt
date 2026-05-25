/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.litertlmsample.metrics

import android.os.Process
import android.os.SystemClock
import io.github.aakira.napier.Napier
import java.io.File

/**
 * App-friendly CPU usage sampling. Two sources:
 *
 * 1. Aggregate process CPU — `Process.getElapsedCpuTime()` (CLOCK_PROCESS_CPUTIME_ID
 *    under the hood). Returns total CPU-ms consumed by all threads in this
 *    process; deltas over wall-clock time give "fraction of one core busy".
 *    Divided by [numCores] to land on a 0-100% scale representing average
 *    per-core busy across the whole process.
 *
 *    Works without any permission on every Android version. (Earlier
 *    `/proc/stat` approach returned EACCES from app UID on Android 11+ —
 *    SELinux blocks system-wide CPU stats for non-system apps.)
 *
 * 2. Per-core frequency — `/sys/devices/system/cpu/cpuN/cpufreq/scaling_cur_freq`
 *    against `cpuinfo_max_freq`. This is the cpufreq governor's currently-set
 *    frequency divided by the silicon's max frequency. When cores ramp up
 *    under load, the percentage climbs; idle cores throttle down. It's a
 *    *frequency-utilization* proxy, not a true CPU% per core — but on
 *    Android's interactive governor (the default) it tracks real load
 *    closely enough to look right during inference and is the best signal
 *    available without root.
 */
class CpuMonitor {

    private val numCores: Int = Runtime.getRuntime().availableProcessors()

    private val maxFreqKHzPerCore: List<Long> = (0 until numCores).map { core ->
        readLongOrZero(File("/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq"))
    }

    private var lastCpuMs: Long = -1L
    private var lastWallMs: Long = -1L

    fun sample(): CpuSnapshot? {
        val nowCpu = Process.getElapsedCpuTime()
        val nowWall = SystemClock.elapsedRealtime()

        val prevCpu = lastCpuMs
        val prevWall = lastWallMs

        lastCpuMs = nowCpu
        lastWallMs = nowWall

        // Per-core scaling-frequency utilization is independent of the
        // wall-clock delta and can be sampled on the very first call too.
        val perCore = (0 until numCores).map { core ->
            val maxFreq = maxFreqKHzPerCore[core]
            if (maxFreq <= 0L) return@map 0f
            val curFreq = readLongOrZero(
                File("/sys/devices/system/cpu/cpu$core/cpufreq/scaling_cur_freq")
            )
            if (curFreq <= 0L) 0f
            else (100f * curFreq / maxFreq).coerceIn(0f, 100f)
        }

        // Aggregate needs a previous sample to delta against.
        if (prevWall <= 0L) {
            return CpuSnapshot(totalUsagePct = 0f, perCoreUsagePct = perCore)
        }

        val deltaCpu = (nowCpu - prevCpu).coerceAtLeast(0L)
        val deltaWall = (nowWall - prevWall).coerceAtLeast(1L)
        // 100 * (deltaCpu / deltaWall) is the process's total CPU usage as a
        // fraction of one core. Divide by numCores to land on 0-100% scale
        // representing average per-core occupancy across the process.
        val avgPerCore = (100f * deltaCpu / deltaWall / numCores).coerceIn(0f, 100f)

        return CpuSnapshot(
            totalUsagePct = avgPerCore,
            perCoreUsagePct = perCore,
        )
    }

    private fun readLongOrZero(file: File): Long = try {
        file.readText().trim().toLong()
    } catch (t: Throwable) {
        Napier.v(tag = "CpuMonitor") { "Could not read ${file.path}: ${t.message}" }
        0L
    }
}
