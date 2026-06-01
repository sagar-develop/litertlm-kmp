/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CitationJsonTest {

    @Test fun emptyEncodesToEmptyStringAndBack() {
        assertEquals("", CitationJson.encode(emptyList()))
        assertTrue(CitationJson.decode("").isEmpty())
    }

    @Test fun nullDecodesToEmpty() {
        // ObjectBox returns null for this column on rows written before the field
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
        // Tolerate legacy/garbage data rather than crashing chat restore.
        assertTrue(CitationJson.decode("not json").isEmpty())
        assertTrue(CitationJson.decode("{\"d\":1}").isEmpty()) // object, not the expected array
    }

    @Test fun missingFieldsFallBackToDefaults() {
        val decoded = CitationJson.decode("""[{"t":"OnlyTitle"}]""")
        assertEquals(1, decoded.size)
        assertEquals(Citation(0L, "OnlyTitle", 0, ""), decoded.first())
    }
}
