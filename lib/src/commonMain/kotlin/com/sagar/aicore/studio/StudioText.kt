/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore.studio

/**
 * Tolerant cleanup of model output for Studio artifacts. Small on-device models
 * (Gemma, etc.) habitually wrap numbers/units in LaTeX math (`$9.5 \text{ g/dL}$`,
 * `$3.72 \times 10^6$`, `$68.3\%$`) even when asked not to. Our Markdown renderer
 * has no LaTeX support, so that shows up as raw `$`, `\text`, `\times`, `^`, `\%`.
 *
 * This strips the math delimiters and rewrites the common commands into plain
 * Unicode so the briefing reads cleanly. It is deliberately lenient: anything it
 * doesn't recognise just loses its backslash rather than hard-failing.
 */
fun sanitizeStudioMarkdown(raw: String): String {
    if (raw.isBlank()) return raw
    var s = raw

    // 1. Unwrap math delimiters, keeping the inner text. Inline `$…$` is not allowed
    //    to cross a newline so a stray `$` can't swallow a paragraph.
    //    `[\s\S]` = "any char incl. newline" (DOTALL is JVM-only RegexOption, not in commonMain).
    s = s.replace(Regex("\\$\\$([\\s\\S]+?)\\$\\$")) { it.groupValues[1] }
    s = s.replace(Regex("(?<!\\\\)\\$([^$\\n]+?)(?<!\\\\)\\$")) { it.groupValues[1] }
    s = s.replace(Regex("\\\\\\(([\\s\\S]+?)\\\\\\)")) { it.groupValues[1] }
    s = s.replace(Regex("\\\\\\[([\\s\\S]+?)\\\\\\]")) { it.groupValues[1] }

    // 2. Text wrappers → inner content.
    s = s.replace(Regex("\\\\(?:text|mathrm|mathbf|mathit|operatorname)\\{([^{}]*)\\}")) { it.groupValues[1] }

    // 3. Fractions → a/b.
    s = s.replace(Regex("\\\\frac\\{([^{}]*)\\}\\{([^{}]*)\\}")) { "${it.groupValues[1]}/${it.groupValues[2]}" }

    // 4. Symbol commands → Unicode (word-boundary so \le doesn't eat \leq's tail).
    val symbols = mapOf(
        "times" to "×", "cdot" to "·", "div" to "÷", "pm" to "±",
        "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥",
        "approx" to "≈", "neq" to "≠", "rightarrow" to "→", "to" to "→",
        "mu" to "µ", "alpha" to "α", "beta" to "β", "circ" to "°", "degree" to "°",
    )
    for ((cmd, glyph) in symbols) {
        s = s.replace(Regex("\\\\$cmd(?![a-zA-Z])"), glyph)
    }

    // 5. Escaped specials → the literal character.
    s = s.replace("\\%", "%").replace("\\#", "#").replace("\\&", "&")
        .replace("\\_", "_").replace("\\{", "{").replace("\\}", "}").replace("\\\$", "$")

    // 6. Thin/explicit spaces; LaTeX line break `\\` → newline.
    s = s.replace(Regex("\\\\[,;:! ]"), " ").replace("\\\\", "\n")

    // 6b. Natural-language math the model writes despite the prompt — normalise
    //     conservatively (only adjacent to digits so prose isn't mangled):
    //     "10 to the power of 6" → "10^6", "3.72 times 10" → "3.72 × 10",
    //     "68.3 percent" → "68.3%". The "^6" is turned into a superscript in step 7.
    s = s.replace(Regex("\\s*(?:raised\\s+)?to the power of\\s*", RegexOption.IGNORE_CASE), "^")
    s = s.replace(Regex("(?<=[0-9)])\\s*times\\s*(?=[0-9])", RegexOption.IGNORE_CASE), " × ")
    s = s.replace(Regex("(\\d(?:\\.\\d+)?)\\s*percent\\b", RegexOption.IGNORE_CASE), "$1%")

    // 7. Super/subscripts (multi-digit exponents too, e.g. "^12").
    s = s.replace(Regex("\\^\\{([^{}]*)\\}")) { superscript(it.groupValues[1]) }
    s = s.replace(Regex("\\^([0-9n+\\-]+)")) { superscript(it.groupValues[1]) }
    s = s.replace(Regex("_\\{([^{}]*)\\}")) { it.groupValues[1] }

    // 8. Any leftover command: drop the backslash, keep the letters.
    s = s.replace(Regex("\\\\([a-zA-Z]+)")) { it.groupValues[1] }

    // 9. Tidy up the spaces we may have introduced.
    s = s.replace(Regex("[ \\t]{2,}"), " ").replace(Regex(" +\n"), "\n")
    return s.trim()
}

/**
 * Plain-text projection of a Studio artifact for text-to-speech. Builds on
 * [sanitizeStudioMarkdown] (LaTeX → Unicode) and then drops Markdown *structure*
 * — headings, emphasis, code ticks, list/quote markers, table pipes, link URLs —
 * so the speech engine reads natural prose instead of pronouncing symbols.
 */
fun stripForSpeech(markdown: String): String {
    var s = sanitizeStudioMarkdown(markdown)
    // Links/images: keep the visible text, drop the target.
    s = s.replace("![", "[")
    s = s.replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
    // Line-leading structure: heading hashes, block quotes, list bullets.
    s = s.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
    s = s.replace(Regex("(?m)^\\s{0,3}>\\s?"), "")
    s = s.replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
    // Table divider rows (e.g. |---|:--:|) → gone; remaining cell pipes → a pause.
    s = s.replace(Regex("(?m)^[\\s|:-]+$"), "")
    s = s.replace(Regex("\\s*\\|\\s*"), ", ")
    // Inline emphasis / code markers.
    s = s.replace(Regex("[*_`~]"), "")
    // Tidy whitespace; turn paragraph breaks into a spoken pause.
    s = s.replace(Regex("\\n{2,}"), ".\n").replace(Regex("[ \\t]{2,}"), " ")
    return s.trim()
}

private val SUPERSCRIPTS = mapOf(
    '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
    '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
    '+' to '⁺', '-' to '⁻', 'n' to 'ⁿ',
)

private fun superscript(s: String): String =
    s.map { SUPERSCRIPTS[it] ?: it }.joinToString("")
