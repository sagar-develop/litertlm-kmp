/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.litertlmsample.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sagar.litertlmsample.metrics.MetricsRepository
import kotlin.math.roundToInt

private const val HISTORY_LEN = 80

@Composable
fun MetricsOverlay(repo: MetricsRepository) {
    val snapshot by repo.snapshot.collectAsStateWithLifecycle()

    // Keep a UI-side ring buffer of recent CPU% values so we can draw a
    // flowing history line. (The repository only emits the latest sample.)
    val cpuHistory: SnapshotStateList<Float> = remember {
        List(HISTORY_LEN) { 0f }.toMutableStateList()
    }
    LaunchedEffect(snapshot.cpu.totalUsagePct) {
        cpuHistory.removeAt(0)
        cpuHistory.add(snapshot.cpu.totalUsagePct)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Header row: title + live "generating · N t/s" indicator
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Live metrics",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            if (snapshot.tokens.isGenerating) {
                Box(
                    Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Generating · ${snapshot.tokens.tokensPerSecond.roundToInt()} t/s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    "Idle",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        CpuHistoryChart(cpuHistory)

        if (snapshot.cpu.perCoreUsagePct.isNotEmpty()) {
            PerCoreBars(snapshot.cpu.perCoreUsagePct)
        }

        // Metric chips row
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MetricChip(label = "RAM", value = formatMb(snapshot.memory.totalPssMb), modifier = Modifier.weight(1f))
            MetricChip(label = "tok/s", value = "${snapshot.tokens.tokensPerSecond.roundToInt()}", modifier = Modifier.weight(1f))
            MetricChip(
                label = "TTFT",
                value = if (snapshot.tokens.timeToFirstTokenMs < 0) "—" else "${snapshot.tokens.timeToFirstTokenMs}ms",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CpuHistoryChart(history: List<Float>) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val fillColor = lineColor.copy(alpha = 0.18f)

    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "CPU total",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${history.lastOrNull()?.roundToInt() ?: 0}%",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = lineColor,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        ) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                // grid lines at 25 / 50 / 75 / 100%
                for (pct in listOf(0.25f, 0.5f, 0.75f, 1f)) {
                    val y = size.height * (1f - pct)
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1f,
                    )
                }
                if (history.size < 2) return@Canvas
                val step = size.width / (history.size - 1)
                val toY = { v: Float -> size.height * (1f - v / 100f) }
                val path = Path().apply {
                    moveTo(0f, toY(history[0]))
                    for (i in 1 until history.size) {
                        lineTo(i * step, toY(history[i]))
                    }
                }
                val fillPath = Path().apply {
                    addPath(path)
                    lineTo((history.size - 1) * step, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(fillPath, color = fillColor)
                drawPath(
                    path,
                    color = lineColor,
                    style = Stroke(width = 2.5f, cap = StrokeCap.Round),
                )
            }
        }
    }
}

@Composable
private fun PerCoreBars(perCore: List<Float>) {
    Column {
        Text(
            "Per-core ${perCore.size}×",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            val barColor = MaterialTheme.colorScheme.primary
            val bgColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            perCore.forEachIndexed { idx, pct ->
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(2.dp))
                            .background(bgColor),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height((28 * (pct / 100f).coerceIn(0f, 1f)).dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(barColor),
                        )
                    }
                    Text(
                        "$idx",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricChip(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun formatMb(mb: Int): String =
    if (mb >= 1024) String.format("%.1f GB", mb / 1024f) else "$mb MB"
