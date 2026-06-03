/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.ui.settings

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.nativelm.app.llm.NativeLmViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MIN_PASSPHRASE_LEN = 8

/**
 * The "Back up my data" / "Restore from backup" controls plus their passphrase dialogs,
 * SAF file pickers, and the in-progress overlay. Everything is local: the encrypted file
 * goes wherever the user points the system picker; nothing is uploaded.
 */
@Composable
fun BackupControls(vm: NativeLmViewModel) {
    val busy by vm.backupBusy.collectAsState()

    // Both flows are *picker-first*: pick the file via SAF, then ask for the passphrase.
    // This means the passphrase is never held in state across the picker activity (which
    // can recreate this screen) — only a non-secret content Uri is, and only briefly.
    var pendingExportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }

    var pendingImportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }

    val createLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) {
            pendingExportUri = uri
            showExportDialog = true
        }
    }

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            pendingImportUri = uri
            showImportDialog = true
        }
    }

    BackupRow(
        title = "Back up my data",
        subtitle = "Save an encrypted copy you control. Nothing is uploaded.",
        onClick = {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
            createLauncher.launch("nativelm-backup-$stamp.nlmbak")
        },
    )
    BackupRow(
        title = "Restore from backup",
        subtitle = "Import a .nlmbak file. Restored data is added to what's already here.",
        onClick = { openLauncher.launch(arrayOf("application/octet-stream", "application/zip", "*/*")) },
    )

    if (showExportDialog) {
        PassphraseDialog(
            title = "Encrypt this backup",
            message = "Choose a passphrase. You'll need it to restore — there is no recovery if you lose it.",
            confirmField = true,
            actionLabel = "Encrypt & save",
            onDismiss = {
                showExportDialog = false
                pendingExportUri = null
            },
            onConfirm = { pass ->
                showExportDialog = false
                val uri = pendingExportUri
                pendingExportUri = null
                if (uri != null) vm.exportBackup(uri, pass) else pass.fill(' ')
            },
        )
    }

    if (showImportDialog) {
        PassphraseDialog(
            title = "Enter backup passphrase",
            message = "Enter the passphrase this backup was created with.",
            confirmField = false,
            actionLabel = "Restore",
            onDismiss = {
                showImportDialog = false
                pendingImportUri = null
            },
            onConfirm = { pass ->
                showImportDialog = false
                val uri = pendingImportUri
                pendingImportUri = null
                if (uri != null) vm.importBackup(uri, pass) else pass.fill(' ')
            },
        )
    }

    if (busy != null) {
        AlertDialog(
            onDismissRequest = { /* not dismissible while working */ },
            confirmButton = {},
            title = { Text(busy!!) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Spacer(Modifier.width(16.dp))
                    Text("Everything stays on your device.")
                }
            },
        )
    }
}

@Composable
private fun BackupRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PassphraseDialog(
    title: String,
    message: String,
    confirmField: Boolean,
    actionLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit,
) {
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }

    val tooShort = pass.length < MIN_PASSPHRASE_LEN
    val mismatch = confirmField && pass != pass2
    val error = when {
        pass.isEmpty() -> null
        tooShort -> "Use at least $MIN_PASSPHRASE_LEN characters."
        mismatch -> "Passphrases don't match."
        else -> null
    }
    val canConfirm = pass.isNotEmpty() && !tooShort && !mismatch

    val transform = if (visible) VisualTransformation.None else PasswordVisualTransformation()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text("Passphrase") },
                    singleLine = true,
                    visualTransformation = transform,
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                if (visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = if (visible) "Hide passphrase" else "Show passphrase",
                            )
                        }
                    },
                )
                if (confirmField) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pass2,
                        onValueChange = { pass2 = it },
                        label = { Text("Confirm passphrase") },
                        singleLine = true,
                        visualTransformation = transform,
                    )
                }
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = canConfirm, onClick = { onConfirm(pass.toCharArray()) }) {
                Text(actionLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
