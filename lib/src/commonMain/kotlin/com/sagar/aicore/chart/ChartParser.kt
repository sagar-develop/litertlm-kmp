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
        val root = Json.parseToJsonElement(json.trim()) as? JsonObject ?: return@runCatching null
        // Resolve the chart object + its type. The strict top-level "type" path is
        // tried FIRST (so well-formed `{"type":"donut",…}` is unchanged), then the
        // tolerant fallbacks for models that wrap the chart or omit the type.
        val (obj, type) = resolve(root) ?: return@runCatching null
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

    // ── chart resolution (tolerant of how different models shape the JSON) ──

    /** Wrapper keys some models nest the chart under, mapped to a type hint
     *  (empty = generic wrapper, infer the type from the inner object). */
    private val WRAPPERS = mapOf(
        "donut" to "donut", "doughnut" to "donut", "pie" to "donut",
        "bar" to "bar", "barchart" to "bar", "column" to "bar", "columns" to "bar", "histogram" to "bar",
        "line" to "line", "linechart" to "line", "area" to "line", "trend" to "line", "growth" to "line", "spline" to "line",
        "progress" to "progress", "gauge" to "progress", "ring" to "progress",
        "chart" to "", "graph" to "", "plot" to "", "visualization" to "", "visualisation" to "",
    )

    /** Reads an explicit type, tolerating `chartType` / `kind` synonyms. */
    private fun typeOf(obj: JsonObject): String? =
        (str(obj["type"]) ?: str(obj["chartType"]) ?: str(obj["kind"]))?.lowercase()?.trim()?.ifBlank { null }

    /** Whether an object carries chart-shaped payload (so a bare key isn't mistaken for a chart). */
    private fun hasData(obj: JsonObject): Boolean =
        obj["data"] is JsonArray || obj["slices"] is JsonArray || obj["values"] is JsonArray ||
            obj["series"] is JsonArray || obj["value"] != null

    /** When the type is absent, infer it from the payload shape (proportions → donut). */
    private fun inferType(obj: JsonObject): String? = when {
        obj["series"] is JsonArray -> "line"
        obj["data"] is JsonArray || obj["slices"] is JsonArray || obj["values"] is JsonArray -> "donut"
        obj["value"] != null -> "progress"
        else -> null
    }

    /**
     * Resolves the (object, type) to read the chart from:
     *  1) explicit type on the root (the strict, common case — unchanged);
     *  2) the chart nested under a single wrapper key (`{"chart":{…}}`, `{"donut":{…}}`),
     *     taking the type from the wrapper name, else the inner type, else inferred;
     * otherwise null (ordinary JSON stays a code block).
     */
    private fun resolve(root: JsonObject): Pair<JsonObject, String>? {
        typeOf(root)?.let { return root to it }
        for ((key, value) in root) {
            val inner = value as? JsonObject ?: continue
            val hint = WRAPPERS[key.lowercase().trim()] ?: continue
            if (!hasData(inner)) continue
            val type = hint.ifBlank { null } ?: typeOf(inner) ?: inferType(inner) ?: continue
            return inner to type
        }
        return null
    }

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
