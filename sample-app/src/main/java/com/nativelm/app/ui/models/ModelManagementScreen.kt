/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.ui.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.platform.LocalContext
import com.sagar.aicore.ModelRole
import com.nativelm.app.llm.ModelStatus
import com.nativelm.app.llm.ModelUi
import com.nativelm.app.llm.NativeLmViewModel
import com.nativelm.app.ui.theme.JetBrainsMono
import com.nativelm.app.ui.theme.WideContent

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
    // Split the chat models by whether they need a Hugging Face account. The
    // ungated (Apache-2.0 / MIT) models download with no token — the friction-free
    // path for a fresh install — so they lead. The gated Gemma tier sits behind a
    // collapsed "Advanced" section together with the token field.
    val recommendedLlms = llms.filter { !it.descriptor.requiresAuth }
    val advancedLlms = llms.filter { it.descriptor.requiresAuth }
    // RAG models = embedders + the optional cross-encoder reranker.
    val ragModels = models.filter {
        it.descriptor.role == ModelRole.EMBEDDING || it.descriptor.role == ModelRole.RERANKER
    }
    val audio = models.filter { it.descriptor.role == ModelRole.SPEECH_TO_TEXT }
    val recommendedId = vm.recommendedModelId
    val recommendedEmbedderId = vm.recommendedEmbedderId
    val recommendedRerankerId = vm.recommendedRerankerId

    val context = LocalContext.current
    var advancedExpanded by remember { mutableStateOf(hasToken) }
    var licensePrompt by remember { mutableStateOf<ModelUi?>(null) }
    // Gated models need their HF license accepted with the token's account;
    // intercept the download to remind the user (with a link) rather than letting
    // them hit a bare 403.
    val onDownloadRequest: (ModelUi) -> Unit = { m ->
        if (m.descriptor.requiresAuth) licensePrompt = m else vm.download(m.descriptor.id)
    }

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
                // navigationBarsPadding: a custom bottomBar isn't auto-inset by
                // Scaffold, so without this the button renders under the system
                // nav bar (untappable on 3-button-nav devices).
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.navigationBarsPadding(),
                ) {
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
        WideContent(padding) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionHeader("Recommended")
                Text(
                    "Free, open models — no account needed. Downloads start right away.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
            }
            items(recommendedLlms, key = { it.descriptor.id }) { model ->
                ModelCard(model, vm, onDownloadRequest, recommended = model.descriptor.id == recommendedId)
            }

            item {
                SectionHeader("Document / RAG models")
                Text(
                    "for document chat (Projects). The Recommended embedder matches your device; " +
                        "EmbeddingGemma needs a Hugging Face token (see Advanced).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
            }
            items(ragModels, key = { it.descriptor.id }) { model ->
                ModelCard(
                    model,
                    vm,
                    onDownloadRequest,
                    recommended = model.descriptor.id == recommendedEmbedderId ||
                        model.descriptor.id == recommendedRerankerId,
                )
            }

            if (audio.isNotEmpty()) {
                item {
                    SectionHeader("Audio")
                    Text(
                        "for voice input (speech-to-text, on-device)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                    )
                }
                items(audio, key = { it.descriptor.id }) { model ->
                    ModelCard(model, vm, onDownloadRequest)
                }
            }

            if (advancedLlms.isNotEmpty()) {
                item {
                    AdvancedHeader(
                        expanded = advancedExpanded,
                        onToggle = { advancedExpanded = !advancedExpanded },
                    )
                }
                if (advancedExpanded) {
                    item { TokenCard(hasToken = hasToken, onSave = vm::setToken, onClear = vm::clearToken) }
                    items(advancedLlms, key = { it.descriptor.id }) { model ->
                        ModelCard(model, vm, onDownloadRequest)
                    }
                }
            }
        }
        }
    }

    licensePrompt?.let { m ->
        AlertDialog(
            onDismissRequest = { licensePrompt = null },
            title = { Text("Accept the model license") },
            text = {
                Text(
                    "${m.displayName} is a gated model. With your Hugging Face token you must " +
                        "also accept its license once on Hugging Face, or the download is denied. " +
                        "Open the model page, tap \"Agree and access\", then download.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    licensePrompt = null
                    vm.download(m.descriptor.id)
                }) { Text("Download") }
            },
            dismissButton = {
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(licensePageUrl(m.descriptor.url))),
                        )
                    }
                }) { Text("Open license page") }
            },
        )
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

/** Collapsible header for the gated (Hugging Face account) tier. */
@Composable
private fun AdvancedHeader(expanded: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(top = 8.dp, start = 4.dp, end = 4.dp, bottom = 4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "ADVANCED — HUGGING FACE ACCOUNT",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMono),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Gemma models. Free, but need a Hugging Face account + token and a one-time license accept.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
private fun ModelCard(
    model: ModelUi,
    vm: NativeLmViewModel,
    onDownload: (ModelUi) -> Unit,
    recommended: Boolean = false,
) {
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            model.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (model.supported) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (recommended && model.supported) {
                            androidx.compose.foundation.layout.Box(Modifier.padding(start = 8.dp)) {
                                AssistBadge("Recommended")
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        metadataLine(model),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ModelAction(model, vm, onDownload)
            }

            if (model.descriptor.role == ModelRole.LLM_PRIMARY) {
                Spacer(Modifier.height(10.dp))
                ModalityChips(model.descriptor.supportsVision)
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
private fun ModelAction(model: ModelUi, vm: NativeLmViewModel, onDownload: (ModelUi) -> Unit) {
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
            OutlinedButton(onClick = { onDownload(model) }, enabled = model.supported) { Text(label) }
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

/** Supported input-type chips for a model (Text always; Image for vision bundles). */
@Composable
private fun ModalityChips(supportsVision: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        InputTypeChip("Text")
        if (supportsVision) InputTypeChip("Image")
    }
}

@Composable
private fun InputTypeChip(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

/** Strip `/resolve/main/<file>` to get the HF model page (where the license is accepted). */
private fun licensePageUrl(downloadUrl: String): String = downloadUrl.substringBefore("/resolve/")
