/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.ui.chat

import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nativelm.app.llm.ChatMessage
import com.nativelm.app.llm.ConversationSummary
import com.nativelm.app.llm.NativeLmViewModel
import com.nativelm.app.ui.theme.JetBrainsMono
import com.nativelm.app.ui.theme.NativeLmMark
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: NativeLmViewModel,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDocuments: () -> Unit,
) {
    val chat by vm.chat.collectAsState()
    val activeModel by vm.activeModelName.collectAsState()
    val metrics by vm.metrics.snapshot.collectAsState()
    val conversations by vm.conversations.collectAsState()
    val currentId by vm.currentConversationId.collectAsState()
    val ragEnabled by vm.ragEnabled.collectAsState()
    val documents by vm.documents.collectAsState()
    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var renameTarget by remember { mutableStateOf<ConversationSummary?>(null) }

    // Follow the conversation as new messages arrive (newest is item 0 with
    // reverseLayout, so scrolling to 0 keeps the latest in view).
    LaunchedEffect(chat.messages.size) {
        if (chat.messages.isNotEmpty()) listState.animateScrollToItem(0)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ConversationDrawer(
                conversations = conversations,
                currentId = currentId,
                onNewChat = { scope.launch { drawerState.close() }; vm.newChat() },
                onOpen = { id -> scope.launch { drawerState.close() }; vm.openConversation(id) },
                onRename = { renameTarget = it },
                onDelete = { vm.deleteConversation(it) },
                onOpenModels = { scope.launch { drawerState.close() }; onOpenModels() },
                onOpenSettings = { scope.launch { drawerState.close() }; onOpenSettings() },
                onOpenDocuments = { scope.launch { drawerState.close() }; onOpenDocuments() },
            )
        },
    ) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Filled.Menu, contentDescription = "Conversations")
                    }
                },
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
                // While the engine re-prefills a reopened conversation's history.
                if (chat.isWarming) BuildingUnderstanding()
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

            GroundingToggle(
                enabled = ragEnabled,
                docCount = documents.size,
                onToggle = vm::setRagEnabled,
                onManage = onOpenDocuments,
            )

            InputBar(
                value = chat.input,
                generating = chat.isGenerating,
                canSend = activeModel != null && !chat.isWarming,
                onValueChange = vm::setInput,
                onSend = vm::sendChatMessage,
                onStop = vm::stopGeneration,
            )
        }
    }
    } // end ModalNavigationDrawer content

    renameTarget?.let { target ->
        RenameDialog(
            current = target.title,
            onDismiss = { renameTarget = null },
            onConfirm = { newTitle -> vm.renameConversation(target.id, newTitle); renameTarget = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationDrawer(
    conversations: List<ConversationSummary>,
    currentId: Long,
    onNewChat: () -> Unit,
    onOpen: (Long) -> Unit,
    onRename: (ConversationSummary) -> Unit,
    onDelete: (Long) -> Unit,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDocuments: () -> Unit,
) {
    var menuForId by remember { mutableStateOf<Long?>(null) }
    ModalDrawerSheet {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            Text(
                "NativeLM",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(16.dp),
            )
            NavigationDrawerItem(
                label = { Text("New chat") },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                selected = false,
                onClick = onNewChat,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(items = conversations, key = { it.id }) { c ->
                    Box {
                        NavigationDrawerItem(
                            label = { Text(c.title, maxLines = 1) },
                            selected = c.id == currentId,
                            onClick = { onOpen(c.id) },
                            badge = {
                                IconButton(onClick = { menuForId = c.id }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = "Options")
                                }
                            },
                        )
                        DropdownMenu(
                            expanded = menuForId == c.id,
                            onDismissRequest = { menuForId = null },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                onClick = { menuForId = null; onRename(c) },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                onClick = { menuForId = null; onDelete(c.id) },
                            )
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            NavigationDrawerItem(
                label = { Text("Documents") },
                selected = false,
                onClick = onOpenDocuments,
            )
            NavigationDrawerItem(
                label = { Text("Models") },
                selected = false,
                onClick = onOpenModels,
            )
            NavigationDrawerItem(
                label = { Text("Settings") },
                selected = false,
                onClick = onOpenSettings,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun RenameDialog(
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename conversation") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Shown over the thread while the engine re-prefills a reopened conversation's
 *  history into the KV cache — the "catching up on this conversation" moment. */
@Composable
private fun BuildingUnderstanding() {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.5.dp,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Building understanding…",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Catching up on this conversation",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    msg.text.isEmpty() ->
                        if (msg.isStreaming) TypingIndicator()
                        else Text("", style = MaterialTheme.typography.bodyMedium, color = textColor)
                    // Assistant replies arrive as Markdown — render rich text.
                    else -> MarkdownText(markdown = msg.text, color = textColor)
                }
                if (!isUser && msg.citations.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    val sources = msg.citations
                        .distinctBy { it.documentTitle to it.pageNumber }
                        .take(3)
                        .joinToString("  ·  ") {
                            if (it.pageNumber > 0) "${it.documentTitle} p.${it.pageNumber}" else it.documentTitle
                        }
                    Text(
                        "Sources: $sources",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Three-dot "thinking" loader shown while the model is processing before the
 *  first token arrives (and between tokens if the stream stalls). */
@Composable
private fun TypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 600, delayMillis = i * 150),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha)),
            )
        }
    }
}

/** Toggle that grounds each turn against imported documents, with a shortcut to manage them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroundingToggle(
    enabled: Boolean,
    docCount: Int,
    onToggle: (Boolean) -> Unit,
    onManage: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = enabled,
            onClick = { onToggle(!enabled) },
            label = {
                Text(if (docCount > 0) "Ground in documents · $docCount" else "Ground in documents")
            },
            leadingIcon = {
                Icon(
                    Icons.Filled.Description,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onManage) { Text(if (docCount > 0) "Manage" else "Add") }
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
