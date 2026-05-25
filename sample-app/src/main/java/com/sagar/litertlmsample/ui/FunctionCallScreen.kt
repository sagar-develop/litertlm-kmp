/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.litertlmsample.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.sagar.litertlmsample.llm.SampleViewModel

@Composable
fun FunctionCallScreen(vm: SampleViewModel) {
    val state by vm.functionCall.collectAsState()
    val scroll = rememberScrollState()

    // Auto-scroll to bottom when extraction completes so the result is visible
    // without manual scrolling. Triggers when extractedJson populates.
    androidx.compose.runtime.LaunchedEffect(state.extractedJson, state.isGenerating) {
        if (state.extractedJson.isNotEmpty() || state.isGenerating) {
            scroll.animateScrollTo(scroll.maxValue)
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Structured-output extraction",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "The library converts a typed ToolSchema.Definition into OpenAPI JSON, sends it to LiteRT-LM with automaticToolCalling=false, and returns the model's parsed arguments.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = state.prompt,
            onValueChange = vm::setFunctionCallPrompt,
            label = { Text("Prompt") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isGenerating,
            maxLines = 3,
        )

        Text("Schema", style = MaterialTheme.typography.labelLarge)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp),
        ) {
            Text(
                state.schemaPreview,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Button(
            onClick = vm::runFunctionCall,
            enabled = !state.isGenerating,
        ) {
            Text(if (state.isGenerating) "Extracting…" else "Run extraction")
        }

        Spacer(Modifier.height(4.dp))
        Text("Extracted", style = MaterialTheme.typography.labelLarge)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(12.dp),
        ) {
            Text(
                text = when {
                    state.error != null -> "[error: ${state.error}]"
                    state.extractedJson.isNotEmpty() -> state.extractedJson
                    state.isGenerating -> "…"
                    else -> "Tap Run extraction"
                },
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
