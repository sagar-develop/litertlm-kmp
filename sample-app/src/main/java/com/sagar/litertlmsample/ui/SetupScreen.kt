/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.litertlmsample.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sagar.litertlmsample.BuildConfig
import com.sagar.litertlmsample.llm.SetupState

@Composable
fun SetupScreen(setup: SetupState, onStart: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Set up the model",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                "First launch downloads the model from your configured URL.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            when (setup) {
                SetupState.Idle -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Source: ${BuildConfig.MODEL_URL.take(60)}${if (BuildConfig.MODEL_URL.length > 60) "..." else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (BuildConfig.MODEL_URL.contains("REPLACE_WITH_YOUR_HOST")) {
                        Text(
                            "Configure model.url in sample-app/local.properties before running. See README.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        Button(onClick = onStart) {
                            Text("Download & initialize")
                        }
                    }
                }
                is SetupState.Downloading -> {
                    Text(
                        "Downloading model… ${(setup.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinearProgressIndicator(
                        progress = { setup.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${setup.downloadedMb} / ${setup.totalMb} MB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SetupState.Initializing -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        "Initializing LiteRT-LM engine…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                SetupState.Ready -> Unit
                is SetupState.Failed -> {
                    Text(
                        "Setup failed",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        setup.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = onStart) { Text("Retry") }
                }
            }
        }
    }
}
