/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Formalized layout tokens for the adaptive UI (see docs/prototype/DESIGN.md §1).
 * Colors and type are locked in [Color] / [Type]; this adds the spacing, radius,
 * and elevation discipline the redesign asks for, without touching the brand.
 *
 * Plain `dp` constants (not a CompositionLocal) — the scale is global and never
 * themed per-surface, so a simple object keeps call sites readable: `Spacing.lg`.
 */
object Spacing {
    /** Strict 8pt grid (with a 4dp half-step for tight metadata rows). */
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
    val huge = 40.dp
    val giant = 48.dp
}

/** Corner radii: sm 10 · md 14 · lg 20 · xl 28 (pill = 50%). */
object Radius {
    val sm = 10.dp
    val md = 14.dp
    val lg = 20.dp
    val xl = 28.dp
}

/**
 * Reading-column cap. On Medium/Expanded windows content panes center and stop
 * widening here so text never runs edge-to-edge on a tablet (DESIGN.md §2).
 */
object Layout {
    val contentMaxWidth = 760.dp
    /** Hairline border weight for cards (soft warm elevation, not heavy shadow). */
    val hairline = 1.dp
}

/**
 * Centers content and caps it at [Layout.contentMaxWidth] so a reading column
 * never runs edge-to-edge on a Medium/Expanded window. On Compact it's a no-op
 * (the cap is wider than the screen). Apply the scaffold's inner [padding] here.
 */
@Composable
fun WideContent(
    padding: PaddingValues,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(Modifier.widthIn(max = Layout.contentMaxWidth).fillMaxSize()) {
            content()
        }
    }
}

/** Material3 shape scale mapped onto [Radius] so components inherit the brand radii. */
val NativeLmShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.sm),
    small = RoundedCornerShape(Radius.sm),
    medium = RoundedCornerShape(Radius.md),
    large = RoundedCornerShape(Radius.lg),
    extraLarge = RoundedCornerShape(Radius.xl),
)
