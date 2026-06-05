/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore.studio

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
    /** Prompt templates (override to retune tone/wording/language). */
    private val prompts: StudioPrompts = DefaultStudioPrompts,
    /** Map-reduce sizing + per-artifact token budgets. */
    private val config: StudioConfig = StudioConfig(),
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
        return llm(prompts.briefing(scopeLabel, digest.take(config.maxDigestChars)), config.briefingTokens)
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
        return llm(prompts.faq(scopeLabel, digest.take(config.maxDigestChars)), config.faqTokens)
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
        return llm(prompts.keyTopics(scopeLabel, digest.take(config.maxDigestChars)), config.topicsTokens)
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
        return llm(prompts.studyGuide(scopeLabel, digest.take(config.maxDigestChars)), config.studyGuideTokens)
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
        val content = llm(prompts.timeline(scopeLabel, digest.take(config.maxDigestChars)), config.timelineTokens)
            .ifBlank { error("The model produced an empty timeline.") }
        if (content.contains(prompts.noDates) || parseTimeline(content).isEmpty()) {
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
        return llm(prompts.mindMap(scopeLabel, digest.take(config.maxDigestChars)), config.mindMapTokens)
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
        return llm(prompts.audioOverview(scopeLabel, digest.take(config.maxDigestChars)), config.audioOverviewTokens)
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
        return llm(prompts.podcast(scopeLabel, digest.take(config.maxDigestChars)), config.podcastTokens)
            .ifBlank { error("The model produced an empty podcast script.") }
    }

    /** MAP + REDUCE: produce a single context-budget digest from all sources. */
    private suspend fun digest(sources: List<Source>, onProgress: (Progress) -> Unit): String {
        val windows = windows(sources)
        check(windows.isNotEmpty()) { "These sources have no extractable text yet." }

        // MAP — summarize each window. A lone short window needs no compression.
        if (windows.size == 1 && windows.first().text.length <= config.mapWindowChars) {
            return windows.first().text
        }
        val summaries = ArrayList<String>(windows.size)
        windows.forEachIndexed { i, w ->
            onProgress(Progress("Summarizing sources", i + 1, windows.size))
            llm(prompts.map(w.sourceTitle, w.text), config.mapTokens)
                .trim()
                .takeIf { it.isNotBlank() }
                ?.let { summaries.add(it) }
        }
        check(summaries.isNotEmpty()) { "The model produced no usable summaries." }

        // REDUCE — fold summaries down until they fit the digest budget. Each pass
        // groups summaries into context-sized batches; a single pass usually suffices.
        var current = summaries
        var pass = 0
        while (current.joinToString("\n\n").length > config.maxDigestChars && current.size > 1 && pass < config.maxReducePasses) {
            pass++
            val groups = groupByBudget(current, config.reduceGroupChars)
            if (groups.size == current.size) break // no progress possible — stop folding
            val folded = ArrayList<String>(groups.size)
            groups.forEachIndexed { i, group ->
                onProgress(Progress("Combining summaries", i + 1, groups.size))
                folded.add(llm(prompts.reduce(group), config.reduceTokens).trim())
            }
            current = folded
        }
        return current.joinToString("\n\n")
    }

    /** Pack each source's chunks into ≤ [config.mapWindowChars] windows; never cross a source. */
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
                if (text.length >= config.mapWindowChars) {
                    // Oversized single chunk: flush what we have, then hard-split it.
                    flush()
                    text.chunked(config.mapWindowChars).forEach { out.add(Window(src.title, it)) }
                    continue
                }
                if (buf.length + text.length + 2 > config.mapWindowChars) flush()
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

}
