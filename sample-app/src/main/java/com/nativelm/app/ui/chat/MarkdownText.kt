/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nativelm.app.ui.chart.ChartView
import com.nativelm.app.ui.theme.JetBrainsMono
import com.sagar.aicore.chart.ChartParser
import com.sagar.aicore.chart.ChartSpec

/**
 * A small, dependency-free Markdown renderer for chat bubbles. Handles the subset
 * on-device models emit — ATX headings (with proper visual hierarchy), bold /
 * italic / strikethrough, inline code chips, fenced code blocks, ordered/unordered
 * and nested lists, task lists, blockquotes, GFM tables, links, and inline
 * ```chart blocks (rendered as on-brand charts; see [ChartView]). Styled to the
 * NativeLM brand. Not a full CommonMark implementation.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val blocks = rememberMarkdownBlocks(markdown)
    val ic = rememberInlineColors(color)
    Column(modifier) {
        blocks.forEachIndexed { i, block ->
            if (i > 0) Spacer(Modifier.height(topSpaceFor(block)))
            when (block) {
                is MdBlock.Heading -> Text(
                    text = inline(block.text, ic),
                    style = headingStyle(block.level),
                    color = color,
                )
                is MdBlock.Paragraph -> Text(
                    text = inline(block.text, ic),
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                    color = color,
                )
                is MdBlock.Code -> CodeBlock(block.code)
                is MdBlock.BulletItem -> ListItemRow("•", block.text, block.indent, ic, color)
                is MdBlock.NumberItem -> ListItemRow("${block.number}.", block.text, block.indent, ic, color)
                is MdBlock.TaskItem -> TaskItemRow(block.checked, block.text, block.indent, ic, color)
                is MdBlock.Quote -> Quote(block.text, ic)
                is MdBlock.Table -> MdTableView(block, ic, color)
                is MdBlock.Chart -> ChartView(block.spec, Modifier.padding(top = 2.dp))
                is MdBlock.Rule -> HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun headingStyle(level: Int): TextStyle = when (level) {
    1 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
    2 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
    3 -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
    else -> MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
}

/** Headings/blocks get more breathing room above them than tight body lines. */
private fun topSpaceFor(block: MdBlock) = when (block) {
    is MdBlock.Heading -> if (block.level <= 2) 16.dp else 12.dp
    is MdBlock.Chart, is MdBlock.Table, is MdBlock.Code, is MdBlock.Rule -> 10.dp
    else -> 6.dp
}

@Composable
private fun ListItemRow(marker: String, text: String, indent: Int, ic: InlineColors, color: Color) {
    Row(Modifier.fillMaxWidth().padding(start = (indent * 16).dp)) {
        Text(
            text = "$marker ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = inline(text, ic),
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
            color = color,
        )
    }
}

@Composable
private fun TaskItemRow(checked: Boolean, text: String, indent: Int, ic: InlineColors, color: Color) {
    val sage = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    Row(
        Modifier.fillMaxWidth().padding(start = (indent * 16).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .padding(end = 8.dp)
                .size(15.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (checked) sage else Color.Transparent)
                .then(if (checked) Modifier else Modifier.border(1.dp, outline, RoundedCornerShape(4.dp))),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Text("✓", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall)
            }
        }
        Text(
            text = inline(text, ic),
            style = MaterialTheme.typography.bodyMedium.copy(
                lineHeight = 21.sp,
                textDecoration = if (checked) TextDecoration.LineThrough else null,
            ),
            color = if (checked) MaterialTheme.colorScheme.onSurfaceVariant else color,
        )
    }
}

@Composable
private fun Quote(text: String, ic: InlineColors) {
    Row(Modifier.fillMaxWidth()) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(3.dp).height(20.dp),
            content = {},
        )
        Text(
            text = inline(text, ic),
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
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
        )
    }
}

// ---- Block model + parsing ----

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Code(val code: String) : MdBlock
    data class BulletItem(val text: String, val indent: Int) : MdBlock
    data class NumberItem(val number: Int, val text: String, val indent: Int) : MdBlock
    data class TaskItem(val checked: Boolean, val text: String, val indent: Int) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MdBlock
    data class Chart(val spec: ChartSpec) : MdBlock
    data object Rule : MdBlock
}

