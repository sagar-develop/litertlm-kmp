/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.rag

import com.nativelm.app.rag.extract.TextChunker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChunkerTest {

    @Test fun blankTextYieldsNoChunks() {
        assertTrue(TextChunker().chunk("   \n  ").isEmpty())
    }

    @Test fun shortSinglePageIsOneChunkWithUnknownPage() {
        val chunks = TextChunker().chunk("hello world")
        assertEquals(1, chunks.size)
        assertEquals("hello world", chunks[0].text)
        assertEquals(0, chunks[0].pageNumber) // no page markers → unknown
    }

    @Test fun longTextSplitsWithOverlapStride() {
        // size=10, overlap=2 → stride=8. 16 chars → windows at offset 0 and 8.
        val chunks = TextChunker(chunkSize = 10, overlap = 2).chunk("abcdefghijklmnop")
        assertEquals(2, chunks.size)
        assertEquals("abcdefghij", chunks[0].text)
        assertEquals("ijklmnop", chunks[1].text) // 2-char overlap with the first window
    }

    @Test fun pageBreaksProduceAccuratePageNumbersAndDoNotSpan() {
        val pb = Char(TextChunker.PAGE_BREAK_CODE)
        val chunks = TextChunker().chunk("first page${pb}second page")
        assertEquals(2, chunks.size)
        assertEquals(1, chunks[0].pageNumber)
        assertEquals(2, chunks[1].pageNumber)
        assertEquals("first page", chunks[0].text)
        assertEquals("second page", chunks[1].text)
    }
}
