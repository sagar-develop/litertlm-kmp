/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.ui.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
    // Open the renderer once for this file; close it on dispose.
    val renderer = remember(target.localPath) { openRenderer(target.localPath) }
    DisposableEffect(target.localPath) {
        onDispose { renderer?.closeQuietly() }
    }

    val pageCount = renderer?.pageCount ?: 0
    var pageIndex by remember(target.localPath) {
        mutableIntStateOf((target.initialPage - 1).coerceIn(0, (pageCount - 1).coerceAtLeast(0)))
    }
    var bitmap by remember(target.localPath) { mutableStateOf<ImageBitmap?>(null) }
    val renderLock = remember(target.localPath) { Mutex() }

    LaunchedEffect(target.localPath, pageIndex) {
        if (renderer == null || pageCount == 0) return@LaunchedEffect
        bitmap = renderLock.withLock { renderPage(renderer, pageIndex) }
    }

    Column(modifier.fillMaxSize()) {
        if (target.highlight.isNotBlank()) {
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
                else -> Image(
                    bitmap = bitmap!!,
                    contentDescription = "Page ${pageIndex + 1}",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
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

private suspend fun renderPage(renderer: PdfRenderer, index: Int): ImageBitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            renderer.openPage(index).use { page ->
                val scale = RENDER_WIDTH_PX.toFloat() / page.width
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(RENDER_WIDTH_PX, height, Bitmap.Config.ARGB_8888)
                // PDFs assume a white page; fill first so transparent areas aren't black.
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp.asImageBitmap()
            }
        }.getOrNull()
    }

private fun PdfRenderer.closeQuietly() = runCatching { close() }
