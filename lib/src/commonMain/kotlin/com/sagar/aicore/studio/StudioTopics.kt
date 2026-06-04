/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore.studio

/** One parsed Key Topic: a short [title] and a one-line [description]. */
data class TopicItem(val title: String, val description: String)

private val TOPIC_HEADING = Regex("^(#{2,6})\\s+(.*\\S)\\s*$")
private val TOPIC_TOP_H1 = Regex("^#\\s+\\S")
private val TOPIC_BULLET = Regex("^\\s*(?:[-*]|\\d+[.)])\\s+(.*\\S)\\s*$")

/**
 * Tolerantly parse a Key Topics artifact into [TopicItem]s. Prefers `## / ###`
 * headings (the format we ask for); falls back to bullet / numbered lines where a
 * `**bold**` or `name — description` / `name: description` split gives the title.
 * Never hard-fails — an empty result tells the caller to render plain markdown.
 */
fun parseTopics(content: String): List<TopicItem> {
    val text = content.trim()
    if (text.isEmpty()) return emptyList()

    headingTopics(text).let { if (it.isNotEmpty()) return it }
    return bulletTopics(text)
}

private fun headingTopics(text: String): List<TopicItem> {
    val items = ArrayList<TopicItem>()
    var title: String? = null
    val desc = StringBuilder()

    fun flush() {
        val t = title ?: return
        items.add(TopicItem(t, desc.toString().trim()))
        desc.setLength(0)
    }

    for (line in text.lineSequence()) {
        if (TOPIC_TOP_H1.containsMatchIn(line)) continue // a top-level title, not a topic
        val heading = TOPIC_HEADING.find(line)?.groupValues?.get(2)?.trim()
        if (heading != null) {
            flush()
            title = stripMarkers(heading)
        } else if (title != null) {
            desc.append(line.trim()).append(' ')
        }
    }
    flush()
    return items.filter { it.title.isNotBlank() }
}

private fun bulletTopics(text: String): List<TopicItem> =
    text.lineSequence()
        .mapNotNull { TOPIC_BULLET.find(it)?.groupValues?.get(1)?.trim() }
        .map { body ->
            // Split "Title — description" / "Title: description" if present.
            val split = body.indexOfFirst { it == ':' || it == '—' || it == '-' }
            if (split in 1 until body.length - 1) {
                TopicItem(stripMarkers(body.take(split).trim()), body.substring(split + 1).trim())
            } else {
                TopicItem(stripMarkers(body), "")
            }
        }
        .filter { it.title.isNotBlank() }
        .toList()

/** Drop surrounding markdown emphasis/colons a model often wraps the title in. */
private fun stripMarkers(s: String): String =
    s.trim().trim('*', '_', '`', '#', ':', ' ').trim()
