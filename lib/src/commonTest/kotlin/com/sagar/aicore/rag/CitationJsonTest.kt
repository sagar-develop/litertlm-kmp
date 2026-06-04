/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore.rag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CitationJsonTest {

    @Test fun emptyEncodesToEmptyStringAndBack() {
        assertEquals("", CitationJson.encode(emptyList()))
        assertTrue(CitationJson.decode("").isEmpty())
    }

    @Test fun nullDecodesToEmpty() {
        // A store may return null for this column on rows written before the field
        // existed (Kotlin defaults aren't applied to legacy rows). Must not throw.
        assertTrue(CitationJson.decode(null).isEmpty())
    }

    @Test fun roundTripsAllFields() {
        val original = listOf(
            Citation(documentId = 7, documentTitle = "The-Hindu-Review-May", pageNumber = 19, snippet = "RBI launched a summit."),
            Citation(documentId = 12, documentTitle = "Notes", pageNumber = 0, snippet = "Quote with \"quotes\", commas, and\nnewlines."),
        )
        val decoded = CitationJson.decode(CitationJson.encode(original))
        assertEquals(original, decoded)
    }

    @Test fun malformedJsonDecodesToEmpty() {
        assertTrue(CitationJson.decode("not json").isEmpty())
        assertTrue(CitationJson.decode("{\"d\":1}").isEmpty())
    }

    @Test fun missingFieldsFallBackToDefaults() {
        val decoded = CitationJson.decode("""[{"t":"OnlyTitle"}]""")
        assertEquals(1, decoded.size)
        assertEquals(Citation(0L, "OnlyTitle", 0, ""), decoded.first())
    }
}
