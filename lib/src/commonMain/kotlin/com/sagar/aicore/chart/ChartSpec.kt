/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore.chart

/**
 * A chart the assistant asked to render, parsed from a ```chart fenced JSON block
 * in its answer (see [ChartParser]). Engine-neutral and UI-free: the host app draws
 * each variant however it likes. Values are plain numbers; colours are deliberately
 * NOT carried here so the renderer can keep every chart on-brand.
 */
sealed interface ChartSpec {
    val title: String?

    /** Proportions of a whole — rendered as a thin donut ring + legend. */
    data class Donut(
        override val title: String?,
        val slices: List<ChartSlice>,
        val centerLabel: String? = null,
    ) : ChartSpec

    /** Categorical magnitudes — rendered as horizontal bars. */
    data class Bar(
        override val title: String?,
        val bars: List<ChartSlice>,
        val unit: String? = null,
    ) : ChartSpec

    /** A single value against a max — rendered as a progress ring. */
    data class Progress(
        override val title: String?,
        val value: Double,
        val max: Double,
        val label: String? = null,
    ) : ChartSpec

    /** A trend over an ordered axis — rendered as a growth/line chart. */
    data class Line(
        override val title: String?,
        val series: List<ChartSeries>,
        val unit: String? = null,
    ) : ChartSpec
}

/** One labelled value, used by donut slices and bars. */
data class ChartSlice(val label: String, val value: Double)

/** A named line in a [ChartSpec.Line]; one series for a single growth curve. */
data class ChartSeries(val name: String?, val points: List<ChartPoint>)

/** A point on a line: [x] is the (categorical/time) axis label, [y] the value. */
data class ChartPoint(val x: String, val y: Double)
