/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.litertlmsample.metrics

import io.github.aakira.napier.Napier
import java.io.File

/**
 * Reads `/proc/stat` to compute system-wide CPU usage (aggregate + per-core).
 *
 * `/proc/stat` exposes cumulative jiffies per CPU since boot:
 *   cpu  user nice system idle iowait irq softirq steal guest guest_nice
 *   cpu0 user nice system idle ...
 *   cpu1 ...
 *
 * Usage% over a window = 100 * (Δactive) / (Δactive + Δidle)
 * where active = user + nice + system + irq + softirq + steal
 *       idle   = idle + iowait
 *
 * /proc/stat is world-readable on Android — no permission needed for own-pid
 * or system aggregate.
 */
class CpuMonitor {

    private var lastTotal: CoreTimes? = null
    private var lastPerCore: List<CoreTimes> = emptyList()

    /** Take a sample. Returns null on the very first call (no delta yet). */
    fun sample(): CpuSnapshot? = try {
        val raw = File("/proc/stat").readLines()
        val totalLine = raw.firstOrNull { it.startsWith("cpu ") }
            ?: return null
        val coreLines = raw.filter { it.matches(CORE_LINE) }

        val current = totalLine.toCoreTimes()
        val currentPerCore = coreLines.map { it.toCoreTimes() }

        val totalPrev = lastTotal
        val perCorePrev = lastPerCore

        lastTotal = current
        lastPerCore = currentPerCore

        if (totalPrev == null || perCorePrev.size != currentPerCore.size) {
            // First sample or core count changed — no delta yet.
            null
        } else {
            CpuSnapshot(
                totalUsagePct = current.usagePctSince(totalPrev),
                perCoreUsagePct = currentPerCore.mapIndexed { i, c ->
                    c.usagePctSince(perCorePrev[i])
                },
            )
        }
    } catch (t: Throwable) {
        Napier.w(throwable = t, tag = "CpuMonitor") { "Failed to read /proc/stat" }
        null
    }

    private data class CoreTimes(
        val user: Long, val nice: Long, val system: Long,
        val idle: Long, val iowait: Long, val irq: Long,
        val softirq: Long, val steal: Long,
    ) {
        val active = user + nice + system + irq + softirq + steal
        val idleAll = idle + iowait

        fun usagePctSince(prev: CoreTimes): Float {
            val deltaActive = (active - prev.active).coerceAtLeast(0)
            val deltaIdle = (idleAll - prev.idleAll).coerceAtLeast(0)
            val total = deltaActive + deltaIdle
            return if (total == 0L) 0f else (100f * deltaActive / total).coerceIn(0f, 100f)
        }
    }

    private fun String.toCoreTimes(): CoreTimes {
        val parts = trim().split(WHITESPACE)
        // parts[0] is "cpu" or "cpuN"; parts[1..] are jiffy counts
        return CoreTimes(
            user = parts.getOrZero(1),
            nice = parts.getOrZero(2),
            system = parts.getOrZero(3),
            idle = parts.getOrZero(4),
            iowait = parts.getOrZero(5),
            irq = parts.getOrZero(6),
            softirq = parts.getOrZero(7),
            steal = parts.getOrZero(8),
        )
    }

    private fun List<String>.getOrZero(i: Int): Long = getOrNull(i)?.toLongOrNull() ?: 0L

    companion object {
        private val WHITESPACE = "\\s+".toRegex()
        private val CORE_LINE = "^cpu\\d+\\s+.*".toRegex()
    }
}