@Composable
private fun MdTableView(table: MdBlock.Table, ic: InlineColors, color: Color) {
    val border = MaterialTheme.colorScheme.outline
    val cols = table.headers.size.coerceAtLeast(1)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            TableRow(table.headers, cols, ic, color, header = true)
            table.rows.forEach { row ->
                HorizontalDivider(color = border)
                TableRow(row, cols, ic, color, header = false)
            }
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, cols: Int, ic: InlineColors, color: Color, header: Boolean) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        for (c in 0 until cols) {
            if (c > 0) VerticalDivider(color = MaterialTheme.colorScheme.outline)
            Box(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp)) {
                Text(
                    text = inline(cells.getOrElse(c) { "" }, ic),
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
private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
private val NUMBER = Regex("""^(\s*)(\d+)\.\s+(.*)$""")
private val TASK = Regex("""^(\s*)[-*+]\s+\[([ xX])]\s+(.*)$""")

/** Indentation level from leading whitespace (tabs count as 2 spaces), capped. */
private fun indentOf(ws: String): Int = (ws.replace("\t", "  ").length / 2).coerceIn(0, 5)

private fun isTableSeparator(line: String): Boolean {
    val t = line.trim()
    return t.contains('-') && t.contains('|') && t.all { it == '|' || it == '-' || it == ':' || it == ' ' || it == '\t' }
}

private fun splitTableRow(line: String): List<String> =
    line.trim().trim('|').split('|').map { it.trim() }

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
                val lang = trimmed.removePrefix("```").trim().lowercase()
                val body = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    body.appendLine(lines[i]); i++
                }
                i++ // skip closing fence
                val text = body.toString().trimEnd('\n')
                if (lang == "chart") {
                    val spec = ChartParser.parse(text)
                    // Graceful fallback: a malformed chart shows its data as a code block,
                    // never an error and never lost content.
                    out += if (spec != null) MdBlock.Chart(spec) else MdBlock.Code(text)
                } else {
                    out += MdBlock.Code(text)
                }
                continue
            }
            line.isBlank() -> flushParagraph()
            line.contains('|') && i + 1 < lines.size && isTableSeparator(lines[i + 1]) -> {
                flushParagraph()
                val headers = splitTableRow(line)
                i += 2
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
                flushParagraph(); out += MdBlock.Rule
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
            TASK.matches(line) -> {
                flushParagraph()
                val m = TASK.find(line)!!
                val checked = m.groupValues[2].lowercase() == "x"
                out += MdBlock.TaskItem(checked, m.groupValues[3].trim(), indentOf(m.groupValues[1]))
            }
            BULLET.matches(line) -> {
                flushParagraph()
                val m = BULLET.find(line)!!
                out += MdBlock.BulletItem(m.groupValues[2].trim(), indentOf(m.groupValues[1]))
            }
            NUMBER.matches(line) -> {
                flushParagraph()
                val m = NUMBER.find(line)!!
                out += MdBlock.NumberItem(m.groupValues[2].toIntOrNull() ?: 1, m.groupValues[3].trim(), indentOf(m.groupValues[1]))
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

/** Theme colours threaded into [inline] so links + code chips adapt to light/dark. */
private data class InlineColors(val base: Color, val link: Color, val codeBg: Color, val codeFg: Color)

@Composable
private fun rememberInlineColors(base: Color) = InlineColors(
    base = base,
    link = MaterialTheme.colorScheme.primary,
    codeBg = MaterialTheme.colorScheme.surfaceVariant,
    codeFg = MaterialTheme.colorScheme.onSurface,
)

private fun inline(text: String, c: InlineColors): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = text.length
    while (i < n) {
        when {
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontFamily = JetBrainsMono, fontSize = 13.sp, background = c.codeBg, color = c.codeFg)) {
                        append(" " + text.substring(i + 1, end) + " ")
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
            text.startsWith("~~", i) -> {
                val end = text.indexOf("~~", i + 2)
                if (end > i) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(text.substring(i + 2, end)) }
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
                            withStyle(SpanStyle(color = c.link, fontWeight = FontWeight.Medium)) { append(label) }
                        }
                        i = urlEnd + 1
                    } else { append(text[i]); i++ }
                } else { append(text[i]); i++ }
            }
            else -> { append(text[i]); i++ }
        }
    }
}
