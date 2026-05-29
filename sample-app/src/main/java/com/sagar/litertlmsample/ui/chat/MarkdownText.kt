/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.litertlmsample.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sagar.litertlmsample.ui.theme.JetBrainsMono

/**
 * A small, dependency-free Markdown renderer for chat bubbles. Handles the
 * subset on-device models actually emit: ATX headings, bold/italic, inline code,
 * fenced code blocks, unordered/ordered lists, blockquotes, and links. Styled to
 * the NativeLM brand (JetBrains Mono for code, sage links). Not a full CommonMark
 * implementation — tables and nested emphasis are rendered best-effort.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    val blocks = rememberMarkdownBlocks(markdown)
    Column(modifier) {
        blocks.forEachIndexed { i, block ->
            if (i > 0) Spacer(Modifier.height(6.dp))
            when (block) {
                is MdBlock.Heading -> Text(
                    text = inline(block.text, color),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }.copy(fontWeight = FontWeight.SemiBold),
                    color = color,
                )
                is MdBlock.Paragraph -> Text(
                    text = inline(block.text, color),
                    style = MaterialTheme.typography.bodyMedium,
                    color = color,
                )
                is MdBlock.Code -> CodeBlock(block.code)
                is MdBlock.BulletItem -> ListItemRow(marker = "•", text = block.text, color = color)
                is MdBlock.NumberItem -> ListItemRow(marker = "${block.number}.", text = block.text, color = color)
                is MdBlock.Quote -> Quote(block.text, color)
                is MdBlock.Table -> MdTableView(block, color)
                is MdBlock.Rule -> HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun ListItemRow(marker: String, text: String, color: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = "$marker ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = inline(text, color),
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
    }
}

@Composable
private fun Quote(text: String, color: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth()) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .width(3.dp)
                .height(20.dp),
            content = {},
        )
        Text(
            text = inline(text, color),
            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

@Composable
private fun CodeBlock(code: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = code,
            style = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp, lineHeight = 18.sp),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
        )
    }
}

// ---- Block model + parsing ----

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Code(val code: String) : MdBlock
    data class BulletItem(val text: String) : MdBlock
    data class NumberItem(val number: Int, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

@Composable
private fun MdTableView(table: MdBlock.Table, color: androidx.compose.ui.graphics.Color) {
    val border = MaterialTheme.colorScheme.outline
    val cols = table.headers.size.coerceAtLeast(1)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            TableRow(cells = table.headers, cols = cols, color = color, header = true)
            table.rows.forEach { row ->
                HorizontalDivider(color = border)
                TableRow(cells = row, cols = cols, color = color, header = false)
            }
        }
    }
}

@Composable
private fun TableRow(
    cells: List<String>,
    cols: Int,
    color: androidx.compose.ui.graphics.Color,
    header: Boolean,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        for (c in 0 until cols) {
            if (c > 0) VerticalDivider(color = MaterialTheme.colorScheme.outline)
            Box(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(
                    text = inline(cells.getOrElse(c) { "" }, color),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = color,
                )
            }
        }
    }
}

@Composable
private fun rememberMarkdownBlocks(markdown: String): List<MdBlock> =
    androidx.compose.runtime.remember(markdown) { parseMarkdownBlocks(markdown) }

private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
private val BULLET = Regex("""^\s*[-*+]\s+(.*)$""")
private val NUMBER = Regex("""^\s*(\d+)\.\s+(.*)$""")

/** A GFM table separator like `| :--- | ---: |` — only pipes, dashes, colons, spaces, and at least one dash. */
private fun isTableSeparator(line: String): Boolean {
    val t = line.trim()
    return t.contains('-') && t.contains('|') && t.all { it == '|' || it == '-' || it == ':' || it == ' ' || it == '\t' }
}

private fun splitTableRow(line: String): List<String> =
    line.trim().trim('|').split('|').map { it.trim() }

/** A thematic break: a line of 3+ of the same -, *, or _ (optionally spaced). */
private fun isHorizontalRule(line: String): Boolean {
    val t = line.trim()
    if (t.length < 3) return false
    val ch = t.firstOrNull() ?: return false
    if (ch != '-' && ch != '*' && ch != '_') return false
    return t.all { it == ch || it == ' ' } && t.count { it == ch } >= 3
}

private fun parseMarkdownBlocks(markdown: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = markdown.replace("\r\n", "\n").split("\n")
    var i = 0
    val paragraph = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotBlank()) out += MdBlock.Paragraph(paragraph.toString().trim())
        paragraph.setLength(0)
    }

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()

        when {
            trimmed.startsWith("```") -> {
                flushParagraph()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    code.appendLine(lines[i]); i++
                }
                i++ // skip closing fence
                out += MdBlock.Code(code.toString().trimEnd('\n'))
                continue
            }
            line.isBlank() -> flushParagraph()
            line.contains('|') && i + 1 < lines.size && isTableSeparator(lines[i + 1]) -> {
                flushParagraph()
                val headers = splitTableRow(line)
                i += 2 // consume header + separator rows
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank() &&
                    !lines[i].trimStart().startsWith("```")
                ) {
                    rows += splitTableRow(lines[i]); i++
                }
                out += MdBlock.Table(headers, rows)
                continue
            }
            isHorizontalRule(line) -> {
                flushParagraph()
                out += MdBlock.Rule
            }
            HEADING.matches(line) -> {
                flushParagraph()
                val m = HEADING.find(line)!!
                out += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2].trim())
            }
            trimmed.startsWith("> ") || trimmed == ">" -> {
                flushParagraph()
                out += MdBlock.Quote(trimmed.removePrefix(">").trim())
            }
            BULLET.matches(line) -> {
                flushParagraph()
                out += MdBlock.BulletItem(BULLET.find(line)!!.groupValues[1].trim())
            }
            NUMBER.matches(line) -> {
                flushParagraph()
                val m = NUMBER.find(line)!!
                out += MdBlock.NumberItem(m.groupValues[1].toIntOrNull() ?: 1, m.groupValues[2].trim())
            }
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(line.trim())
            }
        }
        i++
    }
    flushParagraph()
    return out
}

// ---- Inline parsing → AnnotatedString ----

private fun inline(text: String, base: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = text.length
    while (i < n) {
        when {
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontFamily = JetBrainsMono, fontSize = 13.sp)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            text.startsWith("**", i) || text.startsWith("__", i) -> {
                val delim = text.substring(i, i + 2)
                val end = text.indexOf(delim, i + 2)
                if (end > i) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else { append(text[i]); i++ }
            }
            (text[i] == '*' || text[i] == '_') -> {
                val delim = text[i]
                val end = text.indexOf(delim, i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            text[i] == '[' -> {
                val close = text.indexOf(']', i + 1)
                if (close > i && close + 1 < n && text[close + 1] == '(') {
                    val urlEnd = text.indexOf(')', close + 2)
                    if (urlEnd > close) {
                        val label = text.substring(i + 1, close)
                        val url = text.substring(close + 2, urlEnd)
                        withLink(LinkAnnotation.Url(url)) {
                            withStyle(SpanStyle(color = MaterialThemePrimary, fontWeight = FontWeight.Medium)) {
                                append(label)
                            }
                        }
                        i = urlEnd + 1
                    } else { append(text[i]); i++ }
                } else { append(text[i]); i++ }
            }
            else -> { append(text[i]); i++ }
        }
    }
}

// Link color resolved at call-time would need a composable; use the brand sage
// directly so inline() stays a plain function.
private val MaterialThemePrimary = com.sagar.litertlmsample.ui.theme.Sage
