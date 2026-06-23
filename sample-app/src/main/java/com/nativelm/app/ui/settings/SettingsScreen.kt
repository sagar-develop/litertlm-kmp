/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.ui.settings

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nativelm.app.BuildConfig
import com.nativelm.app.data.ThemeMode
import com.nativelm.app.llm.NativeLmViewModel
import com.nativelm.app.ui.lock.canAuthenticate
import com.nativelm.app.ui.sync.SyncControls
import com.nativelm.app.ui.theme.JetBrainsMono
import com.nativelm.app.ui.theme.WideContent

/** Hosted privacy policy (GitHub Pages). Mirrors PRIVACY.md / docs/privacy/. */
private const val PRIVACY_URL = "https://sagar-develop.github.io/litertlm-kmp/privacy/"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: NativeLmViewModel,
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenBenchmark: () -> Unit = {},
    showBack: Boolean = true,
) {
    val themeMode by vm.themeMode.collectAsState()
    val activeModel by vm.activeModelName.collectAsState()
    val hasToken by vm.hasToken.collectAsState()
    val appLockEnabled by vm.appLockEnabled.collectAsState()
    val outputLanguage by vm.outputLanguage.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    var showLanguagePicker by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val canAuth = remember { canAuthenticate(context) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    if (showBack) {
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
    ) { padding ->
        WideContent(padding) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Section("Appearance")
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Theme", style = MaterialTheme.typography.bodyLarge)
                SingleChoiceSegmentedButtonRow {
                    val modes = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK)
                    modes.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = themeMode == m,
                            onClick = { vm.setThemeMode(m) },
                            shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                        ) { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }) }
                    }
                }
            }

            Section("Models")
            NavRow(label = "Manage models", onClick = onOpenModels)
            ValueRow(label = "Active model", value = activeModel ?: "None")

            Section("Language")
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { showLanguagePicker = true }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Answer language", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "The AI answers in this language, even over English documents.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    outputLanguage.nativeName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Section("HuggingFace")
            ValueRow(
                label = "Access token",
                value = if (hasToken) "hf_••••••••" else "Not set",
                trailing = {
                    Row {
                        TextButton(onClick = onOpenModels) { Text("Manage") }
                        if (hasToken) TextButton(onClick = vm::clearToken) { Text("Clear") }
                    }
                },
            )

            Section("Security")
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("App lock", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (canAuth) {
                            "Require fingerprint, face, or your screen lock to open NativeLM."
                        } else {
                            "Set up a fingerprint or screen lock on your device to use this."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = appLockEnabled && canAuth,
                    onCheckedChange = { vm.setAppLockEnabled(it) },
                    enabled = canAuth,
                )
            }

            Section("Data")
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { confirmClear = true }
                    .padding(vertical = 12.dp),
            ) {
                Column {
                    Text("Clear all data", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                    Text(
                        "Deletes downloaded models and resets the app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Section("Backup")
            BackupControls(vm)

            Section("Sync")
            SyncControls(vm)

            Section("About")
            ValueRow(label = "Version", value = BuildConfig.VERSION_NAME, mono = true)
            ValueRow(label = "License", value = "AGPL-3.0", mono = true)
            NavRow(label = "Privacy policy") {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL)))
                }
            }

            Section("Developer")
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenBenchmark)
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Benchmark", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Measure decode tok/s, TTFT, load time, and peak RAM on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "No telemetry · No account · No upload",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all data?") },
            text = { Text("This deletes all downloaded models, your token, and app settings. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; vm.clearAllData() }) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }

    if (showLanguagePicker) {
        LanguagePickerSheet(
            current = outputLanguage,
            onSelect = vm::setOutputLanguage,
            onDismiss = { showLanguagePicker = false },
        )
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(16.dp))
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMono),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    HorizontalDivider(Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.outline)
}

@Composable
private fun NavRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ValueRow(
    label: String,
    value: String,
    mono: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        if (trailing != null) {
            trailing()
        } else {
            Text(
                value,
                style = if (mono) MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMono)
                else MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
