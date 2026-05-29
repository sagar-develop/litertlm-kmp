/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.litertlmsample.ui.chat

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sagar.litertlmsample.llm.ChatMessage
import com.sagar.litertlmsample.llm.NativeLmViewModel
import com.sagar.litertlmsample.ui.theme.JetBrainsMono
import com.sagar.litertlmsample.ui.theme.NativeLmMark

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: NativeLmViewModel,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val chat by vm.chat.collectAsState()
    val activeModel by vm.activeModelName.collectAsState()
    val metrics by vm.metrics.snapshot.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Follow the conversation as new messages arrive (newest is item 0 with
    // reverseLayout, so scrolling to 0 keeps the latest in view).
    LaunchedEffect(chat.messages.size) {
        if (chat.messages.isNotEmpty()) listState.animateScrollToItem(0)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("NativeLM", style = MaterialTheme.typography.titleMedium)
                        Text(
                            activeModel ?: "No model loaded",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.newChat() }, enabled = chat.messages.isNotEmpty()) {
                        Icon(Icons.Filled.Add, contentDescription = "New chat")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("New chat") },
                            onClick = { menuOpen = false; vm.newChat() },
                        )
                        DropdownMenuItem(text = { Text("Models") }, onClick = { menuOpen = false; onOpenModels() })
                        DropdownMenuItem(text = { Text("Settings") }, onClick = { menuOpen = false; onOpenSettings() })
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            Box(Modifier.weight(1f)) {
                if (chat.messages.isEmpty()) {
                    Greeting(hasModel = activeModel != null, onOpenModels = onOpenModels)
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        reverseLayout = true,
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(items = chat.messages.reversed(), key = { it.id }) { msg ->
                            MessageBubble(msg)
                        }
                    }
                }
            }

            // Quiet live telemetry while generating.
            if (chat.isGenerating) {
                val tps = metrics.tokens.tokensPerSecond
                val ttft = metrics.tokens.timeToFirstTokenMs
                val ttftText = if (ttft > 0) "TTFT ${"%.1f".format(ttft / 1000.0)}s · " else ""
                Text(
                    "$ttftText${"%.0f".format(tps)} tok/s",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                )
            }

            InputBar(
                value = chat.input,
                generating = chat.isGenerating,
                canSend = activeModel != null,
                onValueChange = vm::setInput,
                onSend = vm::sendChatMessage,
                onStop = vm::stopGeneration,
            )
        }
    }
}

@Composable
private fun Greeting(hasModel: Boolean, onOpenModels: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            NativeLmMark(size = 48.dp)
            Spacer(Modifier.height(20.dp))
            Text(
                text = if (hasModel) "How can I help you today?" else "No model loaded",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            if (!hasModel) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onOpenModels) { Text("Open Models") }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.role == ChatMessage.Role.User
    val bubbleColor = if (isUser) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surface
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface

    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .widthIn(max = 320.dp)
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        if (msg.text.isNotEmpty()) {
                            clipboard.setText(AnnotatedString(msg.text))
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        }
                    },
                ),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    if (isUser) "You" else "NativeLM",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                when {
                    isUser -> Text( // User input is plain text, not Markdown.
                        msg.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                    )
                    msg.text.isEmpty() -> Text(
                        if (msg.isStreaming) "…" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                    )
                    // Assistant replies arrive as Markdown — render rich text.
                    else -> MarkdownText(markdown = msg.text, color = textColor)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputBar(
    value: String,
    generating: Boolean,
    canSend: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = !generating && canSend,
                placeholder = { Text("Message NativeLM…") },
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f),
            )
            if (generating) {
                FilledIconButton(onClick = onStop) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop")
                }
            } else {
                FilledIconButton(onClick = onSend, enabled = canSend && value.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}
