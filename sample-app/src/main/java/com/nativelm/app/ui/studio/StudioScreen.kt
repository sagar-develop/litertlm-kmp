/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.ui.studio

import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nativelm.app.llm.NativeLmViewModel
import com.nativelm.app.llm.StudioArtifactSummary
import com.nativelm.app.llm.StudioArtifactView
import com.nativelm.app.llm.StudioProgress
import com.sagar.aicore.studio.FaqItem
import com.nativelm.app.studio.PodcastPlayState
import com.nativelm.app.studio.PodcastStatus
import com.sagar.aicore.studio.PodcastTurn
import com.nativelm.app.studio.ReadStatus
import com.sagar.aicore.studio.StudioArtifactType
import com.sagar.aicore.studio.TermItem
import com.sagar.aicore.studio.TimelineEvent
import com.sagar.aicore.studio.TopicItem
import com.sagar.aicore.studio.parseFaq
import com.sagar.aicore.studio.parseMindMap
import com.sagar.aicore.studio.parsePodcast
import com.sagar.aicore.studio.parseStudyGuide
import com.sagar.aicore.studio.parseTimeline
import com.sagar.aicore.studio.parseTopics
import com.nativelm.app.ui.chat.MarkdownText
import com.nativelm.app.ui.theme.JetBrainsMono

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen(vm: NativeLmViewModel, onBack: () -> Unit, onAskInChat: () -> Unit) {
    val studio by vm.studio.collectAsState()
    val documents by vm.documents.collectAsState()
    val projectName by vm.currentProjectName.collectAsState()
    val readAloud by vm.readAloud.collectAsState()
    val podcast by vm.podcast.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        vm.refreshDocuments()
        vm.openStudio()
    }

    // The viewer takes over the whole screen when an artifact is open.
    studio.open?.let { artifact ->
        ArtifactViewer(
            artifact = artifact,
            busy = studio.generating,
            readStatus = readAloud?.takeIf { it.artifactId == artifact.id }?.status,
            podcastState = podcast?.takeIf { it.artifactId == artifact.id },
            onBack = vm::closeArtifact,
            onReadAloud = { vm.toggleReadAloud(artifact) },
            onPodcast = { vm.togglePodcast(artifact) },
            onShare = { shareArtifact(context, artifact) },
            onRegenerate = { vm.regenerateArtifact(artifact.id) },
            onDelete = { vm.deleteArtifact(artifact.id) },
            onAskTopic = { question ->
                vm.askInChat(question)
                onAskInChat()
            },
        )
        return
    }

    var pendingType by remember { mutableStateOf<StudioArtifactType?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Studio", style = MaterialTheme.typography.titleMedium)
                        Text(
                            projectName ?: "",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        val canGenerate = documents.isNotEmpty() && !studio.generating
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "Generate overviews from this project's sources. Everything runs on your device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }

            if (documents.isEmpty()) {
                item {
                    Text(
                        "Import sources first to generate an overview.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            studio.progress?.let { p ->
                item { ProgressCard(p, onCancel = vm::cancelStudio) }
            }
            studio.error?.let { msg ->
                item { ErrorCard(msg, onDismiss = vm::clearStudioError) }
            }

            // Audio — the headline outputs lead.
            item { SectionLabel("Audio") }
            item {
                AudioHeroCard(
                    type = StudioArtifactType.AUDIO_OVERVIEW,
                    blurb = "A spoken summary, single narrator",
                    enabled = canGenerate,
                    onClick = { pendingType = StudioArtifactType.AUDIO_OVERVIEW },
                )
            }
            item {
                AudioHeroCard(
                    type = StudioArtifactType.PODCAST,
                    blurb = "Two hosts discuss your sources",
                    enabled = canGenerate,
                    onClick = { pendingType = StudioArtifactType.PODCAST },
                )
            }

            // Create — the text artifact types as a 2-column grid.
            item { SectionLabel("Create") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TEXT_TYPES.chunked(2).forEach { rowTypes ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowTypes.forEach { type ->
                                CreateCard(
                                    type = type,
                                    enabled = canGenerate,
                                    modifier = Modifier.weight(1f),
                                    onClick = { pendingType = type },
                                )
                            }
                            if (rowTypes.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            // Outputs — generated artifacts.
            if (studio.artifacts.isNotEmpty()) {
                item { SectionLabel("Outputs") }
                items(items = studio.artifacts, key = { it.id }) { artifact ->
                    ArtifactRow(
                        artifact = artifact,
                        onOpen = { vm.openArtifact(artifact.id) },
                        onDelete = { vm.deleteArtifact(artifact.id) },
                    )
                }
            }
        }
    }

    pendingType?.let { type ->
        SourceSheet(
            type = type,
            sources = documents,
            onDismiss = { pendingType = null },
            onGenerate = { sourceId ->
                pendingType = null
                vm.generate(type, sourceId)
            },
        )
    }
}

private val TEXT_TYPES = listOf(
    StudioArtifactType.BRIEFING,
    StudioArtifactType.FAQ,
    StudioArtifactType.KEY_TOPICS,
    StudioArtifactType.STUDY_GUIDE,
    StudioArtifactType.TIMELINE,
    StudioArtifactType.MIND_MAP,
)

/** The outline icon for each artifact type — used in the grid, hero cards, and output rows. */
private fun StudioArtifactType.icon(): ImageVector = when (this) {
    StudioArtifactType.BRIEFING -> Icons.AutoMirrored.Outlined.Article
    StudioArtifactType.FAQ -> Icons.Outlined.QuestionAnswer
    StudioArtifactType.KEY_TOPICS -> Icons.AutoMirrored.Outlined.Label
    StudioArtifactType.STUDY_GUIDE -> Icons.Outlined.School
    StudioArtifactType.TIMELINE -> Icons.Outlined.Timeline
    StudioArtifactType.MIND_MAP -> Icons.Outlined.AccountTree
    StudioArtifactType.AUDIO_OVERVIEW -> Icons.Outlined.GraphicEq
    StudioArtifactType.PODCAST -> Icons.Outlined.Mic
}

/** A small uppercase mono section label (Audio / Create / Outputs). */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(
            fontFamily = JetBrainsMono,
            letterSpacing = 1.5.sp,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

/** A full-width hero card for an audio output (Audio Overview / Podcast). */
@Composable
private fun AudioHeroCard(
    type: StudioArtifactType,
    blurb: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    type.icon(),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    type.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    blurb,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(
                Modifier
                    .size(40.dp)
                    .background(
                        if (enabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = "Generate ${type.label}",
                    tint = if (enabled) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** A compact grid card for a text artifact type (Briefing, FAQ, …). */
@Composable
private fun CreateCard(
    type: StudioArtifactType,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        Column(Modifier.padding(14.dp)) {
            Icon(
                type.icon(),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.height(24.dp))
            Text(
                type.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Bottom sheet to pick the source for a generation: Whole project (default) or one source. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceSheet(
    type: StudioArtifactType,
    sources: List<com.nativelm.app.llm.DocumentSummary>,
    onDismiss: () -> Unit,
    onGenerate: (Long) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selected by remember { mutableStateOf(0L) } // 0 = whole project

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Text(
                "Generate ${type.label}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Choose a source",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
            )

            SourceRow(
                icon = Icons.Outlined.FolderOpen,
                label = "Whole project",
                caption = "${sources.size} source" + if (sources.size == 1) "" else "s",
                selected = selected == 0L,
                onClick = { selected = 0L },
            )
            sources.forEach { doc ->
                SourceRow(
                    icon = Icons.Outlined.Description,
                    label = doc.title,
                    caption = "${doc.pageCount} page" + if (doc.pageCount == 1) "" else "s",
                    selected = selected == doc.id,
                    onClick = { selected = doc.id },
                )
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { onGenerate(selected) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Generate")
            }
            Text(
                "Runs on your device",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        }
    }
}

/** One selectable source row in the [SourceSheet]. */
@Composable
private fun SourceRow(
    icon: ImageVector,
    label: String,
    caption: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(22.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    caption,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun ProgressCard(progress: StudioProgress, onCancel: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        progress.phase,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (progress.total > 0) {
                        Text(
                            "${progress.current} / ${progress.total}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            Spacer(Modifier.height(8.dp))
            if (progress.total > 0) {
                LinearProgressIndicator(
                    progress = { progress.current.toFloat() / progress.total.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun ArtifactRow(
    artifact: StudioArtifactSummary,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                artifact.type.icon(),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    artifact.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                val when_ = DateUtils.getRelativeTimeSpanString(artifact.createdAt)
                Text(
                    "${artifact.type.label} · ${artifact.scopeLabel} · $when_",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "More options")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArtifactViewer(
    artifact: StudioArtifactView,
    busy: Boolean,
    readStatus: ReadStatus?,
    podcastState: PodcastPlayState?,
    onBack: () -> Unit,
    onReadAloud: () -> Unit,
    onPodcast: () -> Unit,
    onShare: () -> Unit,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    onAskTopic: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(artifact.title, maxLines = 1, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Read-aloud lives only inside the audio artifacts' own player
                    // cards (Audio Overview / Podcast), not as a global top-bar action.
                    // Share is the common action; regenerate/delete tuck into an overflow.
                    IconButton(onClick = onShare) {
                        Icon(Icons.Filled.Share, contentDescription = "Share")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Regenerate") },
                                leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                                enabled = !busy,
                                onClick = {
                                    menuOpen = false
                                    onRegenerate()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onDelete()
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        // A mind map drives its own pan/zoom, so it gets the full content area
        // (no outer scroll, no fixed height). Everything else scrolls normally.
        val mindRoot = if (artifact.type == StudioArtifactType.MIND_MAP) {
            parseMindMap(artifact.content)
        } else {
            null
        }
        if (mindRoot != null) {
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                ScopeChip(artifact)
                Text(
                    "Pinch to zoom, drag to pan. Tap a node to ask about it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                MindMapView(
                    mindRoot,
                    onAsk = { onAskTopic("Tell me more about $it.") },
                    modifier = Modifier.weight(1f),
                )
            }
            return@Scaffold
        }
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            ScopeChip(artifact)
            // Structured artifacts get custom rendering; a degraded parse always
            // falls back to plain markdown so the generated text is never lost.
            when (artifact.type) {
                StudioArtifactType.FAQ -> {
                    val faq = parseFaq(artifact.content)
                    if (faq.isNotEmpty()) faq.forEach { FaqRow(it) } else MarkdownText(artifact.content)
                }
                StudioArtifactType.KEY_TOPICS -> {
                    val topics = parseTopics(artifact.content)
                    if (topics.isNotEmpty()) {
                        Text(
                            "Tap a topic to ask about it in this project's chat.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        topics.forEach { topic ->
                            TopicRow(topic, onAsk = { onAskTopic("Tell me more about ${topic.title}.") })
                        }
                    } else {
                        MarkdownText(artifact.content)
                    }
                }
                StudioArtifactType.STUDY_GUIDE -> {
                    val guide = parseStudyGuide(artifact.content)
                    if (guide != null) {
                        if (guide.terms.isNotEmpty()) {
                            SectionHeader("Key terms")
                            guide.terms.forEach { TermDefRow(it) }
                        }
                        if (guide.questions.isNotEmpty()) {
                            SectionHeader("Review questions")
                            guide.questions.forEach { FaqRow(it) }
                        }
                    } else {
                        MarkdownText(artifact.content)
                    }
                }
                StudioArtifactType.TIMELINE -> {
                    val events = parseTimeline(artifact.content)
                    if (events.isNotEmpty()) TimelineView(events) else MarkdownText(artifact.content)
                }
                // A parsable mind map is rendered full-screen above (early return);
                // reaching here means the parse failed, so degrade to plain markdown.
                StudioArtifactType.MIND_MAP -> MarkdownText(artifact.content)
                StudioArtifactType.AUDIO_OVERVIEW -> {
                    AudioOverviewPlayer(status = readStatus, onToggle = onReadAloud)
                    Spacer(Modifier.height(20.dp))
                    SectionHeader("Transcript")
                    MarkdownText(artifact.content)
                }
                StudioArtifactType.PODCAST -> {
                    val turns = parsePodcast(artifact.content)
                    if (turns.isNotEmpty()) {
                        PodcastPlayer(status = podcastState?.status, onToggle = onPodcast)
                        Spacer(Modifier.height(20.dp))
                        SectionHeader("Transcript")
                        val activeTurn = podcastState
                            ?.takeIf { it.status == PodcastStatus.SPEAKING }
                            ?.turnIndex
                        turns.forEachIndexed { i, turn ->
                            PodcastTurnRow(turn, isCurrent = i == activeTurn)
                        }
                    } else {
                        MarkdownText(artifact.content)
                    }
                }
                else -> MarkdownText(markdown = artifact.content)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * The Audio Overview player: a prominent play/pause card reflecting the live
 * read-aloud [status]. The transcript is rendered below it by the caller.
 */
@Composable
private fun AudioOverviewPlayer(status: ReadStatus?, onToggle: () -> Unit) {
    PlayerCard(
        icon = StudioArtifactType.AUDIO_OVERVIEW.icon(),
        title = "Audio Overview",
        subtitle = when (status) {
            ReadStatus.SPEAKING -> "Playing…"
            ReadStatus.PAUSED -> "Paused — tap to resume"
            null -> "Tap to play"
        },
        playing = status == ReadStatus.SPEAKING,
        onToggle = onToggle,
    )
}

/** The Podcast player: same play/pause card, driven by two-voice playback [status]. */
@Composable
private fun PodcastPlayer(status: PodcastStatus?, onToggle: () -> Unit) {
    PlayerCard(
        icon = StudioArtifactType.PODCAST.icon(),
        title = "Podcast",
        subtitle = when (status) {
            PodcastStatus.SPEAKING -> "Playing…"
            PodcastStatus.PAUSED -> "Paused — tap to resume"
            null -> "Two voices · tap to play"
        },
        playing = status == PodcastStatus.SPEAKING,
        onToggle = onToggle,
    )
}

/**
 * Shared play/pause card for the audio artifacts (Audio Overview, Podcast). Mirrors the
 * home hero card — a sage-tinted type-icon square and the title — but the trailing circle
 * is the live play/pause control instead of a generate affordance.
 */
@Composable
private fun PlayerCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    playing: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(
                Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

/** One podcast turn in the transcript: a speaker-coloured name + the line, highlighted
 *  while it is the turn currently being spoken. */
@Composable
private fun PodcastTurnRow(turn: PodcastTurn, isCurrent: Boolean) {
    val accent = if (turn.speaker == 0) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.tertiary
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(
                if (isCurrent) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = if (isCurrent) 10.dp else 0.dp, vertical = if (isCurrent) 8.dp else 0.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                Modifier
                    .size(7.dp)
                    .background(accent, CircleShape),
            )
            Text(
                turn.name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = accent,
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            turn.text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun FaqRow(item: FaqItem) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                item.question,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded && item.answer.isNotBlank()) {
            MarkdownText(
                markdown = item.answer,
                modifier = Modifier.padding(bottom = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun TopicRow(item: TopicItem, onAsk: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        onClick = onAsk,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (item.description.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        item.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "Ask about this",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(
            fontFamily = JetBrainsMono,
            letterSpacing = 1.5.sp,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
    )
}

/** A small context chip at the top of the viewer: the artifact's type icon + "Type · scope". */
@Composable
private fun ScopeChip(artifact: StudioArtifactView) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.padding(bottom = 14.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                artifact.type.icon(),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
            Text(
                "${artifact.type.label} · ${artifact.scopeLabel}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TermDefRow(item: TermItem) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Text(
            item.term,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (item.definition.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                item.definition,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun TimelineView(events: List<TimelineEvent>) {
    Column(Modifier.fillMaxWidth()) {
        events.forEachIndexed { index, event ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
            ) {
                TimelineRail(isFirst = index == 0, isLast = index == events.lastIndex)
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 12.dp, bottom = 20.dp),
                ) {
                    Text(
                        event.date,
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = JetBrainsMono,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (event.description.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            event.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** The dotted rail down the left of the timeline: a node per event joined by a line. */
@Composable
private fun TimelineRail(isFirst: Boolean, isLast: Boolean) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val dotColor = MaterialTheme.colorScheme.primary
    Canvas(
        Modifier
            .width(24.dp)
            .fillMaxHeight(),
    ) {
        val cx = size.width / 2f
        val dotY = 9.dp.toPx()
        val radius = 5.dp.toPx()
        val stroke = 2.dp.toPx()
        if (!isFirst) drawLine(lineColor, Offset(cx, 0f), Offset(cx, dotY), strokeWidth = stroke)
        if (!isLast) drawLine(lineColor, Offset(cx, dotY), Offset(cx, size.height), strokeWidth = stroke)
        drawCircle(dotColor, radius = radius, center = Offset(cx, dotY))
    }
}

private fun shareArtifact(context: Context, artifact: StudioArtifactView) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, artifact.title)
        putExtra(Intent.EXTRA_TEXT, artifact.content)
    }
    context.startActivity(Intent.createChooser(intent, "Share ${artifact.title}"))
}
