/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.ui.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nativelm.app.llm.NativeLmViewModel
import com.nativelm.app.llm.PdfViewTarget
import com.nativelm.app.ui.theme.JetBrainsMono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private const val RENDER_WIDTH_PX = 1080

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

/**
 * Renders a source PDF (the durable app-private copy) at the cited page using
 * Android's built-in [PdfRenderer] — no third-party dependency. The cited passage
 * is shown in a pinned highlight callout above the page so the reader can see what
 * the answer was grounded in. (True in-page glyph highlighting is a follow-up.)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(vm: NativeLmViewModel, onBack: () -> Unit) {
    // Snapshot the target once for this screen's lifetime. Reading it live would
    // mean that clearing it on back-press (to avoid stale state) re-triggers the
    // null-guard below and pops the back stack twice — landing on a blank screen.
    val t = remember { vm.pdfViewTarget.value }
    if (t == null) {
        // No target (e.g. process death restored this route) — nothing to show; pop out.
        LaunchedEffect(Unit) { onBack() }
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        t.title,
                        maxLines = 1,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        PdfContent(target = t, modifier = Modifier.padding(padding))
    }
}

@Composable
private fun PdfContent(target: PdfViewTarget, modifier: Modifier = Modifier) {
    // Image sources (OCR'd) have no PDF structure — decode and show directly.
    if (target.isImage) {
        ImagePageContent(target, modifier)
        return
    }

    val context = LocalContext.current

    // Open the renderer once for this file; close it on dispose.
    val renderer = remember(target.localPath) { openRenderer(target.localPath) }
    DisposableEffect(target.localPath) {
        onDispose { renderer?.closeQuietly() }
    }

    val pageCount = renderer?.pageCount ?: 0
    val citedIndex = remember(target.localPath) {
        (target.initialPage - 1).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    }
    var pageIndex by remember(target.localPath) { mutableIntStateOf(citedIndex) }
    var bitmap by remember(target.localPath) { mutableStateOf<ImageBitmap?>(null) }
    val renderLock = remember(target.localPath) { Mutex() }

    // Best-effort: locate the cited passage's glyph rectangles on the cited page,
    // so we can highlight it in place. Empty when it can't be placed (then the
    // callout below stands in). [highlightResolved] gates the fallback so the
    // callout doesn't flash before the lookup finishes.
    var highlightBoxes by remember(target.localPath) { mutableStateOf<List<PdfHighlighter.Box>>(emptyList()) }
    var highlightResolved by remember(target.localPath) { mutableStateOf(false) }
    LaunchedEffect(target.localPath) {
        highlightBoxes = if (target.highlight.isBlank()) {
            emptyList()
        } else {
            PdfHighlighter.rectsFor(context, File(target.localPath), citedIndex, target.highlight)
        }
        highlightResolved = true
    }

    // Sage highlight over the (always-white) page. The opaque border does the
    // work of marking the region, so the fill is just a light tint (~0.15) that
    // keeps the text underneath fully legible rather than washing it darker.
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f).toArgb()

    LaunchedEffect(target.localPath, pageIndex, highlightBoxes) {
        if (renderer == null || pageCount == 0) return@LaunchedEffect
        val boxes = if (pageIndex == citedIndex) highlightBoxes else emptyList()
        bitmap = renderLock.withLock { renderPage(renderer, pageIndex, boxes, highlightColor) }
    }

    Column(modifier.fillMaxSize()) {
        // The callout is a fallback: show it only when we're on the cited page,
        // the lookup has finished, and we couldn't place the passage inline.
        val showCallout = target.highlight.isNotBlank() &&
            pageIndex == citedIndex && highlightResolved && highlightBoxes.isEmpty()
        if (showCallout) {
            HighlightCallout(target.highlight)
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                renderer == null ->
                    Text(
                        "Couldn't open this document.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                bitmap == null -> CircularProgressIndicator()
                else -> ZoomablePage(
                    bitmap = bitmap!!,
                    contentDescription = "Page ${pageIndex + 1}",
                    resetKey = pageIndex,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        if (pageCount > 1) {
            PageBar(
                page = pageIndex + 1,
                pageCount = pageCount,
                onPrev = { if (pageIndex > 0) pageIndex-- },
                onNext = { if (pageIndex < pageCount - 1) pageIndex++ },
            )
        }
    }
}

/**
 * An image source (OCR'd at import): decode the durable copy and show it in the
 * same zoomable viewer. In-image highlighting isn't supported yet, so the cited
 * passage is shown via the callout above the image.
 */
