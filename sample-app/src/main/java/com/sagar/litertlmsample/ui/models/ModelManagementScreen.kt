/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.litertlmsample.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.sagar.aicore.ModelRole
import com.sagar.litertlmsample.llm.ModelStatus
import com.sagar.litertlmsample.llm.ModelUi
import com.sagar.litertlmsample.llm.NativeLmViewModel
import com.sagar.litertlmsample.ui.theme.JetBrainsMono

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagementScreen(
    vm: NativeLmViewModel,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onContinue: () -> Unit,
) {
    val models by vm.models.collectAsState()
    val hasToken by vm.hasToken.collectAsState()
    val activeId by vm.activeModelId.collectAsState()

    val llms = models.filter { it.descriptor.role == ModelRole.LLM_PRIMARY }
    val embedders = models.filter { it.descriptor.role == ModelRole.EMBEDDING }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Models") },
                navigationIcon = {
                    if (canGoBack) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            if (activeId != null) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Button(
                        onClick = onContinue,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) { Text("Continue to chat") }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { TokenCard(hasToken = hasToken, onSave = vm::setToken, onClear = vm::clearToken) }

            item { SectionHeader("Language models") }
            items(llms, key = { it.descriptor.id }) { model ->
                ModelCard(model, vm)
            }

            item {
                SectionHeader("Document / RAG models")
                Text(
                    "for upcoming document chat",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
            }
            items(embedders, key = { it.descriptor.id }) { model ->
                ModelCard(model, vm)
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMono),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TokenCard(hasToken: Boolean, onSave: (String) -> Unit, onClear: () -> Unit) {
    var editing by remember { mutableStateOf(!hasToken) }
    var token by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("HuggingFace token", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(
                "Used to download license-gated models directly from Hugging Face.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            if (editing) {
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    placeholder = { Text("hf_…") },
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (visible) "Hide token" else "Show token",
                            )
                        }
                    },
                    keyboardActions = KeyboardActions(onDone = {
                        if (token.isNotBlank()) { onSave(token); editing = false }
                    }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { if (token.isNotBlank()) { onSave(token); editing = false } },
                        enabled = token.isNotBlank(),
                    ) { Text("Save") }
                    if (hasToken) TextButton(onClick = { editing = false }) { Text("Cancel") }
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "hf_••••••••••••",
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMono),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row {
                        TextButton(onClick = { token = ""; editing = true }) { Text("Replace") }
                        TextButton(onClick = { onClear(); token = ""; editing = true }) { Text("Clear") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelCard(model: ModelUi, vm: NativeLmViewModel) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        model.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (model.supported) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        metadataLine(model),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ModelAction(model, vm)
            }

            val status = model.status
            if (status is ModelStatus.Downloading) {
                Spacer(Modifier.height(12.dp))
                // Some hosts (e.g. Hugging Face's xet CDN) stream without a
                // Content-Length, so the total is unknown — show an indeterminate
                // bar and just the downloaded MB in that case.
                val hasTotal = status.totalMb > 0
                if (hasTotal) {
                    LinearProgressIndicator(
                        progress = { status.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (hasTotal) "${status.downloadedMb} / ${status.totalMb} MB"
                    else "${status.downloadedMb} MB",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (status is ModelStatus.Failed) {
                Spacer(Modifier.height(8.dp))
                Text(
                    status.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!model.supported) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Not enough RAM on this device",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ModelAction(model: ModelUi, vm: NativeLmViewModel) {
    val id = model.descriptor.id
    val isLlm = model.descriptor.role == ModelRole.LLM_PRIMARY
    when (val status = model.status) {
        is ModelStatus.Downloading -> IconButton(onClick = { vm.cancelDownload(id) }) {
            Icon(Icons.Filled.Close, contentDescription = "Cancel")
        }
        ModelStatus.Active -> AssistBadge("Active")
        ModelStatus.Downloaded -> Row(verticalAlignment = Alignment.CenterVertically) {
            if (isLlm) TextButton(onClick = { vm.setActive(id) }) { Text("Set active") }
            TextButton(onClick = { vm.deleteModel(id) }) { Text("Delete") }
        }
        is ModelStatus.Failed, ModelStatus.NotDownloaded -> {
            val label = if (status is ModelStatus.Failed) "Retry" else "Download"
            OutlinedButton(onClick = { vm.download(id) }, enabled = model.supported) { Text(label) }
        }
    }
}

@Composable
private fun AssistBadge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

private fun metadataLine(model: ModelUi): String {
    val d = model.descriptor
    val size = formatSize(d.sizeBytes)
    val ram = if (d.minDeviceRamMb > 0) " · needs ${d.minDeviceRamMb / 1000} GB RAM" else ""
    return "${d.fileName} · $size$ram"
}

private fun formatSize(bytes: Long): String {
    val gb = bytes / 1_000_000_000.0
    if (gb >= 1.0) return String.format("%.2f GB", gb)
    val mb = bytes / 1_000_000.0
    return String.format("%.1f MB", mb)
}
