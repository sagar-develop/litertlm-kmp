/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import io.github.aakira.napier.Napier
import java.io.File
import me.tatarka.inject.annotations.Inject

/**
 * Android implementation of [HardwareProvider]. RAM tier drives Gemma 4
 * variant selection (E2B / E4B); accelerator detection no longer applies
 * since LiteRT-LM picks its backend internally.
 *
 * **Effective RAM, not kernel-reported `MemTotal`:** OEM "RAM expansion"
 * features (Realme Dynamic RAM Expansion, Xiaomi Memory Extension, OPPO,
 * Vivo, etc.) mount swap onto storage and inflate `MemoryInfo.totalMem`.
 * A device marketed as 8 GB can report 11+ GB. Running a 3.6 GB E4B model
 * on a device whose "extra" RAM is disk-backed kills latency.
 *
 * The math `MemTotal âˆ’ SwapTotal` doesn't recover physical RAM cleanly
 * because Realme et al. inflate `MemTotal` AND maintain a separate
 * `SwapTotal` of similar size â€” the relationship is OEM-specific. Instead
 * we use the *presence* of a large swap as a signal that expansion is in
 * play, and cap the effective number below the E4B tier threshold so the
 * device falls to E2B. Devices without expansion (`SwapTotal â‰¤ 1 GB`,
 * which covers zram-only configurations) pass through unchanged.
 */
@Inject
class AndroidHardwareProvider(
    private val context: Context
) : HardwareProvider {

    private val activityManager: ActivityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    override fun getDeviceCapabilities(): DeviceCapabilities {
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val kernelTotalMb = memoryInfo.totalMem / (1024 * 1024)
        val swapMb = readSwapTotalKb() / 1024
        val expansionInPlay = swapMb > SWAP_EXPANSION_THRESHOLD_MB
        val effectiveRamMb = if (expansionInPlay) {
            // Cap below E4B threshold (10 GB) so RAM-expanded devices fall
            // to the E2B tier regardless of the inflated MemTotal value.
            minOf(kernelTotalMb, EXPANSION_CAP_MB)
        } else {
            kernelTotalMb
        }
        Napier.d(tag = TAG) {
            "RAM: MemTotal=${kernelTotalMb}MB SwapTotal=${swapMb}MB " +
                "expansionInPlay=$expansionInPlay effective=${effectiveRamMb}MB"
        }

        return DeviceCapabilities(
            totalRamMb = effectiveRamMb,
            availableRamMb = memoryInfo.availMem / (1024 * 1024),
        )
    }

    /**
     * Reads `SwapTotal` from `/proc/meminfo` in kB. Returns 0 if the file
     * can't be read or the field is missing â€” falls back to the kernel's
     * `MemTotal` (which on devices without RAM expansion is physical RAM
     * anyway).
     */
    private fun readSwapTotalKb(): Long = try {
        File("/proc/meminfo").useLines { lines ->
            lines.firstOrNull { it.startsWith("SwapTotal:") }
                ?.trim()
                ?.split(Regex("\\s+"))
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?: 0L
        }
    } catch (_: Exception) {
        0L
    }

    override fun getAdaptiveMaxTokens(): Int {
        val caps = getDeviceCapabilities()
        return when {
            caps.totalRamMb >= 8000 -> 4096  // 8GB+ devices
            caps.totalRamMb >= 6000 -> 2048  // 6GB devices
            caps.totalRamMb >= 4000 -> 1024  // 4GB devices
            else -> 512                      // Low-end: minimize OOM risk
        }
    }

    override fun getAvailableDiskSpace(): Long {
        val stat = StatFs(context.filesDir.path)
        return stat.availableBytes
    }

    private companion object {
        const val TAG = "Hardware"
        // Above this swap size, assume OEM RAM expansion is active. zram-only
        // setups (common on stock Android) typically sit well below 1 GB.
        const val SWAP_EXPANSION_THRESHOLD_MB = 1024L
        // Cap effective RAM for RAM-expanded devices below the E4B tier
        // threshold (InMemoryModelCatalog.gemma-4-e4b-it-litertlm.minDeviceRamMb
        // = 10 000). 9 000 keeps them on E2B.
        const val EXPANSION_CAP_MB = 9000L
    }
}
