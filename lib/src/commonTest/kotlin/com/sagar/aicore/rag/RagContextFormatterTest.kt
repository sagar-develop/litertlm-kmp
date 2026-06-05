/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore.rag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RagContextFormatterTest {

    private fun scored(docId: Long, text: String, page: Int = 0, score: Double = 0.1) =
        ScoredChunk(
            StoredChunk(
                id = 0,
                documentId = docId,
                projectId = 0,
                text = text,
                pageNumber = page,
                chunkIndex = 0,
            ),
            score,
        )

    @Test fun emptyInputIsEmpty() {
        assertTrue(RagContextFormatter.format(emptyList()) { "x" }.isEmpty)
    }

    @Test fun fencesAndCitesEachChunk() {
        val ctx = RagContextFormatter.format(
            listOf(scored(1, "alpha fact", page = 3), scored(2, "beta fact")),
        ) { id -> "Doc$id" }

        assertFalse(ctx.isEmpty)
        assertTrue(ctx.contextText.contains("--- CONTEXT START ---"))
        assertTrue(ctx.contextText.contains("--- CONTEXT END ---"))
        assertTrue(ctx.contextText.contains("alpha fact"))
        assertTrue(ctx.contextText.contains("beta fact"))
        assertTrue(ctx.contextText.contains("[Doc1, p.3]"))
        assertEquals(2, ctx.citations.size)
        assertEquals("Doc1", ctx.citations[0].documentTitle)
        assertEquals(3, ctx.citations[0].pageNumber)
    }

    @Test fun dropsNearDuplicateChunks() {
        val dupe = "the quick brown fox jumps over the lazy dog"
        val ctx = RagContextFormatter.format(
            listOf(scored(1, dupe), scored(1, dupe)),
        ) { "Doc" }
        assertEquals(1, ctx.citations.size)
    }

    @Test fun capsContextLength() {
        val huge = "x".repeat(10_000)
        val ctx = RagContextFormatter.format(listOf(scored(1, huge))) { "Doc" }
        assertEquals(1, ctx.citations.size)
        assertTrue(ctx.contextText.contains("--- CONTEXT END ---"))
        assertTrue(ctx.contextText.length < 4200, "context exceeded cap: ${ctx.contextText.length}")
    }
}
