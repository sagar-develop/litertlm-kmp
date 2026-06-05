/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore.studio

/** A node in a mind map: a short [text] label and its child branches. */
data class MindNode(val text: String, val children: List<MindNode>)

private val MM_BULLET = Regex("^(\\s*)[-*+]\\s+(.*\\S)\\s*$")
private val MM_H1 = Regex("^#\\s+(.*\\S)\\s*$")

/**
 * Tolerantly parse a nested indented-bullet outline into a [MindNode] tree using an
 * indent stack (tabs count as two spaces; indentation is compared relatively, so
 * inconsistent widths still nest correctly). A single top-level bullet becomes the
 * root; if the model emits several top-level bullets (or a flat list), they're hung
 * under a synthesized root taken from the first `# ` heading, or "Mind map".
 * Returns null when no bullets parse, so the caller degrades to plain markdown.
 */
fun parseMindMap(content: String): MindNode? {
    val text = content.trim()
    if (text.isEmpty()) return null

    val roots = ArrayList<MutableNode>()
    val stack = ArrayList<MutableNode>()
    var h1: String? = null

    for (raw in text.lineSequence()) {
        val line = raw.replace("\t", "  ")
        if (h1 == null) MM_H1.find(line.trim())?.let { h1 = mmClean(it.groupValues[1]) }
        val m = MM_BULLET.find(line) ?: continue
        val label = mmClean(m.groupValues[2])
        if (label.isBlank()) continue
        val node = MutableNode(m.groupValues[1].length, label)
        while (stack.isNotEmpty() && stack.last().indent >= node.indent) stack.removeAt(stack.size - 1)
        if (stack.isEmpty()) roots.add(node) else stack.last().children.add(node)
        stack.add(node)
    }

    if (roots.isEmpty()) return null
    return if (roots.size == 1) roots[0].toNode()
    else MindNode(h1?.takeIf { it.isNotBlank() } ?: "Mind map", roots.map { it.toNode() })
}

private class MutableNode(val indent: Int, val text: String) {
    val children = ArrayList<MutableNode>()
    fun toNode(): MindNode = MindNode(text, children.map { it.toNode() })
}

/** Strip Markdown emphasis / leading bullet residue / trailing colons from a label. */
private fun mmClean(s: String): String = s.trim().trim('*', '_', '`', '#', ':', ' ').trim()
