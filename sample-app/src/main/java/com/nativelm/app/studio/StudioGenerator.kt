/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.studio

/**
 * The core Studio primitive: **map-reduce over a project's whole source set**.
 *
 * Chat answers from a top-k retrieval slice; a Studio artifact must reflect *all*
 * the sources, which far exceeds the on-device LLM's context window. So we:
 *
 *   each source → pack chunks into context-sized windows → summarize each   (MAP)
 *        → fold the summaries down to a context-budget digest               (REDUCE)
 *        → generate the artifact from the digest                           (FINAL)
 *
 * Engine-agnostic by design: the one-shot LLM call is injected as [llm], so this
 * class holds no Android/engine types and the map-reduce logic stays testable.
 * All [llm] calls are suspend → coroutine cancellation propagates for "Cancel".
 */
class StudioGenerator(
    /** One-shot, stateless generation: prompt + token cap → full decoded text. */
    private val llm: suspend (prompt: String, maxTokens: Int) -> String,
) {

    /** A source and its chunk texts in reading order. */
    data class Source(val title: String, val chunks: List<String>)

    /** Coarse progress for the UI: a phase label and a step counter. */
    data class Progress(val phase: String, val current: Int, val total: Int)

    /** One window of text from a single source, fed to one MAP call. */
    private data class Window(val sourceTitle: String, val text: String)

    /**
     * Build a Briefing (markdown) over [sources]. [onProgress] is invoked on each
     * step so the caller can render progress. Throws [IllegalStateException] if
     * there is no text to work with.
     */
    suspend fun briefing(
        sources: List<Source>,
        scopeLabel: String,
        onProgress: (Progress) -> Unit,
    ): String {
        val digest = digest(sources, onProgress)
        onProgress(Progress("Writing briefing", 1, 1))
        return llm(StudioPrompts.briefing(scopeLabel, digest.take(MAX_DIGEST_CHARS)), BRIEFING_TOKENS)
            .ifBlank { error("The model produced an empty briefing.") }
    }

    /**
     * Build a FAQ (markdown, `### question` headings) over [sources]. Same digest
     * pipeline as [briefing]; only the final prompt differs.
     */
    suspend fun faq(
        sources: List<Source>,
        scopeLabel: String,
        onProgress: (Progress) -> Unit,
    ): String {
        val digest = digest(sources, onProgress)
        onProgress(Progress("Writing FAQ", 1, 1))
        return llm(StudioPrompts.faq(scopeLabel, digest.take(MAX_DIGEST_CHARS)), FAQ_TOKENS)
            .ifBlank { error("The model produced an empty FAQ.") }
    }

    /**
     * Build a Key Topics list (markdown, `### topic` headings + one-line descriptions)
     * over [sources]. Same digest pipeline; only the final prompt differs.
     */
    suspend fun keyTopics(
        sources: List<Source>,
        scopeLabel: String,
        onProgress: (Progress) -> Unit,
    ): String {
        val digest = digest(sources, onProgress)
        onProgress(Progress("Finding topics", 1, 1))
        return llm(StudioPrompts.keyTopics(scopeLabel, digest.take(MAX_DIGEST_CHARS)), TOPICS_TOKENS)
            .ifBlank { error("The model produced no topics.") }
    }

    /**
     * Build a Study Guide (markdown, `## Key Terms` / `## Review Questions` sections)
     * over [sources]. Same digest pipeline; only the final prompt differs.
     */
    suspend fun studyGuide(
        sources: List<Source>,
        scopeLabel: String,
        onProgress: (Progress) -> Unit,
    ): String {
        val digest = digest(sources, onProgress)
        onProgress(Progress("Writing study guide", 1, 1))
        return llm(StudioPrompts.studyGuide(scopeLabel, digest.take(MAX_DIGEST_CHARS)), STUDY_GUIDE_TOKENS)
            .ifBlank { error("The model produced an empty study guide.") }
    }

    /**
     * Build a Timeline (markdown, `### date` headings + descriptions) over [sources].
     * Same digest pipeline; the MAP stage is already told to keep dates. Throws with a
     * friendly message when the sources yield no dated events, so the caller can suggest
     * a different artifact rather than persist an empty timeline.
     */
    suspend fun timeline(
        sources: List<Source>,
        scopeLabel: String,
        onProgress: (Progress) -> Unit,
    ): String {
        val digest = digest(sources, onProgress)
        onProgress(Progress("Building timeline", 1, 1))
        val content = llm(StudioPrompts.timeline(scopeLabel, digest.take(MAX_DIGEST_CHARS)), TIMELINE_TOKENS)
            .ifBlank { error("The model produced an empty timeline.") }
        if (content.contains(StudioPrompts.NO_DATES) || parseTimeline(content).isEmpty()) {
            error("These sources don't have enough dated events for a timeline. Try a Briefing or FAQ instead.")
        }
        return content
    }

    /**
     * Build a Mind Map (nested indented-bullet outline) over [sources]. Same digest
     * pipeline; only the final prompt differs. The viewer parses the outline into a
     * node graph, degrading to plain markdown if the structure doesn't parse.
     */
    suspend fun mindMap(
        sources: List<Source>,
        scopeLabel: String,
        onProgress: (Progress) -> Unit,
    ): String {
        val digest = digest(sources, onProgress)
        onProgress(Progress("Building mind map", 1, 1))
        return llm(StudioPrompts.mindMap(scopeLabel, digest.take(MAX_DIGEST_CHARS)), MIND_MAP_TOKENS)
            .ifBlank { error("The model produced an empty mind map.") }
    }

    /**
     * Build an Audio Overview script (single-narrator spoken prose) over [sources].
     * Same digest pipeline; the final prompt asks for ear-friendly narration. The
     * viewer plays it with on-device TTS and shows the transcript.
     */
    suspend fun audioOverview(
        sources: List<Source>,
        scopeLabel: String,
        onProgress: (Progress) -> Unit,
    ): String {
        val digest = digest(sources, onProgress)
        onProgress(Progress("Writing audio overview", 1, 1))
        return llm(StudioPrompts.audioOverview(scopeLabel, digest.take(MAX_DIGEST_CHARS)), AUDIO_OVERVIEW_TOKENS)
            .ifBlank { error("The model produced an empty audio overview.") }
    }

    /**
     * Build a Podcast script (two-host "Alex:"/"Sam:" dialogue) over [sources] — Studio
     * Step 7b. Same digest pipeline; the viewer parses the turns and plays them with two
     * distinct on-device voices, degrading to plain text if the dialogue doesn't parse.
     */
    suspend fun podcast(
        sources: List<Source>,
        scopeLabel: String,
        onProgress: (Progress) -> Unit,
    ): String {
        val digest = digest(sources, onProgress)
        onProgress(Progress("Writing podcast", 1, 1))
        return llm(StudioPrompts.podcast(scopeLabel, digest.take(MAX_DIGEST_CHARS)), PODCAST_TOKENS)
            .ifBlank { error("The model produced an empty podcast script.") }
    }

    /** MAP + REDUCE: produce a single context-budget digest from all sources. */
    private suspend fun digest(sources: List<Source>, onProgress: (Progress) -> Unit): String {
        val windows = windows(sources)
        check(windows.isNotEmpty()) { "These sources have no extractable text yet." }

        // MAP — summarize each window. A lone short window needs no compression.
        if (windows.size == 1 && windows.first().text.length <= MAP_WINDOW_CHARS) {
            return windows.first().text
        }
        val summaries = ArrayList<String>(windows.size)
        windows.forEachIndexed { i, w ->
            onProgress(Progress("Summarizing sources", i + 1, windows.size))
            llm(StudioPrompts.map(w.sourceTitle, w.text), MAP_TOKENS)
                .trim()
                .takeIf { it.isNotBlank() }
                ?.let { summaries.add(it) }
        }
        check(summaries.isNotEmpty()) { "The model produced no usable summaries." }

        // REDUCE — fold summaries down until they fit the digest budget. Each pass
        // groups summaries into context-sized batches; a single pass usually suffices.
        var current = summaries
        var pass = 0
        while (current.joinToString("\n\n").length > MAX_DIGEST_CHARS && current.size > 1 && pass < MAX_REDUCE_PASSES) {
            pass++
            val groups = groupByBudget(current, REDUCE_GROUP_CHARS)
            if (groups.size == current.size) break // no progress possible — stop folding
            val folded = ArrayList<String>(groups.size)
            groups.forEachIndexed { i, group ->
                onProgress(Progress("Combining summaries", i + 1, groups.size))
                folded.add(llm(StudioPrompts.reduce(group), REDUCE_TOKENS).trim())
            }
            current = folded
        }
        return current.joinToString("\n\n")
    }

    /** Pack each source's chunks into ≤ [MAP_WINDOW_CHARS] windows; never cross a source. */
    private fun windows(sources: List<Source>): List<Window> {
        val out = ArrayList<Window>()
        for (src in sources) {
            val buf = StringBuilder()
            fun flush() {
                if (buf.isNotBlank()) out.add(Window(src.title, buf.toString().trim()))
                buf.setLength(0)
            }
            for (chunk in src.chunks) {
                val text = chunk.trim()
                if (text.isEmpty()) continue
                if (text.length >= MAP_WINDOW_CHARS) {
                    // Oversized single chunk: flush what we have, then hard-split it.
                    flush()
                    text.chunked(MAP_WINDOW_CHARS).forEach { out.add(Window(src.title, it)) }
                    continue
                }
                if (buf.length + text.length + 2 > MAP_WINDOW_CHARS) flush()
                if (buf.isNotEmpty()) buf.append("\n\n")
                buf.append(text)
            }
            flush()
        }
        return out
    }

    /** Greedily group strings so each group's joined length stays ≤ [maxChars]. */
    private fun groupByBudget(items: List<String>, maxChars: Int): List<List<String>> {
        val groups = ArrayList<List<String>>()
        var group = ArrayList<String>()
        var len = 0
        for (item in items) {
            if (group.isNotEmpty() && len + item.length + 2 > maxChars) {
                groups.add(group)
                group = ArrayList()
                len = 0
            }
            group.add(item)
            len += item.length + 2
        }
        if (group.isNotEmpty()) groups.add(group)
        return groups
    }

    companion object {
        private const val MAP_WINDOW_CHARS = 3000
        private const val MAP_TOKENS = 256
        private const val REDUCE_GROUP_CHARS = 3500
        private const val REDUCE_TOKENS = 384
        private const val MAX_DIGEST_CHARS = 4000
        private const val MAX_REDUCE_PASSES = 3
        private const val BRIEFING_TOKENS = 768
        private const val FAQ_TOKENS = 1024
        private const val TOPICS_TOKENS = 768
        private const val STUDY_GUIDE_TOKENS = 1280
        private const val TIMELINE_TOKENS = 1280
        private const val MIND_MAP_TOKENS = 1024
        private const val AUDIO_OVERVIEW_TOKENS = 1024
        private const val PODCAST_TOKENS = 1536
    }
}
