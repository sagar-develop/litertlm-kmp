/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore.studio

/** Sentinel the model is asked to emit when the sources contain no dated events. */
const val STUDIO_NO_DATES: String = "NO_DATES"

/**
 * Prompt set for Studio's map-reduce. An interface so a consumer can override the
 * wording (tone, language, artifact instructions) without forking the engine;
 * [DefaultStudioPrompts] is the shipped implementation. [StudioGenerator] calls
 * these and never embeds prompt text itself.
 */
interface StudioPrompts {
    /** MAP: compress one window of a single source into a dense factual summary. */
    fun map(sourceTitle: String, windowText: String): String

    /** REDUCE: fold several partial summaries into one shorter combined summary. */
    fun reduce(summaries: List<String>): String

    fun briefing(scopeLabel: String, digest: String): String
    fun faq(scopeLabel: String, digest: String): String
    fun keyTopics(scopeLabel: String, digest: String): String
    fun studyGuide(scopeLabel: String, digest: String): String
    fun timeline(scopeLabel: String, digest: String): String
    fun mindMap(scopeLabel: String, digest: String): String
    fun audioOverview(scopeLabel: String, digest: String): String
    fun podcast(scopeLabel: String, digest: String): String

    /** The sentinel a timeline prompt asks for when no dated events exist. */
    val noDates: String get() = STUDIO_NO_DATES
}

/**
 * Default prompt templates. Kept pure (no engine, no Android) so they're easy to
 * read and tweak. Each artifact type adds a final-stage prompt; the map (summarize
 * a window) and reduce (combine summaries) prompts are shared.
 */
object DefaultStudioPrompts : StudioPrompts {

    /**
     * Shared number/unit formatting rule. Small models otherwise swing between two
     * bad extremes — LaTeX (`$9.5 \text{g/dL}$`) or spelled-out words ("times 10 to
     * the power of 6") — so we ask for compact Unicode and back it with
     * `sanitizeStudioMarkdown`.
     */
    private const val NUMBER_STYLE: String =
        "Format numbers and units compactly with Unicode symbols: use × for multiplication, " +
            "superscript digits (⁰¹²³⁴⁵⁶⁷⁸⁹) for powers, % for percent and µ for micro — " +
            "e.g. \"3.72 ×10⁶/µL\", \"9.5 g/dL\", \"68.3%\". Do NOT spell these out as words " +
            "(no \"times\", \"to the power of\", \"percent\") and do NOT use LaTeX or math notation " +
            "(no \$, \\text, \\times, ^)."

