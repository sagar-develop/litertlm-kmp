/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore.studio

/** One dated event on a timeline: the [date] as the sources gave it + a [description]. */
data class TimelineEvent(val date: String, val description: String)

private val TL_ITEM = Regex("^#{2,6}\\s+(.*\\S)\\s*$")
private val TL_BULLET = Regex("^\\s*[-*]\\s+(.*\\S)\\s*$")
private val TL_BOLD_LEAD = Regex("^\\*\\*(.+?)\\*\\*\\s*[:—\\-]*\\s*(.*)$")

/**
 * Tolerantly parse a Timeline into ordered events. Primary form is `### <date>`
 * headings (level-1 `#` title lines are ignored) with the description on the lines
 * below; falls back to "- **date** — event" / "- date: event" bullets. Returns an
 * empty list when nothing usable parsed (or the model emitted [StudioPrompts.NO_DATES]),
 * so the caller can degrade to plain markdown or suggest a different artifact.
 */
fun parseTimeline(content: String): List<TimelineEvent> {
    val text = content.trim()
    if (text.isEmpty() || text.equals(StudioPrompts.NO_DATES, ignoreCase = true)) return emptyList()

    val items = ArrayList<TimelineEvent>()
    var head: String? = null
    val buf = StringBuilder()
    fun flush() {
        val h = head ?: return
        items.add(TimelineEvent(tlStrip(h), buf.toString().trim()))
        buf.setLength(0)
    }
    for (line in text.lineSequence()) {
        val item = TL_ITEM.find(line)?.groupValues?.get(1)?.trim()
        if (item != null) {
            flush()
            head = item
        } else if (head != null) {
            buf.append(line).append('\n')
        }
    }
    flush()
    val headingParsed = items.filter { it.date.isNotBlank() }
    if (headingParsed.isNotEmpty()) return headingParsed

    // Fallback: "- **March 2024** — event" / "- 2024: event" bullets.
    return text.lineSequence()
        .mapNotNull { TL_BULLET.find(it)?.groupValues?.get(1)?.trim() }
        .mapNotNull(::splitDateLead)
        .toList()
}

/** Split "date — event" / "**date**: event" into a [TimelineEvent], or null if no date. */
private fun splitDateLead(s: String): TimelineEvent? {
    val cleaned = s.trim()
    TL_BOLD_LEAD.find(cleaned)?.let {
        val date = tlStrip(it.groupValues[1])
        return if (date.isBlank()) null else TimelineEvent(date, it.groupValues[2].trim())
    }
    val idx = cleaned.indexOfFirst { it == ':' || it == '—' }
    if (idx in 1 until cleaned.length - 1) {
        val date = tlStrip(cleaned.take(idx))
        return if (date.isBlank()) null else TimelineEvent(date, cleaned.substring(idx + 1).trim())
    }
    return null
}

private fun tlStrip(s: String): String = s.trim().trim('*', '_', '`', '#', ':', ' ').trim()
