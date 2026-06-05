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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nativelm.app.llm.ChatMessage
import com.nativelm.app.llm.ConversationSummary
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.core.content.ContextCompat
import com.nativelm.app.llm.NativeLmViewModel
import com.nativelm.app.llm.VoiceState
import com.nativelm.app.ui.settings.LanguagePickerSheet
import com.nativelm.app.llm.ProjectSummary
import com.sagar.aicore.rag.Citation
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
    onOpenPdf: () -> Unit,
    onOpenStudio: () -> Unit,
    showNavRail: Boolean = false,
    expanded: Boolean = false,
) {
    val chat by vm.chat.collectAsState()
    val activeModel by vm.activeModelName.collectAsState()
    val conversations by vm.conversations.collectAsState()
    val currentId by vm.currentConversationId.collectAsState()
    val projects by vm.projects.collectAsState()
    val projectName by vm.currentProjectName.collectAsState()
    val outputLanguage by vm.outputLanguage.collectAsState()
    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var renameTarget by remember { mutableStateOf<ConversationSummary?>(null) }
    var projectRenameTarget by remember { mutableStateOf<ProjectSummary?>(null) }
    var saveSheetText by remember { mutableStateOf<String?>(null) }
    var newProjectName by remember { mutableStateOf<String?>(null) } // non-null => dialog open
    var pendingSaveText by remember { mutableStateOf<String?>(null) } // save after creating project
    var showLanguagePicker by remember { mutableStateOf(false) }
    val voiceState by vm.voiceState.collectAsState()
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startVoiceRecording()
        else Toast.makeText(context, "Microphone permission is needed for voice input.", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(chat.messages.size) {
        if (chat.messages.isNotEmpty()) listState.animateScrollToItem(0)
    }

    // One-shot messages from the VM (e.g. a citation whose source file is gone).
    val transientMessage by vm.transientMessage.collectAsState()
    LaunchedEffect(transientMessage) {
        transientMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.consumeTransientMessage()
        }
    }

    // Tapping a bubble's save button: inside a project, save directly; in default
    // chat, open the bottom sheet to choose a notebook.
    fun onSaveBubble(text: String) {
        if (projectName != null) {
            vm.saveBubbleToCurrentProject(text)
            Toast.makeText(context, "Saved to $projectName", Toast.LENGTH_SHORT).show()
        } else {
            saveSheetText = text
        }
    }

    fun toggleMic() {
        when (voiceState) {
            VoiceState.Recording -> vm.stopVoiceAndTranscribe()
            VoiceState.Transcribing -> Unit
            VoiceState.Idle -> {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) vm.startVoiceRecording() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    // The thread + composer. Shared by the Compact/Medium drawer layout and the
    // Expanded two-pane layout; the hamburger only appears when there's no
    // persistent conversations pane (i.e. not Expanded).
    val chatContent: @Composable () -> Unit = {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        if (!expanded) {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Filled.Menu, contentDescription = "Menu")
                            }
                        }
                    },
                    title = {
                        Column {
                            Text(projectName ?: "NativeLM", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (projectName != null) "Project · grounded" else (activeModel ?: "No model loaded"),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = { showLanguagePicker = true },
                            contentPadding = PaddingValues(horizontal = 10.dp),
                        ) {
                            Text(
                                outputLanguage.code.uppercase(),
                                style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMono),
                            )
                        }
                        if (projectName != null) {
                            IconButton(onClick = onOpenStudio) {
                                Icon(Icons.Filled.AutoAwesome, contentDescription = "Studio")
                            }
                            IconButton(onClick = onOpenDocuments) {
                                Icon(Icons.Filled.Description, contentDescription = "Sources")
                            }
                        }
                        IconButton(onClick = { vm.newChat() }, enabled = chat.messages.isNotEmpty() || projectName != null) {
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
                        Greeting(
                            hasModel = activeModel != null,
                            projectName = projectName,
                            onOpenModels = onOpenModels,
                            onSuggestion = { vm.setInput(it); vm.sendChatMessage() },
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            reverseLayout = true,
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(items = chat.messages.reversed(), key = { it.id }) { msg ->
                                MessageBubble(
                                    msg,
                                    onSave = { onSaveBubble(msg.text) },
                                    onCitationClick = { citation ->
                                        vm.openCitation(citation, onOpen = onOpenPdf)
                                    },
                                )
                            }
                        }
                    }
                    if (chat.isWarming) BuildingUnderstanding()
                }

                InputBar(
                    value = chat.input,
                    generating = chat.isGenerating,
                    canSend = activeModel != null && !chat.isWarming,
                    voiceState = voiceState,
                    onValueChange = vm::setInput,
                    onSend = vm::sendChatMessage,
                    onStop = vm::stopGeneration,
                    onMicToggle = { toggleMic() },
                )
            }
        }
    }

    if (expanded) {
        // Two-pane: persistent conversations list │ thread + composer.
        Row(Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier.width(320.dp).fillMaxHeight(),
                color = MaterialTheme.colorScheme.surface,
            ) {
                ConversationsList(
                    conversations = conversations,
                    projects = projects,
                    currentId = currentId,
                    onNewChat = { vm.newChat() },
                    onOpen = { vm.openConversation(it) },
                    onRename = { renameTarget = it },
                    onDelete = { vm.deleteConversation(it) },
                    onOpenProject = { vm.openProject(it) },
                    onNewProject = { newProjectName = "" },
                    onRenameProject = { projectRenameTarget = it },
                    onDeleteProject = { vm.deleteProject(it) },
                    onOpenModels = onOpenModels,
                    onOpenSettings = onOpenSettings,
                )
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outline)
            Box(Modifier.weight(1f)) { chatContent() }
        }
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ChatDrawer(
                    conversations = conversations,
                    projects = projects,
                    currentId = currentId,
                    onNewChat = { scope.launch { drawerState.close() }; vm.newChat() },
                    onOpen = { id -> scope.launch { drawerState.close() }; vm.openConversation(id) },
                    onRename = { renameTarget = it },
                    onDelete = { vm.deleteConversation(it) },
                    onOpenProject = { id -> scope.launch { drawerState.close() }; vm.openProject(id) },
                    onNewProject = { newProjectName = "" },
                    onRenameProject = { projectRenameTarget = it },
                    onDeleteProject = { vm.deleteProject(it) },
                    onOpenModels = { scope.launch { drawerState.close() }; onOpenModels() },
                    onOpenSettings = { scope.launch { drawerState.close() }; onOpenSettings() },
                )
            },
        ) {
            chatContent()
        }
    }

    renameTarget?.let { target ->
        TextInputDialog(
            title = "Rename conversation",
            initial = target.title,
            confirmLabel = "Save",
            onDismiss = { renameTarget = null },
            onConfirm = { newTitle -> vm.renameConversation(target.id, newTitle); renameTarget = null },
        )
    }

    projectRenameTarget?.let { target ->
        TextInputDialog(
            title = "Rename project",
            initial = target.name,
            confirmLabel = "Save",
            onDismiss = { projectRenameTarget = null },
            onConfirm = { newName -> vm.renameProject(target.id, newName); projectRenameTarget = null },
        )
    }

    newProjectName?.let {
        TextInputDialog(
            title = "New project",
            initial = "",
            confirmLabel = "Create",
            placeholder = "Project name",
            onDismiss = { newProjectName = null; pendingSaveText = null },
            onConfirm = { name ->
                val id = vm.createProject(name)
                val toSave = pendingSaveText
                newProjectName = null
                pendingSaveText = null
                if (toSave != null) {
                    vm.saveBubbleToProject(id, toSave)
                    Toast.makeText(context, "Saved to new project", Toast.LENGTH_SHORT).show()
                } else {
                    vm.openProject(id)
                }
            },
        )
    }

    saveSheetText?.let { text ->
        SaveTargetSheet(
            projects = projects,
            onDismiss = { saveSheetText = null },
            onPick = { projectId ->
                vm.saveBubbleToProject(projectId, text)
                saveSheetText = null
                Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
            },
            onNewProject = {
                pendingSaveText = text
                saveSheetText = null
                newProjectName = ""
            },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatDrawer(
    conversations: List<ConversationSummary>,
    projects: List<ProjectSummary>,
    currentId: Long,
    onNewChat: () -> Unit,
    onOpen: (Long) -> Unit,
    onRename: (ConversationSummary) -> Unit,
    onDelete: (Long) -> Unit,
    onOpenProject: (Long) -> Unit,
    onNewProject: () -> Unit,
    onRenameProject: (ProjectSummary) -> Unit,
    onDeleteProject: (Long) -> Unit,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    ModalDrawerSheet {
        ConversationsList(
            conversations = conversations,
            projects = projects,
            currentId = currentId,
            onNewChat = onNewChat,
            onOpen = onOpen,
            onRename = onRename,
            onDelete = onDelete,
            onOpenProject = onOpenProject,
            onNewProject = onNewProject,
            onRenameProject = onRenameProject,
            onDeleteProject = onDeleteProject,
            onOpenModels = onOpenModels,
            onOpenSettings = onOpenSettings,
        )
    }
}

/**
 * The conversations + projects list — shared by the Compact/Medium modal drawer
 * ([ChatDrawer]) and the Expanded two-pane left pane. Holds its own long-press
 * action sheet so both presentations get rename/delete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationsList(
    conversations: List<ConversationSummary>,
    projects: List<ProjectSummary>,
    currentId: Long,
    onNewChat: () -> Unit,
    onOpen: (Long) -> Unit,
    onRename: (ConversationSummary) -> Unit,
    onDelete: (Long) -> Unit,
    onOpenProject: (Long) -> Unit,
    onNewProject: () -> Unit,
    onRenameProject: (ProjectSummary) -> Unit,
    onDeleteProject: (Long) -> Unit,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var sheet by remember { mutableStateOf<DrawerSheet?>(null) }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NativeLmMark(size = 24.dp)
            Spacer(Modifier.size(10.dp))
            Text("NativeLM", style = MaterialTheme.typography.titleLarge)
        }
        NavigationDrawerItem(
            label = { Text("New chat") },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            selected = false,
            onClick = onNewChat,
        )

        LazyColumn(Modifier.weight(1f)) {
            items(items = conversations, key = { "c${it.id}" }) { c ->
                DrawerRow(
                    label = c.title,
                    selected = c.id == currentId,
                    onClick = { onOpen(c.id) },
                    onLongClick = { sheet = DrawerSheet.Conv(c) },
                )
            }

            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    "Projects",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                )
                NavigationDrawerItem(
                    label = { Text("New project") },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    selected = false,
                    onClick = onNewProject,
                )
            }
            items(items = projects, key = { "p${it.id}" }) { p ->
                DrawerRow(
                    label = p.name,
                    selected = false,
                    onClick = { onOpenProject(p.id) },
                    onLongClick = { sheet = DrawerSheet.Proj(p) },
                    icon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                )
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        NavigationDrawerItem(label = { Text("Models") }, selected = false, onClick = onOpenModels)
        NavigationDrawerItem(label = { Text("Settings") }, selected = false, onClick = onOpenSettings)
        Spacer(Modifier.height(8.dp))
    }

    sheet?.let { target ->
        val title = when (target) {
            is DrawerSheet.Conv -> target.item.title
            is DrawerSheet.Proj -> target.item.name
        }
        val isProject = target is DrawerSheet.Proj
        ModalBottomSheet(onDismissRequest = { sheet = null }) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
                HorizontalDivider()
                SheetAction(Icons.Filled.Edit, "Rename") {
                    sheet = null
                    when (target) {
                        is DrawerSheet.Conv -> onRename(target.item)
                        is DrawerSheet.Proj -> onRenameProject(target.item)
                    }
                }
                SheetAction(Icons.Filled.Delete, if (isProject) "Delete project" else "Delete") {
                    sheet = null
                    when (target) {
                        is DrawerSheet.Conv -> onDelete(target.item.id)
                        is DrawerSheet.Proj -> onDeleteProject(target.item.id)
                    }
                }
            }
        }
    }
}

private sealed interface DrawerSheet {
    data class Conv(val item: ConversationSummary) : DrawerSheet
    data class Proj(val item: ProjectSummary) : DrawerSheet
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    icon: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(CircleShape)
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            icon()
            Spacer(Modifier.size(12.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaveTargetSheet(
    projects: List<ProjectSummary>,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
    onNewProject: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                "Save to project",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            if (projects.isEmpty()) {
                Text(
                    "No projects yet — create one to save this.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            } else {
                projects.forEach { p ->
                    NavigationDrawerItem(
                        label = { Text(p.name, maxLines = 1) },
                        icon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                        selected = false,
                        onClick = { onPick(p.id) },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
            TextButton(onClick = onNewProject, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Create a new project")
            }
        }
    }
}

@Composable
private fun TextInputDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    placeholder: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun BuildingUnderstanding() {
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.5.dp,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text("Building understanding…", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(4.dp))
            Text("Catching up on this conversation", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Greeting(
    hasModel: Boolean,
    projectName: String?,
    onOpenModels: () -> Unit,
    onSuggestion: (String) -> Unit,
) {
    // Tappable starters on an empty chat — project-aware, sent on tap.
    val suggestions = when {
        !hasModel -> emptyList()
        projectName != null -> listOf(
            "Summarize the key points",
            "What are the main risks?",
            "List the action items",
        )
        else -> listOf(
            "Explain a concept simply",
            "Help me draft an email",
            "Brainstorm ideas with me",
        )
    }
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 480.dp),
        ) {
            NativeLmMark(size = 48.dp)
            Spacer(Modifier.height(20.dp))
            Text(
                text = when {
                    !hasModel -> "No model loaded"
                    projectName != null -> "Ask about “$projectName”"
                    else -> "How can I help you today?"
                },
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            if (projectName != null && hasModel) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Answers are grounded in this project's sources.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (!hasModel) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onOpenModels) { Text("Open Models") }
            }
            if (suggestions.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    suggestions.forEach { s ->
                        SuggestionChip(
                            onClick = { onSuggestion(s) },
                            label = { Text(s) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(msg: ChatMessage, onSave: () -> Unit, onCitationClick: (Citation) -> Unit) {
    val isUser = msg.role == ChatMessage.Role.User
    val bubbleColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface

    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val canSave = !isUser && msg.text.isNotEmpty() && !msg.isStreaming

    Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start, modifier = Modifier.fillMaxWidth()) {
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
                    isUser -> Text(msg.text, style = MaterialTheme.typography.bodyMedium, color = textColor)
                    msg.text.isEmpty() ->
                        if (msg.isStreaming) TypingIndicator()
                        else Text("", style = MaterialTheme.typography.bodyMedium, color = textColor)
                    else -> MarkdownText(markdown = msg.text, color = textColor)
                }
                if (!isUser && msg.citations.isNotEmpty()) {
                    val sources = remember(msg.citations) {
                        msg.citations.distinctBy { it.documentId to it.pageNumber }.take(3)
                    }
                    var sourcesExpanded by remember(msg.id) { mutableStateOf(false) }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { sourcesExpanded = !sourcesExpanded }
                            .padding(vertical = 2.dp),
                    ) {
                        Icon(
                            if (sourcesExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (sourcesExpanded) "Hide sources" else "Show sources",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Sources (${sources.size})",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    if (sourcesExpanded) {
                        Spacer(Modifier.height(6.dp))
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            sources.forEach { citation ->
                                CitationChip(
                                    citation,
                                    onClick = { onCitationClick(citation) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
        if (canSave) {
            IconButton(onClick = onSave, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.BookmarkAdd,
                    contentDescription = "Save to project",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A tappable source row under a grounded answer: "Title · p.N" → opens the PDF. */
@Composable
private fun CitationChip(citation: Citation, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val chipStyle = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono)
    val hasPage = citation.pageNumber > 0
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Icon(
                Icons.Filled.Description,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            // Fixed-column layout so the dot separator sits at the same spot on
            // every chip (titles align in a column down the list). The title
            // takes the left ~80% and ellipsizes before the dot; the page takes
            // the right ~20% — enough for a 4-digit "p.1234" with room to spare,
            // ellipsizing from its end if it ever overflows.
            Text(
                citation.documentTitle,
                style = chipStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(if (hasPage) 0.8f else 1f),
            )
            if (hasPage) {
                Text("·", style = chipStyle, modifier = Modifier.padding(horizontal = 6.dp))
                Text(
                    "p.${citation.pageNumber}",
                    style = chipStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(0.2f),
                )
            }
        }
    }
}

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
            Box(Modifier.size(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = alpha)))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputBar(
    value: String,
    generating: Boolean,
    canSend: Boolean,
    voiceState: VoiceState,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onMicToggle: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = !generating && canSend,
                placeholder = {
                    val hint = when (voiceState) {
                        VoiceState.Recording -> "Listening…"
                        VoiceState.Transcribing -> "Transcribing…"
                        VoiceState.Idle -> "Message NativeLM…"
                    }
                    Text(hint)
                },
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f),
            )
            when {
                generating ->
                    FilledIconButton(onClick = onStop) { Icon(Icons.Filled.Stop, contentDescription = "Stop") }
                value.isNotBlank() ->
                    FilledIconButton(onClick = onSend, enabled = canSend) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                voiceState == VoiceState.Transcribing ->
                    FilledIconButton(onClick = {}, enabled = false) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                voiceState == VoiceState.Recording ->
                    FilledIconButton(onClick = onMicToggle) {
                        Icon(Icons.Filled.Stop, contentDescription = "Stop recording")
                    }
                else ->
                    FilledIconButton(onClick = onMicToggle, enabled = canSend) {
                        Icon(Icons.Filled.Mic, contentDescription = "Dictate")
                    }
            }
        }
    }
}