@Composable
private fun ImagePageContent(target: PdfViewTarget, modifier: Modifier = Modifier) {
    var bitmap by remember(target.localPath) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(target.localPath) { mutableStateOf(false) }
    LaunchedEffect(target.localPath) {
        val decoded = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(target.localPath)?.asImageBitmap() }.getOrNull()
        }
        bitmap = decoded
        failed = decoded == null
    }

    Column(modifier.fillMaxSize()) {
        if (target.highlight.isNotBlank()) {
            HighlightCallout(target.highlight)
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                failed -> Text(
                    "Couldn't open this image.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                bitmap == null -> CircularProgressIndicator()
                else -> ZoomablePage(
                    bitmap = bitmap!!,
                    contentDescription = target.title,
                    resetKey = target.localPath,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * The page bitmap, fit to width, with pinch-to-zoom and pan. The page (and the
 * baked-in highlight) scale together since both live in the bitmap. At 1× the
 * page is taller than the viewport, so a single-finger drag pans it vertically;
 * once zoomed, panning works in both axes. Pan is clamped so the page can't be
 * dragged off-screen, and double-tap toggles between fit and [DOUBLE_TAP_ZOOM].
 * [resetKey] (the page index) resets zoom/pan when the page changes.
 */
@Composable
private fun ZoomablePage(
    bitmap: ImageBitmap,
    contentDescription: String,
    resetKey: Any?,
    modifier: Modifier = Modifier,
) {
    var scale by remember(resetKey) { mutableFloatStateOf(1f) }
    var offset by remember(resetKey) { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(modifier = modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        val viewportW = constraints.maxWidth.toFloat()
        val viewportH = constraints.maxHeight.toFloat()
        val aspect = bitmap.height.toFloat() / bitmap.width.toFloat()
        val baseW = viewportW
        val baseH = baseW * aspect
        val center = Offset(viewportW / 2f, viewportH / 2f)

        // Keep the (centered) page within view: at a given scale you can pan at
        // most half the overflow in each axis.
        fun clamp(s: Float, o: Offset): Offset {
            val maxX = ((baseW * s - viewportW) / 2f).coerceAtLeast(0f)
            val maxY = ((baseH * s - viewportH) / 2f).coerceAtLeast(0f)
            return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
        }

        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
                .pointerInput(resetKey) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                        // Hold the pinch centroid steady while zooming, then apply pan.
                        val c = centroid - center
                        val zoomed = c - (c - offset) * (newScale / scale) + pan
                        scale = newScale
                        offset = clamp(newScale, zoomed)
                    }
                }
                .pointerInput(resetKey) {
                    detectTapGestures(
                        onDoubleTap = { tap ->
                            if (scale > MIN_ZOOM) {
                                scale = MIN_ZOOM
                                offset = clamp(MIN_ZOOM, Offset.Zero)
                            } else {
                                val c = tap - center
                                scale = DOUBLE_TAP_ZOOM
                                offset = clamp(DOUBLE_TAP_ZOOM, c - (c - offset) * DOUBLE_TAP_ZOOM)
                            }
                        },
                    )
                },
        )
    }
}

@Composable
private fun HighlightCallout(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                "Cited passage",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun PageBar(page: Int, pageCount: Int, onPrev: () -> Unit, onNext: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            IconButton(onClick = onPrev, enabled = page > 1) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous page")
            }
            Text(
                "Page $page of $pageCount",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMono),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = onNext, enabled = page < pageCount) {
                Icon(Icons.Filled.ChevronRight, contentDescription = "Next page")
            }
        }
    }
}

// ---- PdfRenderer plumbing ----

private fun openRenderer(path: String): PdfRenderer? = runCatching {
    val file = File(path)
    if (!file.exists()) return null
    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    PdfRenderer(pfd)
}.getOrNull()

private suspend fun renderPage(
    renderer: PdfRenderer,
    index: Int,
    highlights: List<PdfHighlighter.Box>,
    highlightColor: Int,
): ImageBitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            renderer.openPage(index).use { page ->
                val scale = RENDER_WIDTH_PX.toFloat() / page.width
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(RENDER_WIDTH_PX, height, Bitmap.Config.ARGB_8888)
                // PDFs assume a white page; fill first so transparent areas aren't black.
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                if (highlights.isNotEmpty()) drawHighlights(bmp, highlights, highlightColor)
                bmp.asImageBitmap()
            }
        }.getOrNull()
    }

/**
 * A clean opaque sage border over the cited passage (normalized page coords) with
 * only a light tint inside. The border marks the region clearly on a busy page;
 * the faint fill leaves the text underneath legible rather than darkening it.
 */
private fun drawHighlights(bmp: Bitmap, boxes: List<PdfHighlighter.Box>, color: Int) {
    val canvas = Canvas(bmp)
    val w = bmp.width.toFloat()
    val h = bmp.height.toFloat()
    val radius = 0.004f * w
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color // light sage tint (alpha set by caller)
        style = Paint.Style.FILL
    }
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color or 0xFF000000.toInt() // same sage, fully opaque
        style = Paint.Style.STROKE
        strokeWidth = (0.0025f * w).coerceAtLeast(2.5f)
    }
    for (b in boxes) {
        val l = b.x * w
        val t = b.y * h
        val r = (b.x + b.w) * w
        val btm = (b.y + b.h) * h
        canvas.drawRoundRect(l, t, r, btm, radius, radius, fill)
        canvas.drawRoundRect(l, t, r, btm, radius, radius, border)
    }
}

private fun PdfRenderer.closeQuietly() = runCatching { close() }
