/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.rag.extract

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.sagar.aicore.rag.ExtractedDoc
import com.sagar.aicore.rag.TextExtractor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android [TextExtractor]: PDFs via PDFBox (per-page, so page boundaries survive
 * as form feeds for the chunker), images via on-device OCR, plain text via the
 * ContentResolver with a binary-content guard.
 *
 * Scanned PDFs (and scanned pages within an otherwise-digital PDF) carry no text
 * layer, so a page that PDFBox returns (near-)empty is rendered to a bitmap and
 * run through [ocrEngine]. SAF `content://` tails are opaque, so the type is
 * decided from the resolver's MIME type and the [displayName] extension.
 */
class AndroidTextExtractor(
    private val context: Context,
    private val ocrEngine: OcrEngine,
) : TextExtractor {

    override suspend fun extract(uri: String, displayName: String?): ExtractedDoc =
        withContext(Dispatchers.IO) {
            ensurePdfBox()
            val parsed = Uri.parse(uri)
            val mime = runCatching { context.contentResolver.getType(parsed) }.getOrNull()
            val ext = extensionOf(displayName, uri)
            when {
                ext == "pdf" || mime == "application/pdf" -> extractPdf(parsed)
                ext in IMAGE_EXTS || mime?.startsWith("image/") == true -> extractImage(parsed, mime)
                else -> extractText(parsed)
            }
        }

    private suspend fun extractPdf(uri: Uri): ExtractedDoc {
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
                // Opened lazily and only if some page lacks a text layer, so a
                // fully-digital PDF never pays the PdfRenderer/OCR cost.
                var pfd: ParcelFileDescriptor? = null
                var renderer: PdfRenderer? = null
                try {
                    for (page in 1..pageCount) {
                        stripper.startPage = page
                        stripper.endPage = page
                        var pageText = stripper.getText(doc).trim()
                        if (pageText.length < MIN_TEXT_LAYER_CHARS) {
                            if (renderer == null) {
                                pfd = runCatching {
                                    context.contentResolver.openFileDescriptor(uri, "r")
                                }.getOrNull()
                                renderer = pfd?.let { runCatching { PdfRenderer(it) }.getOrNull() }
                            }
                            val ocr = renderer?.let { ocrPage(it, page - 1) }.orEmpty().trim()
                            if (ocr.length > pageText.length) pageText = ocr
                        }
                        sb.append(pageText)
                        if (page < pageCount) sb.append(pageBreak)
                    }
                } finally {
                    runCatching { renderer?.close() }
                    runCatching { pfd?.close() }
                }
                return ExtractedDoc(sb.toString(), pageCount, "application/pdf")
            }
        }
    }

    /** Render one PDF page to a bitmap at OCR-friendly resolution, then recognize it. */
    private suspend fun ocrPage(renderer: PdfRenderer, index: Int): String {
        val bitmap = runCatching {
            renderer.openPage(index).use { page ->
                val scale = (OCR_RENDER_WIDTH.toFloat() / page.width).coerceAtMost(MAX_OCR_SCALE)
                val w = (page.width * scale).toInt().coerceAtLeast(1)
                val h = (page.height * scale).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE) // OCR expects a white background, not transparent/black
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            }
        }.getOrNull() ?: return ""
        return runCatching { ocrEngine.recognize(bitmap) }.getOrDefault("")
    }

    private suspend fun extractImage(uri: Uri, mime: String?): ExtractedDoc {
        val bitmap = decodeSampled(uri) ?: error("Couldn't read this image.")
        val text = runCatching { ocrEngine.recognize(bitmap) }.getOrDefault("").trim()
        return ExtractedDoc(text, pageCount = 1, mimeType = mime ?: "image/*")
    }

    /** Decode a (possibly large) image, downscaled so the longer side ≤ [IMAGE_MAX_DIM]. */
    private fun decodeSampled(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longer = maxOf(bounds.outWidth, bounds.outHeight)
        if (longer <= 0) return null
        var sample = 1
        while (longer / sample > IMAGE_MAX_DIM) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
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

        /** Below this, a PDF page is treated as having no usable text layer → OCR. */
        private const val MIN_TEXT_LAYER_CHARS: Int = 8
        private const val OCR_RENDER_WIDTH: Int = 2200
        private const val MAX_OCR_SCALE: Float = 4f
        private const val IMAGE_MAX_DIM: Int = 2600
        private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "bmp", "heic", "heif")

        private val lock = Any()

        @Volatile
        private var pdfBoxReady: Boolean = false
    }
}
