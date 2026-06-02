/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.studio

/**
 * Prompt templates for Studio's map-reduce. Kept pure (no engine, no Android) so
 * they're easy to read and tweak. Each artifact type adds a final-stage prompt;
 * the map (summarize a window) and reduce (combine summaries) prompts are shared.
 */
internal object StudioPrompts {

    /**
     * Shared number/unit formatting rule. Small models otherwise swing between two
     * bad extremes — LaTeX (`$9.5 \text{g/dL}$`) or spelled-out words ("times 10 to
     * the power of 6") — so we ask for compact Unicode and back it with
     * [sanitizeStudioMarkdown].
     */
    private const val NUMBER_STYLE: String =
        "Format numbers and units compactly with Unicode symbols: use × for multiplication, " +
            "superscript digits (⁰¹²³⁴⁵⁶⁷⁸⁹) for powers, % for percent and µ for micro — " +
            "e.g. \"3.72 ×10⁶/µL\", \"9.5 g/dL\", \"68.3%\". Do NOT spell these out as words " +
            "(no \"times\", \"to the power of\", \"percent\") and do NOT use LaTeX or math notation " +
            "(no \$, \\text, \\times, ^)."

    /** MAP: compress one window of a single source into a dense factual summary. */
    fun map(sourceTitle: String, windowText: String): String = buildString {
        append("You are summarizing part of a document titled \"")
        append(sourceTitle)
        append("\" so it can later be combined with other parts.\n")
        append("Write a dense, factual summary of the excerpt below: keep names, numbers, ")
        append("dates, definitions, and conclusions; drop filler. Use plain prose, no preamble. ")
        append(NUMBER_STYLE).append("\n\n")
        append("--- EXCERPT START ---\n")
        append(windowText.trim())
        append("\n--- EXCERPT END ---\n\n")
        append("Summary:")
    }

    /** REDUCE: fold several partial summaries into one shorter combined summary. */
    fun reduce(summaries: List<String>): String = buildString {
        append("Combine the partial summaries below into one coherent, non-repetitive summary. ")
        append("Preserve key facts, names, numbers and conclusions; merge overlaps. Plain prose, no preamble.\n\n")
        summaries.forEachIndexed { i, s ->
            append("[Part ").append(i + 1).append("]\n").append(s.trim()).append("\n\n")
        }
        append("Combined summary:")
    }

    /** FINAL (Briefing): turn the reduced digest into an executive briefing in markdown. */
    fun briefing(scopeLabel: String, digest: String): String = buildString {
        append("You are writing an executive briefing based ONLY on the digest of source material below ")
        append("(scope: ").append(scopeLabel).append(").\n")
        append("Write clear, well-structured **Markdown**:\n")
        append("- A short title (\"# ...\").\n")
        append("- A 2-3 sentence overview.\n")
        append("- \"## Key points\" as a bulleted list of the most important takeaways.\n")
        append("- \"## Details\" with a few short paragraphs expanding the key points.\n")
        append("- When the digest has several numeric results (measurements, metrics, line items), ")
        append("add a \"## Figures\" section presenting them as a GitHub-flavored Markdown table, e.g.:\n")
        append("  | Parameter | Value | Reference range |\n  | --- | --- | --- |\n  | Hemoglobin | 9.5 g/dL | 12.0 – 15.0 g/dL |\n")
        append(NUMBER_STYLE).append("\n")
        append("Use only information supported by the digest; do not invent facts. No preamble before the title.\n\n")
        append("--- DIGEST START ---\n")
        append(digest.trim())
        append("\n--- DIGEST END ---\n")
    }

    /** FINAL (FAQ): turn the digest into grounded question/answer pairs in markdown. */
    fun faq(scopeLabel: String, digest: String): String = buildString {
        append("You are writing a FAQ based ONLY on the digest of source material below ")
        append("(scope: ").append(scopeLabel).append(").\n")
        append("Produce 6 to 10 of the questions a reader is most likely to ask, each with a ")
        append("concise, grounded answer. Format as **Markdown**:\n")
        append("- Start each question with a \"### \" heading containing the question itself.\n")
        append("- Put the answer in plain prose on the lines after the heading.\n")
        append("Order from most to least important. ").append(NUMBER_STYLE).append("\n")
        append("Use only information supported by the digest; do not invent facts. No preamble.\n\n")
        append("--- DIGEST START ---\n")
        append(digest.trim())
        append("\n--- DIGEST END ---\n")
    }

    /** FINAL (Key Topics): cluster the digest into themes, each with a one-line description. */
    fun keyTopics(scopeLabel: String, digest: String): String = buildString {
        append("You are identifying the key topics covered by the source material in the ")
        append("digest below (scope: ").append(scopeLabel).append(").\n")
        append("List 5 to 8 distinct topics, most important first. Format as **Markdown**:\n")
        append("- Start each topic with a \"### \" heading containing a short topic name (2 to 5 words).\n")
        append("- On the next line, give a single-sentence description of what the sources say about it.\n")
        append(NUMBER_STYLE).append("\n")
        append("Use only information supported by the digest; do not invent topics. No preamble.\n\n")
        append("--- DIGEST START ---\n")
        append(digest.trim())
        append("\n--- DIGEST END ---\n")
    }

    /** FINAL (Study Guide): a sectioned guide — key terms + definitions, review questions. */
    fun studyGuide(scopeLabel: String, digest: String): String = buildString {
        append("You are writing a study guide based ONLY on the digest of source material below ")
        append("(scope: ").append(scopeLabel).append(").\n")
        append("Use exactly these two Markdown sections, in this order:\n")
        append("\"## Key Terms\" — then for each important term a \"### \" heading with the term, ")
        append("followed by a one-sentence definition on the next line (8 to 12 terms).\n")
        append("\"## Review Questions\" — then for each question a \"### \" heading with the question, ")
        append("followed by a short model answer on the next line (5 to 8 questions).\n")
        append(NUMBER_STYLE).append("\n")
        append("Use only information supported by the digest; do not invent facts. No preamble.\n\n")
        append("--- DIGEST START ---\n")
        append(digest.trim())
        append("\n--- DIGEST END ---\n")
    }

    /** Sentinel the model is asked to emit when the sources contain no dated events. */
    const val NO_DATES: String = "NO_DATES"

    /** FINAL (Timeline): extract dated events from the digest into chronological order. */
    fun timeline(scopeLabel: String, digest: String): String = buildString {
        append("You are extracting a chronological timeline of events from the digest of source ")
        append("material below (scope: ").append(scopeLabel).append(").\n")
        append("Find the events that have a date or time and list them earliest first. Format as **Markdown**:\n")
        append("- An optional short title (\"# ...\").\n")
        append("- For each event, a \"### \" heading containing only its date or time period, exactly as the ")
        append("sources give it (e.g. \"1947\", \"March 2024\", \"Q3 2023\", \"Day 1\").\n")
        append("- On the next line, a one to two sentence description of what happened.\n")
        append("Order strictly from earliest to latest. Use only events supported by the digest; ")
        append("do not invent dates. ").append(NUMBER_STYLE).append("\n")
        append("If the sources contain no dated events at all, reply with exactly: ").append(NO_DATES).append("\n")
        append("No preamble before the first heading.\n\n")
        append("--- DIGEST START ---\n")
        append(digest.trim())
        append("\n--- DIGEST END ---\n")
    }
}
