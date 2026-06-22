/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.ui.benchmark

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nativelm.app.benchmark.BenchmarkUiState
import com.nativelm.app.benchmark.ModelResult
import com.nativelm.app.llm.ModelStatus
import com.nativelm.app.llm.NativeLmViewModel
import com.nativelm.app.ui.theme.JetBrainsMono
import com.nativelm.app.ui.theme.WideContent
import com.sagar.aicore.ModelRole

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkScreen(vm: NativeLmViewModel, onBack: () -> Unit) {
    val state by vm.benchmarkState.collectAsState()
    val models by vm.models.collectAsState()

    // Only already-downloaded primary LLMs are benchmarkable (no surprise multi-GB downloads).
    val downloaded = remember(models) {
        models.filter {
            it.descriptor.role == ModelRole.LLM_PRIMARY &&
                (it.status is ModelStatus.Downloaded || it.status is ModelStatus.Active)
        }
    }
    // Seed selection (all on) keyed on the downloaded set — avoids writing snapshot
    // state during composition; re-seeds if the available models change.
    val ids = downloaded.map { it.descriptor.id }
    val checked = remember(ids) {
        mutableStateMapOf<String, Boolean>().apply { ids.forEach { put(it, true) } }
    }
    val selectedIds = ids.filter { checked[it] == true }

    var repeats by remember { mutableStateOf(3) }
    var maxTokens by remember { mutableStateOf(128) }

    val running = state is BenchmarkUiState.Running

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Benchmark") },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !running) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        WideContent(padding) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                DeviceLine()

                SectionLabel("Method")
                Text(
                    "For trustworthy numbers: turn on airplane mode, unplug the charger, and let the " +
                        "device cool first. Each run records battery %, charging, and thermal state.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )

                when (val s = state) {
                    is BenchmarkUiState.Running -> RunningBlock(s, onCancel = vm::cancelBenchmark)
                    is BenchmarkUiState.Done -> ResultsBlock(
                        models = s.report.models,
                        onRunAgain = vm::dismissBenchmarkResult,
                        onExportJson = vm::exportBenchmarkJson,
                        onExportCsv = vm::exportBenchmarkCsv,
                        onShare = vm::shareBenchmarkJson,
                    )
                    is BenchmarkUiState.Failed -> {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Benchmark failed: ${s.message}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = vm::dismissBenchmarkResult) { Text("Back to setup") }
                    }
                    BenchmarkUiState.Idle -> SetupBlock(
                        downloaded = downloaded,
                        checked = checked,
                        repeats = repeats,
                        maxTokens = maxTokens,
                        onRepeats = { repeats = it },
                        onMaxTokens = { maxTokens = it },
                        onRun = { vm.runBenchmark(selectedIds, repeats, maxTokens) },
                        canRun = selectedIds.isNotEmpty(),
                    )
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun DeviceLine() {
    Spacer(Modifier.height(8.dp))
    Text(
        "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}",
        style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMono),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SetupBlock(
    downloaded: List<com.nativelm.app.llm.ModelUi>,
    checked: MutableMap<String, Boolean>,
    repeats: Int,
    maxTokens: Int,
    onRepeats: (Int) -> Unit,
    onMaxTokens: (Int) -> Unit,
    onRun: () -> Unit,
    canRun: Boolean,
) {
    SectionLabel("Models")
    if (downloaded.isEmpty()) {
        Text(
            "No language model downloaded yet. Download one from Models, then come back.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    } else {
        downloaded.forEach { m ->
            val id = m.descriptor.id
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { checked[id] = !(checked[id] ?: true) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked[id] ?: true, onCheckedChange = { checked[id] = it })
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(m.displayName, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${m.descriptor.sizeBytes / 1_048_576L} MB",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    SectionLabel("Runs per prompt")
    SegmentedChoice(
        options = listOf(3, 5),
        selected = repeats,
        label = { "$it" },
        onSelect = onRepeats,
    )

    SectionLabel("Max tokens / run")
    SegmentedChoice(
        options = listOf(128, 256),
        selected = maxTokens,
        label = { "$it" },
        onSelect = onMaxTokens,
    )

    Spacer(Modifier.height(20.dp))
    Button(
        onClick = onRun,
        enabled = canRun,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Run benchmark") }
}

@Composable
private fun RunningBlock(s: BenchmarkUiState.Running, onCancel: () -> Unit) {
    Spacer(Modifier.height(16.dp))
    Text(
        "Model ${s.modelIndex + 1} of ${s.modelCount}",
        style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMono),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(12.dp))
    Text(s.label, style = MaterialTheme.typography.bodyMedium)
    if (s.liveTps > 0f) {
        Text(
            "%.1f tok/s".format(s.liveTps),
            style = MaterialTheme.typography.headlineSmall.copy(fontFamily = JetBrainsMono),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    Spacer(Modifier.height(20.dp))
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
}

@Composable
private fun ResultsBlock(
    models: List<ModelResult>,
    onRunAgain: () -> Unit,
    onExportJson: (android.net.Uri) -> Unit,
    onExportCsv: (android.net.Uri) -> Unit,
    onShare: () -> Unit,
) {
    val jsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> if (uri != null) onExportJson(uri) }
    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri -> if (uri != null) onExportCsv(uri) }

    SectionLabel("Results")
    models.forEach { ResultCard(it) }

    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { jsonLauncher.launch("nativelm-benchmark.json") }, modifier = Modifier.weight(1f)) {
            Text("Export JSON")
        }
        OutlinedButton(onClick = { csvLauncher.launch("nativelm-benchmark.csv") }, modifier = Modifier.weight(1f)) {
            Text("Export CSV")
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) { Text("Share") }
        Button(onClick = onRunAgain, modifier = Modifier.weight(1f)) { Text("Run again") }
    }
}

@Composable
private fun ResultCard(m: ModelResult) {
    Spacer(Modifier.height(12.dp))
    Text(m.displayName, style = MaterialTheme.typography.titleSmall)
    if (m.error != null) {
        Text(
            "Failed: ${m.error}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 2.dp),
        )
        return
    }
    StatRow("Load (cold)", "${m.loadTimeMs} ms")
    StatRow("Peak RAM", "${m.peakPssMb} MB")
    // Header row for the per-prompt table.
    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Cell("prompt", 1.2f, header = true)
        Cell("TTFT ms", 1f, header = true)
        Cell("tok/s", 1f, header = true)
    }
    m.prompts.forEach { p ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Cell(p.name, 1.2f)
            Cell("%.0f".format(p.ttftMsMedian), 1f)
            Cell(if (p.decodeTpsMedian > 0) "%.1f".format(p.decodeTpsMedian) else "—", 1f)
        }
    }
    if (m.engineReported) {
        Text(
            "engine-reported metrics available",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
    HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outline)
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMono),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(text: String, weight: Float, header: Boolean = false) {
    Text(
        text,
        style = (if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall)
            .copy(fontFamily = JetBrainsMono),
        color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Start,
        modifier = Modifier.weight(weight),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> SegmentedChoice(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    SingleChoiceSegmentedButtonRow {
        options.forEachIndexed { i, opt ->
            SegmentedButton(
                selected = selected == opt,
                onClick = { onSelect(opt) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(label(opt)) }
        }
    }
}

@Composable
private fun SectionLabel(title: String) {
    Spacer(Modifier.height(16.dp))
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMono),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    HorizontalDivider(Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.outline)
}
