/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.ui.studio

import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nativelm.app.llm.NativeLmViewModel
import com.nativelm.app.llm.StudioArtifactSummary
import com.nativelm.app.llm.StudioArtifactView
import com.nativelm.app.llm.StudioProgress
import com.nativelm.app.studio.FaqItem
import com.nativelm.app.studio.PodcastPlayState
import com.nativelm.app.studio.PodcastStatus
import com.nativelm.app.studio.PodcastTurn
import com.nativelm.app.studio.ReadStatus
import com.nativelm.app.studio.StudioArtifactType
import com.nativelm.app.studio.TermItem
import com.nativelm.app.studio.TimelineEvent
import com.nativelm.app.studio.TopicItem
import com.nativelm.app.studio.parseFaq
import com.nativelm.app.studio.parseMindMap
import com.nativelm.app.studio.parsePodcast
import com.nativelm.app.studio.parseStudyGuide
import com.nativelm.app.studio.parseTimeline
import com.nativelm.app.studio.parseTopics
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

    var showType by remember { mutableStateOf(false) }
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
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            Text(
                "Generate overviews from this project's sources. Everything runs on your device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            Button(
                onClick = { showType = true },
                enabled = !studio.generating && documents.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Generate")
            }
            if (documents.isEmpty()) {
                Text(
                    "Import sources first to generate an overview.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            studio.progress?.let { p ->
                Spacer(Modifier.height(12.dp))
                ProgressCard(p, onCancel = vm::cancelStudio)
            }

            studio.error?.let { msg ->
                Spacer(Modifier.height(12.dp))
                ErrorCard(msg, onDismiss = vm::clearStudioError)
            }

            Spacer(Modifier.height(16.dp))

            if (studio.artifacts.isEmpty() && studio.progress == null) {
                EmptyState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
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
    }

    if (showType) {
        TypeDialog(
            onDismiss = { showType = false },
            onPick = { type ->
                showType = false
                pendingType = type
            },
        )
    }

    pendingType?.let { type ->
        ScopeDialog(
            title = "${type.label} from…",
            sources = documents.map { it.id to it.title },
            onDismiss = { pendingType = null },
            onPick = { sourceId ->
                pendingType = null
                vm.generate(type, sourceId)
            },
        )
    }
}

@Composable
private fun TypeDialog(
    onDismiss: () -> Unit,
    onPick: (StudioArtifactType) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Generate…") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                StudioArtifactType.entries.forEach { type ->
                    ScopeOption(type.label) { onPick(type) }
                }
            }
        },
    )
}

@Composable
private fun ScopeDialog(
    title: String,
    sources: List<Pair<Long, String>>,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ScopeOption("Whole project") { onPick(0L) }
                sources.forEach { (id, title) ->
                    ScopeOption(title) { onPick(id) }
                }
            }
        },
    )
}

@Composable
private fun ScopeOption(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurface,
        )
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
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete ${artifact.title}")
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
                    IconButton(onClick = onShare) {
                        Icon(Icons.Filled.Share, contentDescription = "Share")
                    }
                    IconButton(onClick = onRegenerate, enabled = !busy) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Regenerate")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
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

/** Shared play/pause card used by the audio artifacts (Audio Overview, Podcast). */
@Composable
private fun PlayerCard(title: String, subtitle: String, playing: Boolean, onToggle: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                onClick = onToggle,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        Text(
            turn.name,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = accent,
        )
        Spacer(Modifier.height(2.dp))
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
    Column(Modifier.fillMaxWidth()) {
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
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
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

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "No artifacts yet.\nGenerate a briefing to get an overview of this project.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
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
