/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * NativeLM brand mark — a minimal document outline with a small spark in the
 * corner (a "document + intelligence" motif). Code-drawn so it scales cleanly
 * and needs no raster asset. Flat sage, no gradients or glow.
 */
@Composable
fun NativeLmMark(
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    color: Color = Sage,
) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size.minDimension
        val stroke = Stroke(width = s * 0.07f)

        // Document body: rounded rectangle occupying most of the glyph.
        val docLeft = s * 0.22f
        val docTop = s * 0.14f
        val docRight = s * 0.74f
        val docBottom = s * 0.86f
        drawRoundRect(
            color = color,
            topLeft = Offset(docLeft, docTop),
            size = Size(docRight - docLeft, docBottom - docTop),
            cornerRadius = CornerRadius(s * 0.08f, s * 0.08f),
            style = stroke,
        )

        // Two text lines on the document.
        val lineX1 = docLeft + s * 0.10f
        val lineX2 = docRight - s * 0.10f
        drawLine(color, Offset(lineX1, s * 0.38f), Offset(lineX2, s * 0.38f), strokeWidth = s * 0.055f)
        drawLine(color, Offset(lineX1, s * 0.52f), Offset(lineX2 - s * 0.12f, s * 0.52f), strokeWidth = s * 0.055f)

        // Spark: a small four-point star at the top-right, solid fill.
        val cx = s * 0.80f
        val cy = s * 0.24f
        val r = s * 0.16f
        val w = s * 0.05f
        val spark = Path().apply {
            moveTo(cx, cy - r)
            cubicTo(cx + w, cy - w, cx + w, cy - w, cx + r, cy)
            cubicTo(cx + w, cy + w, cx + w, cy + w, cx, cy + r)
            cubicTo(cx - w, cy + w, cx - w, cy + w, cx - r, cy)
            cubicTo(cx - w, cy - w, cx - w, cy - w, cx, cy - r)
            close()
        }
        drawPath(spark, color)
    }
}
