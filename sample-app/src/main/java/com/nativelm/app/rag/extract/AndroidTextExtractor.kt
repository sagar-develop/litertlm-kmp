/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.rag.extract

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android [TextExtractor]: PDFs via PDFBox (per-page, so page boundaries survive
 * as form feeds for the chunker), plain text via the ContentResolver with a
 * binary-content guard. SAF `content://` tails are opaque, so the file extension
 * is taken from [displayName] when available.
 */
class AndroidTextExtractor(private val context: Context) : TextExtractor {

    override suspend fun extract(uri: String, displayName: String?): ExtractedDoc =
        withContext(Dispatchers.IO) {
            ensurePdfBox()
            val parsed = Uri.parse(uri)
            if (extensionOf(displayName, uri) == "pdf") extractPdf(parsed) else extractText(parsed)
        }

    private fun extractPdf(uri: Uri): ExtractedDoc {
        val pageBreak = Char(PAGE_BREAK_CODE)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open document: $uri" }
            PDDocument.load(input).use { doc ->
                val stripper = PDFTextStripper().apply {
                    sortByPosition = true
                    lineSeparator = "\n"
                }
                val pageCount = doc.numberOfPages
                val sb = StringBuilder()
                for (page in 1..pageCount) {
                    stripper.startPage = page
                    stripper.endPage = page
                    sb.append(stripper.getText(doc).trim())
                    if (page < pageCount) sb.append(pageBreak)
                }
                return ExtractedDoc(sb.toString(), pageCount, "application/pdf")
            }
        }
    }

    private fun extractText(uri: Uri): ExtractedDoc {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Cannot open document: $uri")
        // Binary guard: sample the head; reject if too much is non-printable.
        val sample = if (bytes.size > BINARY_SAMPLE) bytes.copyOf(BINARY_SAMPLE) else bytes
        val nonPrintable = sample.count { b ->
            val c = b.toInt() and 0xFF
            c == 0 || (c < 32 && c != 9 && c != 10 && c != 13)
        }
        require(sample.isEmpty() || nonPrintable.toDouble() / sample.size <= MAX_NON_PRINTABLE) {
            "Unsupported file: looks binary, not text"
        }
        return ExtractedDoc(String(bytes, Charsets.UTF_8), 0, "text/plain")
    }

    private fun ensurePdfBox() {
        if (pdfBoxReady) return
        synchronized(lock) {
            if (!pdfBoxReady) {
                PDFBoxResourceLoader.init(context.applicationContext)
                pdfBoxReady = true
            }
        }
    }

    private fun extensionOf(displayName: String?, uri: String): String {
        val name = displayName?.takeIf { it.contains('.') } ?: uri
        return name.substringAfterLast('.', "").lowercase()
    }

    companion object {
        private const val PAGE_BREAK_CODE: Int = 12
        private const val BINARY_SAMPLE: Int = 1024
        private const val MAX_NON_PRINTABLE: Double = 0.30
        private val lock = Any()

        @Volatile
        private var pdfBoxReady: Boolean = false
    }
}