    override fun map(sourceTitle: String, windowText: String): String = buildString {
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

    override fun reduce(summaries: List<String>): String = buildString {
        append("Combine the partial summaries below into one coherent, non-repetitive summary. ")
        append("Preserve key facts, names, numbers and conclusions; merge overlaps. Plain prose, no preamble.\n\n")
        summaries.forEachIndexed { i, s ->
            append("[Part ").append(i + 1).append("]\n").append(s.trim()).append("\n\n")
        }
        append("Combined summary:")
    }

    override fun briefing(scopeLabel: String, digest: String): String = buildString {
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

    override fun faq(scopeLabel: String, digest: String): String = buildString {
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

    override fun keyTopics(scopeLabel: String, digest: String): String = buildString {
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

    override fun studyGuide(scopeLabel: String, digest: String): String = buildString {
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

    override fun timeline(scopeLabel: String, digest: String): String = buildString {
        append("You are extracting a chronological timeline of events from the digest of source ")
        append("material below (scope: ").append(scopeLabel).append(").\n")
        append("Find the events that have a date or time and list them earliest first. Format as **Markdown**:\n")
        append("- An optional short title (\"# ...\").\n")
        append("- For each event, a \"### \" heading containing only its date or time period, exactly as the ")
        append("sources give it (e.g. \"1947\", \"March 2024\", \"Q3 2023\", \"Day 1\").\n")
        append("- On the next line, a one to two sentence description of what happened.\n")
        append("Order strictly from earliest to latest. Use only events supported by the digest; ")
        append("do not invent dates. ").append(NUMBER_STYLE).append("\n")
        append("If the sources contain no dated events at all, reply with exactly: ").append(noDates).append("\n")
        append("No preamble before the first heading.\n\n")
        append("--- DIGEST START ---\n")
        append(digest.trim())
        append("\n--- DIGEST END ---\n")
    }

    override fun mindMap(scopeLabel: String, digest: String): String = buildString {
        append("You are building a mind map of the source material in the digest below ")
        append("(scope: ").append(scopeLabel).append(").\n")
        append("Output ONLY a nested outline as an indented Markdown bullet list — nothing else:\n")
        append("- One single top-level bullet: the central theme (2 to 5 words).\n")
        append("- Under it, 3 to 6 main branches, each indented by 2 spaces.\n")
        append("- Under each branch, 2 to 4 sub-points, indented by 4 spaces.\n")
        append("Indent strictly with spaces (2 per level) and start every line with \"- \". ")
        append("Keep each label short (a few words), not full sentences. Do not go deeper than ")
        append("3 levels. ").append(NUMBER_STYLE).append("\n")
        append("Use only concepts supported by the digest; do not invent. No preamble, no prose, ")
        append("just the bullet outline. Example:\n")
        append("- Central theme\n  - First branch\n    - Detail\n    - Detail\n  - Second branch\n    - Detail\n\n")
        append("--- DIGEST START ---\n")
        append(digest.trim())
        append("\n--- DIGEST END ---\n")
    }

    override fun audioOverview(scopeLabel: String, digest: String): String = buildString {
        append("You are writing the script for a short spoken audio overview, based ONLY on the ")
        append("digest of source material below (scope: ").append(scopeLabel).append(").\n")
        append("A single narrator will READ THIS ALOUD, so write for the ear, not the eye:\n")
        append("- Plain, flowing prose in a few short paragraphs. Use NO Markdown at all — no ")
        append("headings, bullet points, tables, bold, or links — because the voice would ")
        append("pronounce those symbols.\n")
        append("- Open with one warm sentence saying what this material is about. Then walk the ")
        append("listener through the most important points in a natural order, joined by spoken ")
        append("transitions (\"First,\", \"What's interesting here is\", \"This matters because\"). ")
        append("End with a one or two sentence takeaway.\n")
        append("- Conversational but factual; speak to the listener as \"you\". Aim for about 350 ")
        append("to 550 words — roughly two to three minutes when spoken.\n")
        append("- Write numbers and units the way you would SAY them (\"about three and a half ")
        append("million per microliter\", \"sixty-eight percent\"), never as symbols or math.\n")
        append("Use only information supported by the digest; do not invent facts. Output only the ")
        append("narration itself — no title, no label, no preamble.\n\n")
        append("--- DIGEST START ---\n")
        append(digest.trim())
        append("\n--- DIGEST END ---\n")
    }

    override fun podcast(scopeLabel: String, digest: String): String = buildString {
        append("You are writing the script for a short two-host audio podcast that explains the ")
        append("source material in the digest below (scope: ").append(scopeLabel).append(").\n")
        append("The two hosts are Alex and Sam. Write a natural, friendly conversation in which ")
        append("they explain the material to a listener — Alex tends to guide and ask questions, ")
        append("Sam tends to explain and add detail, but keep it balanced and let them build on ")
        append("each other.\n")
        append("Use this STRICT format — one turn per line, and nothing else:\n")
        append("Alex: <what Alex says>\n")
        append("Sam: <what Sam says>\n")
        append("Rules: every line must start with either \"Alex:\" or \"Sam:\", and the two ")
        append("alternate. Write spoken, conversational sentences — NO Markdown, no stage ")
        append("directions, no text in brackets, no sound effects. Open with a one-line welcome, ")
        append("walk through the most important points, and close with a short takeaway. Aim for ")
        append("12 to 20 turns. Write numbers the way you would SAY them (\"sixty-eight percent\"), ")
        append("never as symbols or math.\n")
        append("Use only information supported by the digest; do not invent facts. Output only the ")
        append("dialogue lines — no title, no preamble.\n\n")
        append("--- DIGEST START ---\n")
        append(digest.trim())
        append("\n--- DIGEST END ---\n")
    }
}
