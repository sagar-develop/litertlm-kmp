/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore.chart

/**
 * The system-prompt fragment that teaches the model how (and when) to emit a chart.
 * Append [SYSTEM] to your chat system instruction to enable charts; [ChartParser]
 * reads what the model produces. Kept terse so it doesn't dominate small-model context.
 */
object ChartInstruction {

    val SYSTEM: String = buildString {
        appendLine("When numeric data is easier to grasp visually, you MAY include ONE chart as a fenced code block tagged `chart` containing minimal JSON. Use it only when it genuinely helps, and still write your normal explanation around it.")
        appendLine("Supported shapes (values are plain numbers, 2–6 entries, valid JSON only, no colours):")
        appendLine("- Proportions: {\"type\":\"donut\",\"title\":\"Spend\",\"data\":[{\"label\":\"Rent\",\"value\":40},{\"label\":\"Food\",\"value\":25}]}")
        appendLine("- Comparison:  {\"type\":\"bar\",\"title\":\"Sales\",\"unit\":\"k\",\"data\":[{\"label\":\"Q1\",\"value\":10},{\"label\":\"Q2\",\"value\":14}]}")
        appendLine("- Single goal: {\"type\":\"progress\",\"title\":\"Progress\",\"value\":72,\"max\":100}")
        appendLine("- Trend:       {\"type\":\"line\",\"title\":\"Growth\",\"unit\":\"%\",\"data\":[{\"label\":\"Jan\",\"value\":10},{\"label\":\"Feb\",\"value\":14},{\"label\":\"Mar\",\"value\":19}]}")
        append("If you are unsure the data is clean, use a normal Markdown table instead of a chart.")
    }
}
