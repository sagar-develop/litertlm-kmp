/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.ui.chart

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nativelm.app.ui.theme.JetBrainsMono
import com.sagar.aicore.chart.ChartSpec
import kotlin.math.roundToInt

/**
 * Renders a [ChartSpec] inside a chat bubble — quiet, compact, and on-brand
 * (monochrome sage family, warm card, JetBrains Mono numbers, no axis chrome).
 * Deliberately hand-drawn with Compose `Canvas`/layout (the same visual language as
 * the NativeLM logo mark) rather than a generic charting library.
 */
@Composable
fun ChartView(spec: ChartSpec, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(14.dp)) {
            spec.title?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(12.dp))
            }
            when (spec) {
                is ChartSpec.Donut -> DonutChart(spec)
                is ChartSpec.Bar -> BarChart(spec)
                is ChartSpec.Progress -> ProgressChart(spec)
                is ChartSpec.Line -> LineChart(spec)
            }
        }
    }
}

/** Monochrome sage ramp (by alpha) so a multi-slice chart still reads as one brand family. */
@Composable
private fun sagePalette(count: Int): List<Color> {
    val sage = MaterialTheme.colorScheme.primary
    val alphas = listOf(1f, 0.78f, 0.6f, 0.46f, 0.34f, 0.24f)
    return (0 until count).map { sage.copy(alpha = alphas[it % alphas.size]) }
}

@Composable
private fun DonutChart(spec: ChartSpec.Donut) {
    val total = spec.slices.sumOf { it.value }.takeIf { it > 0.0 } ?: return
    val colors = sagePalette(spec.slices.size)
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(108.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = size.minDimension * 0.16f
                val arc = Size(size.minDimension - stroke, size.minDimension - stroke)
                val topLeft = Offset((size.width - arc.width) / 2f, (size.height - arc.height) / 2f)
                var start = -90f
                spec.slices.forEachIndexed { idx, slice ->
                    val sweep = (slice.value / total * 360.0).toFloat()
                    // ~3° hairline gap between slices for separation.
                    drawArc(
                        color = colors[idx],
                        startAngle = start + 1.5f,
                        sweepAngle = (sweep - 3f).coerceAtLeast(0.5f),
                        useCenter = false,
                        topLeft = topLeft,
                        size = arc,
                        style = Stroke(width = stroke, cap = StrokeCap.Butt),
                    )
                    start += sweep
                }
            }
            Text(
                text = spec.centerLabel?.takeIf { it.isNotBlank() } ?: fmt(total),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = JetBrainsMono,
                color = onSurface,
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            spec.slices.forEachIndexed { idx, slice ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 2.dp),
                ) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(colors[idx]))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = slice.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = fmt(slice.value),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = JetBrainsMono,
                        color = muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun BarChart(spec: ChartSpec.Bar) {
    val max = spec.bars.maxOfOrNull { it.value }?.takeIf { it > 0.0 } ?: return
    val sage = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val unit = spec.unit?.takeIf { it.isNotBlank() }.orEmpty()
    Column {
        spec.bars.forEach { bar ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 3.dp),
            ) {
                Text(
                    text = bar.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(68.dp),
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .weight(1f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(track),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth((bar.value / max).toFloat().coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(7.dp))
                            .background(sage),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = fmt(bar.value) + unit,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = JetBrainsMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ProgressChart(spec: ChartSpec.Progress) {
    val pct = (spec.value / spec.max).coerceIn(0.0, 1.0).toFloat()
    val sage = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = size.minDimension * 0.12f
                val arc = Size(size.minDimension - stroke, size.minDimension - stroke)
                val topLeft = Offset((size.width - arc.width) / 2f, (size.height - arc.height) / 2f)
                drawArc(track, -90f, 360f, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                drawArc(sage, -90f, 360f * pct, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Text(
                text = "${(pct * 100).roundToInt()}%",
                style = MaterialTheme.typography.titleSmall,
                fontFamily = JetBrainsMono,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        spec.label?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.width(16.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LineChart(spec: ChartSpec.Line) {
    val series = spec.series.firstOrNull { it.points.size >= 2 } ?: return
    val pts = series.points
    val sage = MaterialTheme.colorScheme.primary
    val fill = sage.copy(alpha = 0.14f)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val ys = pts.map { it.y }
    val minY = ys.min()
    val range = (ys.max() - minY).takeIf { it != 0.0 } ?: 1.0
    Column {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(120.dp),
        ) {
            val n = pts.size
            val dx = if (n > 1) size.width / (n - 1) else size.width
            val padY = size.height * 0.12f
            fun at(i: Int): Offset {
                val norm = ((pts[i].y - minY) / range).toFloat()
                val y = size.height - padY - norm * (size.height - 2 * padY)
                return Offset(dx * i, y)
            }
            val line = Path().apply {
                moveTo(at(0).x, at(0).y)
                for (i in 1 until n) lineTo(at(i).x, at(i).y)
            }
            val area = Path().apply {
                moveTo(at(0).x, size.height)
                for (i in 0 until n) lineTo(at(i).x, at(i).y)
                lineTo(at(n - 1).x, size.height)
                close()
            }
            drawPath(area, fill)
            drawPath(
                path = line,
                color = sage,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            drawCircle(sage, radius = 3.5.dp.toPx(), center = at(n - 1))
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(pts.first().x, style = MaterialTheme.typography.labelSmall, color = muted)
            if (pts.size > 2) {
                Text(pts[pts.size / 2].x, style = MaterialTheme.typography.labelSmall, color = muted)
            }
            Text(pts.last().x, style = MaterialTheme.typography.labelSmall, color = muted)
        }
    }
}

/** Compact number formatting: drop a trailing `.0`, otherwise keep ≤ 2 decimals. */
private fun fmt(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString()
    else ((v * 100).roundToInt() / 100.0).toString()
