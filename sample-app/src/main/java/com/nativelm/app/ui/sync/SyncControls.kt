/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.ui.sync

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nativelm.app.llm.NativeLmViewModel
import com.nativelm.app.sync.SyncPeer
import com.nativelm.app.sync.SyncState
import com.nativelm.app.ui.theme.JetBrainsMono

/**
 * "Send to" / "Receive from" a nearby device, with a single state-driven dialog over the
 * ViewModel's [NativeLmViewModel.syncState]. Everything is local: discovery is mDNS, the
 * transfer is a TCP socket on the same Wi-Fi, and the bytes are an already-encrypted
 * bundle. No cloud, no account.
 */
@Composable
fun SyncControls(vm: NativeLmViewModel) {
    val state by vm.syncState.collectAsState()
    var open by remember { mutableStateOf(false) }

    SyncRow(
        title = "Send to a nearby device",
        subtitle = "Beam your data to your phone or tablet over Wi-Fi. Nothing leaves the network.",
        onClick = { open = true; vm.startSyncSend() },
    )
    SyncRow(
        title = "Receive from a nearby device",
        subtitle = "Pull data from another device running NativeLM on the same Wi-Fi.",
        onClick = { open = true; vm.startSyncReceive() },
    )

    if (open && state !is SyncState.Idle) {
        val close = { open = false; vm.cancelSync() }
        AlertDialog(
            onDismissRequest = {
                // Only dismissible at terminal states; mid-transfer the user must cancel explicitly.
                if (state.isTerminal()) close()
            },
            title = { Text(state.title()) },
            text = { SyncDialogBody(state, vm) },
            confirmButton = {
                TextButton(onClick = close) {
                    Text(if (state.isTerminal()) "Done" else "Cancel")
                }
            },
        )
    }
}

@Composable
private fun SyncDialogBody(state: SyncState, vm: NativeLmViewModel) {
    Column {
        when (state) {
            is SyncState.Advertising -> {
                Text(
                    "On your other device, open NativeLM → Settings → “Receive from a nearby device,” pick this one, then enter:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        state.code,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontFamily = JetBrainsMono,
                            letterSpacing = 8.sp,
                            fontSize = 34.sp,
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Waiting for the other device…", style = MaterialTheme.typography.bodySmall)
                }
            }

            is SyncState.Sending -> Progress("Sending…", state.percent)
            SyncState.SendComplete -> Text("Sent. The other device now has your data.")

            SyncState.Discovering -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Looking for nearby devices…")
            }

            is SyncState.PeersFound -> Column {
                Text(
                    "Tap the device that's sending:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                state.peers.forEach { peer ->
                    PeerRow(peer) { vm.connectSyncPeer(peer) }
                }
            }

            is SyncState.Receiving -> Progress("Receiving…", state.percent)

            SyncState.AwaitingCode -> CodeEntry(onSubmit = { vm.importSyncReceived(it) })

            is SyncState.ReceiveComplete -> Text(
                "Received ${state.projects} project(s) and ${state.documents} source(s).",
            )

            is SyncState.Failed -> Text(
                state.message,
                color = MaterialTheme.colorScheme.error,
            )

            SyncState.Idle -> {}
        }
    }
}

@Composable
private fun Progress(label: String, percent: Int) {
    Column {
        Text("$label $percent%", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { percent / 100f },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PeerRow(peer: SyncPeer, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Text(peer.name, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun CodeEntry(onSubmit: (CharArray) -> Unit) {
    var code by remember { mutableStateOf("") }
    Column {
        Text(
            "Enter the 6-digit code shown on the sending device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) code = it },
            label = { Text("Code") },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleLarge.copy(
                fontFamily = FontFamily.Monospace,
                letterSpacing = 6.sp,
            ),
        )
        Spacer(Modifier.height(8.dp))
        TextButton(
            enabled = code.length == 6,
            onClick = { onSubmit(code.toCharArray()) },
        ) {
            Text("Import")
        }
    }
}

@Composable
private fun SyncRow(title: String, subtitle: String, onClick: () -> Unit) {
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

private fun SyncState.isTerminal(): Boolean =
    this is SyncState.SendComplete || this is SyncState.ReceiveComplete || this is SyncState.Failed

private fun SyncState.title(): String = when (this) {
    is SyncState.Advertising, is SyncState.Sending, SyncState.SendComplete -> "Send to device"
    SyncState.Discovering, is SyncState.PeersFound, is SyncState.Receiving,
    SyncState.AwaitingCode, is SyncState.ReceiveComplete -> "Receive from device"
    is SyncState.Failed -> "Sync failed"
    SyncState.Idle -> ""
}
