/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.studio

/** One parsed FAQ entry: a [question] and its [answer] (answer may be markdown). */
data class FaqItem(val question: String, val answer: String)

/**
 * Tolerantly parse a FAQ artifact's markdown into [FaqItem]s for expandable
 * rendering. Small models are inconsistent, so we accept several shapes and never
 * hard-fail — a caller that gets an empty list should fall back to plain markdown.
 *
 * Recognised question delimiters, in order of preference:
 *  1. Markdown headings `## …` / `### …` (the format we ask for; a lone `# Title`
 *     at the very top is treated as a title, not a question).
 *  2. `Q:` / `Q.` prefixed lines, or numbered `1.` / `1)` lines.
 *
 * Everything between one question line and the next is its answer.
 */
fun parseFaq(content: String): List<FaqItem> {
    val text = content.trim()
    if (text.isEmpty()) return emptyList()

    headingItems(text).let { if (it.size >= 2) return it }
    return prefixItems(text)
}

private val HEADING = Regex("^(#{2,6})\\s+(.*\\S)\\s*$")
private val TOP_H1 = Regex("^#\\s+\\S")
private val PREFIX = Regex("^\\s*(?:Q[:.)\\-]\\s*|\\d+[.)]\\s+)(.*\\S)\\s*$", RegexOption.IGNORE_CASE)
private val ANSWER_PREFIX = Regex("^\\s*A[:.)\\-]\\s*", RegexOption.IGNORE_CASE)

private fun headingItems(text: String): List<FaqItem> =
    splitByQuestion(text.lineSequence().toList()) { line ->
        if (TOP_H1.containsMatchIn(line)) null else HEADING.find(line)?.groupValues?.get(2)?.trim()
    }

private fun prefixItems(text: String): List<FaqItem> =
    splitByQuestion(text.lineSequence().toList()) { line ->
        PREFIX.find(line)?.groupValues?.get(1)?.trim()
    }

/** Walk [lines], starting a new item whenever [questionOf] returns non-null. */
private fun splitByQuestion(lines: List<String>, questionOf: (String) -> String?): List<FaqItem> {
    val items = ArrayList<FaqItem>()
    var question: String? = null
    val answer = StringBuilder()

    fun flush() {
        val q = question ?: return
        items.add(FaqItem(q, answer.toString().trim()))
        answer.setLength(0)
    }

    for (line in lines) {
        val q = questionOf(line)
        if (q != null) {
            flush()
            question = q
        } else if (question != null) {
            answer.append(line.replaceFirst(ANSWER_PREFIX, "")).append('\n')
        }
    }
    flush()
    return items.filter { it.question.isNotBlank() }
}
