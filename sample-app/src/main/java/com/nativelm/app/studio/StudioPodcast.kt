/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.studio

/** One turn of a two-host podcast: who speaks ([speaker] 0 or 1, [name]) and what they say. */
data class PodcastTurn(val speaker: Int, val name: String, val text: String)

// A line like "Alex: ..." — a short, capitalised label of one or two words, then a colon.
// Deliberately narrow so ordinary prose with a mid-sentence colon ("Note: ...") doesn't match.
private val SPEAKER_LINE = Regex("^([A-Z][A-Za-z.'-]{1,15}(?: [A-Z][A-Za-z.'-]{1,15})?)\\s*:\\s*(.*)$")

/**
 * Tolerantly parse a two-host dialogue into [PodcastTurn]s. Lines beginning with a
 * recognised "Name:" label start a new turn; any other non-blank line is appended to
 * the current turn (so a wrapped sentence isn't lost). Speakers are numbered in first-
 * appearance order and folded to two voices (index % 2). Returns an empty list when the
 * text doesn't look like a dialogue, so the caller can degrade to plain text.
 */
fun parsePodcast(content: String): List<PodcastTurn> {
    val turns = ArrayList<PodcastTurn>()
    val order = LinkedHashMap<String, Int>()
    for (raw in content.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        val m = SPEAKER_LINE.matchEntire(line)
        if (m != null) {
            val name = m.groupValues[1].trim()
            val text = m.groupValues[2].trim()
            val idx = order.getOrPut(name) { order.size }
            turns.add(PodcastTurn(idx % 2, name, text))
        } else if (turns.isNotEmpty()) {
            val last = turns.removeAt(turns.lastIndex)
            turns.add(last.copy(text = "${last.text} $line".trim()))
        }
    }
    val cleaned = turns.filter { it.text.isNotBlank() }
    // Needs to actually be a two-voice conversation, else it's not a podcast.
    return if (cleaned.size >= 2 && cleaned.map { it.name }.distinct().size >= 2) cleaned else emptyList()
}
