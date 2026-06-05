/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.ui.onboarding

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private data class Slide(val icon: ImageVector, val title: String, val body: String)

private val SLIDES = listOf(
    Slide(
        Icons.Outlined.Lock,
        "Private by design",
        "NativeLM runs entirely on your phone. No account, no servers, no telemetry — your documents and conversations never leave the device.",
    ),
    Slide(
        Icons.Outlined.Forum,
        "Capable local chat",
        "A real LLM running on-device. Ask questions, draft, summarize, and reason — fully offline once a model is downloaded.",
    ),
    Slide(
        Icons.Outlined.Description,
        "Chat with your documents",
        "Ground answers in your own files with on-device retrieval. Your documents stay local — nothing is uploaded.",
    ),
    Slide(
        Icons.Outlined.CloudDownload,
        "You control the models",
        "Start with a free, open model — no account needed. Advanced Gemma models are a tap away with a free Hugging Face token. Switch whenever you like.",
    ),
)

private const val SOURCE_URL = "https://github.com/sagar-develop/litertlm-kmp"
private const val GEMMA_TERMS_URL = "https://ai.google.dev/gemma/terms"

@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { SLIDES.size })
    val scope = rememberCoroutineScope()
    val isLast = pagerState.currentPage == SLIDES.lastIndex

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Background stays full-bleed; content is inset past the status/nav bars
            // (edge-to-edge is on, so without this the bottom Skip/Next row and top
            // content draw under the system bars — worst on 3-button-nav devices).
            .systemBarsPadding()
            .padding(24.dp),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val slide = SLIDES[page]
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 96.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = slide.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(44.dp),
                    )
                }
                Spacer(Modifier.height(40.dp))
                Text(
                    text = slide.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = slide.body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Terms / source gate: shown on the last slide before "Get started".
            // NativeLM is AGPL-3.0 (source linked); downloaded models carry their
            // own licenses (e.g. Google's Gemma Terms for the Gemma tier).
            if (isLast) {
                TermsFooter()
                Spacer(Modifier.height(4.dp))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = onFinish) {
                    Text(if (isLast) "" else "Skip")
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    repeat(SLIDES.size) { i ->
                        val active = i == pagerState.currentPage
                        val color by animateColorAsState(
                            if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                            label = "dot",
                        )
                        Box(
                            Modifier
                                .size(if (active) 9.dp else 7.dp)
                                .clip(CircleShape)
                                .background(color),
                        )
                    }
                }

                Button(
                    onClick = {
                        if (isLast) onFinish()
                        else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    },
                ) {
                    Text(if (isLast) "Get started" else "Next")
                }
            }
        }
    }
}

/** Source + model-license disclosure shown on the final onboarding slide. */
@Composable
private fun TermsFooter() {
    val context = LocalContext.current
    fun open(url: String) = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Open source (AGPL-3.0). Downloaded models are subject to their own licenses.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { open(SOURCE_URL) }) { Text("Source code") }
            TextButton(onClick = { open(GEMMA_TERMS_URL) }) { Text("Gemma Terms") }
        }
    }
}
