/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.rag.extract

/**
 * Extracts plain text from an imported file (PDF or text). Implementations live
 * in this package (e.g. AndroidTextExtractor via PDFBox).
 *
 * Contract:
 *  - [uri] is a SAF `content://` URI string. Its tail is opaque, so use
 *    [displayName] (the picker-provided name) for extension detection.
 *  - Throws on unreadable/corrupt input; never returns partial garbage. A binary
 *    file that isn't a recognized document should be rejected, not parsed.
 */
interface TextExtractor {
    suspend fun extract(uri: String, displayName: String?): ExtractedDoc
}

/**
 * Result of extraction. [text] is the full document text; when the source has
 * pages and the extractor preserves boundaries, pages are separated by a form
 * feed ('') so the chunker can attribute a page number.
 */
data class ExtractedDoc(
    val text: String,
    val pageCount: Int,
    val mimeType: String,
)

/** A single chunk produced by [TextChunker]. [pageNumber] is 0 when unknown. */
data class Chunk(
    val text: String,
    val index: Int,
    val pageNumber: Int = 0,
)
