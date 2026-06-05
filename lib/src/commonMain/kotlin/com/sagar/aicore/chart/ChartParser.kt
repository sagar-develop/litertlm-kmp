/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore.chart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Tolerantly parses the JSON inside a ```chart fenced block into a [ChartSpec].
 *
 * Small on-device models are inconsistent, so this is deliberately lenient: it
 * accepts type synonyms (pie→donut, column→bar, growth/trend→line), several key
 * spellings (label/name/x, value/y), numbers given as strings ("40", "72%"), and
 * never throws — a malformed block returns null so the caller can fall back to a
 * plain table. Uses the kotlinx-serialization JSON *runtime* (no @Serializable
 * codegen), matching how the engine already uses the library.
 */
object ChartParser {

    fun parse(json: String): ChartSpec? = runCatching {
        val obj = Json.parseToJsonElement(json.trim()) as? JsonObject ?: return@runCatching null
        val type = str(obj["type"])?.lowercase()?.trim()
        val title = str(obj["title"])?.ifBlank { null }
        when (type) {
            "donut", "doughnut", "pie" -> {
                val slices = slices(obj)
                if (slices.isEmpty()) null
                else ChartSpec.Donut(title, slices, centerLabel = str(obj["center"]) ?: str(obj["centerLabel"]))
            }
            "bar", "bars", "column", "columns", "histogram" -> {
                val bars = slices(obj)
                if (bars.isEmpty()) null else ChartSpec.Bar(title, bars, unit = str(obj["unit"]))
            }
            "progress", "gauge", "ring" -> {
                val value = num(obj["value"]) ?: return@runCatching null
                val rawMax = num(obj["max"]) ?: 100.0
                ChartSpec.Progress(title, value, max = if (rawMax <= 0.0) 100.0 else rawMax, label = str(obj["label"]))
            }
            "line", "growth", "area", "trend", "spline" -> {
                val series = series(obj).filter { it.points.size >= 2 }
                if (series.isEmpty()) null else ChartSpec.Line(title, series, unit = str(obj["unit"]))
            }
            else -> null
        }
    }.getOrNull()

    // ── helpers ──

    /** A flat list of labelled values under "data" / "slices" / "values". */
    private fun slices(obj: JsonObject): List<ChartSlice> {
        val arr = array(obj["data"]) ?: array(obj["slices"]) ?: array(obj["values"]) ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val label = str(o["label"]) ?: str(o["name"]) ?: str(o["x"]) ?: return@mapNotNull null
            val value = num(o["value"]) ?: num(o["y"]) ?: return@mapNotNull null
            ChartSlice(label.trim(), value)
        }
    }

    /** Explicit "series" if present, otherwise a single series built from flat "data". */
    private fun series(obj: JsonObject): List<ChartSeries> {
        array(obj["series"])?.let { arr ->
            return arr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val name = str(o["name"]) ?: str(o["label"])
                val pts = (array(o["points"]) ?: array(o["data"]))?.mapNotNull { p ->
                    val po = p as? JsonObject ?: return@mapNotNull null
                    val x = str(po["x"]) ?: str(po["label"]) ?: return@mapNotNull null
                    val y = num(po["y"]) ?: num(po["value"]) ?: return@mapNotNull null
                    ChartPoint(x.trim(), y)
                } ?: emptyList()
                ChartSeries(name, pts)
            }
        }
        val pts = slices(obj).map { ChartPoint(it.label, it.value) }
        return if (pts.isEmpty()) emptyList() else listOf(ChartSeries(null, pts))
    }

    private fun array(el: JsonElement?): JsonArray? = el as? JsonArray

    private fun str(el: JsonElement?): String? = (el as? JsonPrimitive)?.contentOrNull

    /** A number given as a JSON number, or a numeric string (tolerating a trailing %). */
    private fun num(el: JsonElement?): Double? {
        val p = el as? JsonPrimitive ?: return null
        return p.doubleOrNull ?: p.contentOrNull?.trim()?.removeSuffix("%")?.trim()?.toDoubleOrNull()
    }
}
