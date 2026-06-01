/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.ui.pdf

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * Locates a cited passage on a PDF page and returns the rectangles to highlight,
 * in normalized page coordinates (fractions of page width/height, top-left
 * origin), so the caller can scale them to whatever size it renders the page at.
 *
 * It re-parses just the cited page with PDFBox, capturing each glyph's
 * [TextPosition], finds the snippet in the page's text, and unions the matched
 * glyphs into one rect per visual line. Best-effort by design: anything it can't
 * confidently place (scanned pages, fonts without a Unicode mapping, rotated
 * pages, or a snippet it can't align) yields an empty list, and the viewer falls
 * back to its "Cited passage" callout.
 */
object PdfHighlighter {

    /** A highlight rectangle in normalized page space: all fields are 0..1, top-left origin. */
    data class Box(val x: Float, val y: Float, val w: Float, val h: Float)

    /** Shortest prefix of the snippet still worth trying to locate. */
    private const val MIN_MATCH_LEN = 24

    suspend fun rectsFor(
        context: Context,
        file: File,
        pageIndex0: Int,
        snippet: String,
    ): List<Box> = withContext(Dispatchers.IO) {
        val needle = normalize(snippet)
        if (needle.length < MIN_MATCH_LEN || !file.exists()) return@withContext emptyList()
        runCatching {
            PDFBoxResourceLoader.init(context.applicationContext)
            PDDocument.load(file).use { doc ->
                if (pageIndex0 !in 0 until doc.numberOfPages) return@use emptyList<Box>()
                val page = doc.getPage(pageIndex0)
                // v1 supports upright pages only; for rotated pages the point→pixel
                // mapping differs from how PdfRenderer lays out the bitmap, so we
                // skip rather than draw a misplaced box.
                if (page.rotation % 360 != 0) return@use emptyList<Box>()
                val cropBox = page.cropBox
                val pageW = cropBox.width
                val pageH = cropBox.height
                if (pageW <= 0f || pageH <= 0f) return@use emptyList<Box>()

                val stripper = CapturingStripper().apply {
                    sortByPosition = true
                    startPage = pageIndex0 + 1
                    endPage = pageIndex0 + 1
                }
                stripper.getText(doc) // drives writeString(); we only want the side effect

                val matched = stripper.locate(needle) ?: return@use emptyList<Box>()
                groupIntoLines(matched).map { line ->
                    // Glyph extents in points (top-left origin, courtesy of the
                    // stripper's flipped Y). A touch of vertical padding covers
                    // ascenders/descenders the font height understates.
                    val left = line.minOf { it.xDirAdj }
                    val right = line.maxOf { it.xDirAdj + it.widthDirAdj }
                    val bottom = line.maxOf { it.yDirAdj } + line.first().heightDir * 0.18f
                    val top = line.minOf { it.yDirAdj - it.heightDir } - line.first().heightDir * 0.12f
                    Box(
                        x = (left / pageW).coerceIn(0f, 1f),
                        y = (top / pageH).coerceIn(0f, 1f),
                        w = ((right - left) / pageW).coerceIn(0f, 1f),
                        h = ((bottom - top) / pageH).coerceIn(0f, 1f),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /** Group glyphs that are already in reading order into one bucket per visual line. */
    private fun groupIntoLines(positions: List<TextPosition>): List<List<TextPosition>> {
        val lines = ArrayList<MutableList<TextPosition>>()
        var lineY = Float.NaN
        for (tp in positions) {
            val y = tp.yDirAdj
            if (lines.isEmpty() || abs(y - lineY) > tp.heightDir * 0.6f) {
                lines.add(mutableListOf())
            }
            lines.last().add(tp)
            lineY = y
        }
        return lines
    }

    /** Collapse all whitespace to single spaces so the snippet (also normalized) can be found. */
    private fun normalize(text: String): String =
        text.replace(Regex("\\s+"), " ").trim()

    /**
     * A [PDFTextStripper] that records the page text alongside the [TextPosition]
     * of each character, then can find a normalized needle within it and return
     * the glyphs that back the match.
     */
    private class CapturingStripper : PDFTextStripper() {
        private val chars = StringBuilder()
        private val positions = ArrayList<TextPosition?>()

        override fun writeString(text: String, textPositions: List<TextPosition>) {
            for (tp in textPositions) {
                val unicode = tp.unicode ?: continue
                for (c in unicode) {
                    chars.append(c)
                    positions.add(tp)
                }
            }
        }

        override fun writeLineSeparator() = appendSeparator()
        override fun writeWordSeparator() = appendSeparator()

        private fun appendSeparator() {
            chars.append(' ')
            positions.add(null)
        }

        /**
         * Find [needle] in the normalized page text and return the backing glyphs,
         * retrying with shorter prefixes so a partial match still highlights
         * something. Returns null if even the shortest prefix isn't present.
         */
        fun locate(needle: String): List<TextPosition>? {
            // Build the normalized string and a parallel char→position list in lockstep.
            val normChars = StringBuilder()
            val normPos = ArrayList<TextPosition?>(chars.length)
            var prevSpace = false
            for (i in chars.indices) {
                val c = chars[i]
                if (c.isWhitespace()) {
                    if (!prevSpace) {
                        normChars.append(' ')
                        normPos.add(null)
                        prevSpace = true
                    }
                } else {
                    normChars.append(c)
                    normPos.add(positions[i])
                    prevSpace = false
                }
            }
            val haystack = normChars.toString()

            var len = needle.length
            while (len >= MIN_MATCH_LEN) {
                val start = haystack.indexOf(needle.substring(0, len))
                if (start >= 0) {
                    return (start until start + len).mapNotNull { normPos[it] }
                }
                len -= (len / 4).coerceAtLeast(8) // shrink quickly: 140→123→…
            }
            return null
        }
    }
}
