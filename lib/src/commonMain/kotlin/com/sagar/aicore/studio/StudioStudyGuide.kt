/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore.studio

/** A key term and its definition. */
data class TermItem(val term: String, val definition: String)

/** A parsed study guide: key [terms] and review [questions] (reusing [FaqItem]). */
data class StudyGuide(val terms: List<TermItem>, val questions: List<FaqItem>)

private val SG_H2 = Regex("^##\\s+(.*\\S)\\s*$")
private val SG_ITEM = Regex("^#{3,6}\\s+(.*\\S)\\s*$")
private val SG_BULLET = Regex("^\\s*[-*]\\s+(.*\\S)\\s*$")
private val SG_BOLD_LEAD = Regex("^\\*\\*(.+?)\\*\\*\\s*[:—\\-]*\\s*(.*)$")

/**
 * Tolerantly parse a Study Guide into its two sections. Splits on `## ` headings,
 * classifies each by title keyword, then parses `### item` (or bullet) pairs within.
 * Returns null when nothing usable parsed, so the caller falls back to plain markdown.
 */
fun parseStudyGuide(content: String): StudyGuide? {
    val text = content.trim()
    if (text.isEmpty()) return null

    val sections = splitH2(text)
    if (sections.isEmpty()) return null

    var terms = emptyList<TermItem>()
    var questions = emptyList<FaqItem>()
    for ((title, body) in sections) {
        val t = title.lowercase()
        when {
            "term" in t || "vocab" in t || "definition" in t || "glossary" in t ->
                terms = parseTermPairs(body)
            "question" in t || "review" in t || "quiz" in t || "short" in t || "answer" in t ->
                questions = pairs(body).map { FaqItem(it.first, it.second) }
        }
    }
    if (terms.isEmpty() && questions.isEmpty()) return null
    return StudyGuide(terms, questions)
}

/** Split into (sectionTitle, sectionBody) by level-2 (`## `) headings only. */
private fun splitH2(text: String): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>()
    var title: String? = null
    val body = StringBuilder()
    fun flush() {
        val t = title ?: return
        out.add(t to body.toString())
        body.setLength(0)
    }
    for (line in text.lineSequence()) {
        val h2 = SG_H2.find(line)?.groupValues?.get(1)?.trim()
        if (h2 != null) {
            flush()
            title = h2
        } else if (title != null) {
            body.append(line).append('\n')
        }
    }
    flush()
    return out
}

/** Heading-delimited (heading, body) pairs within a section; falls back to bullets. */
private fun pairs(body: String): List<Pair<String, String>> {
    val items = ArrayList<Pair<String, String>>()
    var head: String? = null
    val buf = StringBuilder()
    fun flush() {
        val h = head ?: return
        items.add(sgStrip(h) to buf.toString().trim())
        buf.setLength(0)
    }
    for (line in body.lineSequence()) {
        val item = SG_ITEM.find(line)?.groupValues?.get(1)?.trim()
        if (item != null) {
            flush()
            head = item
        } else if (head != null) {
            buf.append(line).append('\n')
        }
    }
    flush()
    if (items.isNotEmpty()) return items.filter { it.first.isNotBlank() }

    // Fallback: "- **Term** — definition" / "- Term: definition" bullets.
    return body.lineSequence()
        .mapNotNull { SG_BULLET.find(it)?.groupValues?.get(1)?.trim() }
        .map(::splitLead)
        .filter { it.first.isNotBlank() }
        .toList()
}

private fun parseTermPairs(body: String): List<TermItem> =
    pairs(body).map { TermItem(it.first, it.second) }

/** Split "Term — definition" / "**Term**: definition" into (term, definition). */
private fun splitLead(s: String): Pair<String, String> {
    val cleaned = s.trim()
    SG_BOLD_LEAD.find(cleaned)?.let { return sgStrip(it.groupValues[1]) to it.groupValues[2].trim() }
    val idx = cleaned.indexOfFirst { it == ':' || it == '—' }
    return if (idx in 1 until cleaned.length - 1) {
        sgStrip(cleaned.take(idx)) to cleaned.substring(idx + 1).trim()
    } else {
        sgStrip(cleaned) to ""
    }
}

private fun sgStrip(s: String): String = s.trim().trim('*', '_', '`', '#', ':', ' ').trim()
