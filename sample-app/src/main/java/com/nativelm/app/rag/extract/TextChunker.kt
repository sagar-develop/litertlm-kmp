/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.rag.extract

/**
 * Splits extracted document text into overlapping chunks for embedding.
 *
 * Character-based sliding window (not token-aware) — the predecessor shipped this
 * and it's a safe fit for USE-Lite's context. Page boundaries are honoured: the
 * extractor separates pages with a form feed (code point 12), and chunks never
 * span a page so the resulting [Chunk.pageNumber] is accurate for citations. When
 * the source has no page markers, [Chunk.pageNumber] is 0 (unknown).
 *
 * @param chunkSize characters per chunk
 * @param overlap characters shared between consecutive chunks (stride = size - overlap)
 */
class TextChunker(
    val chunkSize: Int = 500,
    val overlap: Int = 50,
) {
    private val pageBreak: Char = Char(PAGE_BREAK_CODE)

    init {
        require(chunkSize > 0) { "chunkSize must be > 0" }
        require(overlap in 0 until chunkSize) { "overlap must be in 0..<chunkSize" }
    }

    fun chunk(text: String): List<Chunk> {
        if (text.isBlank()) return emptyList()
        val pages = text.split(pageBreak)
        val hasPages = pages.size > 1
        val stride = chunkSize - overlap
        val out = ArrayList<Chunk>()
        var index = 0
        pages.forEachIndexed { pageIdx, rawPage ->
            val page = rawPage.trim()
            if (page.isEmpty()) return@forEachIndexed
            var start = 0
            while (start < page.length) {
                val end = (start + chunkSize).coerceAtMost(page.length)
                val piece = page.substring(start, end).trim()
                if (piece.isNotEmpty()) {
                    out += Chunk(
                        text = piece,
                        index = index++,
                        pageNumber = if (hasPages) pageIdx + 1 else 0,
                    )
                }
                if (end == page.length) break
                start += stride
            }
        }
        return out
    }

    companion object {
        /** Form feed — the page separator the extractor inserts between PDF pages. */
        const val PAGE_BREAK_CODE: Int = 12
    }
}
