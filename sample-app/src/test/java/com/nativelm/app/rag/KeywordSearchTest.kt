/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordSearchTest {

    @Test fun tokenizeSplitsOnNonAlphanumericAndLowercases() {
        assertEquals(listOf("hello", "world", "123"), KeywordSearch.tokenize("Hello, World! 123"))
    }

    @Test fun queryTermsDropStopwordsAndDuplicates() {
        // "what/is/the" are stop words; "codename" survives; duplicates collapse.
        assertEquals(listOf("codename"), KeywordSearch.queryTerms("What is the codename codename"))
    }

    @Test fun rankReturnsOnlyDocsContainingAQueryTerm() {
        val docs = listOf(
            KeywordSearch.Doc(1, "the project codename is zephyr nine"),
            KeywordSearch.Doc(2, "completely unrelated paragraph about weather"),
        )
        assertEquals(listOf(1L), KeywordSearch.rank("zephyr", docs))
    }

    @Test fun rankOrdersByRelevance() {
        val docs = listOf(
            KeywordSearch.Doc(1, "zephyr report filler text here"),
            KeywordSearch.Doc(2, "zephyr zephyr zephyr dense match"),
        )
        // Doc 2 mentions the term more often → ranks first.
        assertEquals(listOf(2L, 1L), KeywordSearch.rank("zephyr", docs))
    }

    @Test fun emptyQueryOrDocsRankEmpty() {
        assertTrue(KeywordSearch.rank("the and of", listOf(KeywordSearch.Doc(1, "anything"))).isEmpty())
        assertTrue(KeywordSearch.rank("zephyr", emptyList()).isEmpty())
    }

    @Test fun reciprocalRankFusionRewardsAgreement() {
        // id 1 is high in both lists; id 3 high in one; id 2 only mid in one.
        val fused = KeywordSearch.reciprocalRankFusion(listOf(listOf(1, 2, 3), listOf(3, 1)))
        assertEquals(1L, fused.first()) // appears near top of both → wins
        assertEquals(setOf(1L, 2L, 3L), fused.toSet())
    }
}
