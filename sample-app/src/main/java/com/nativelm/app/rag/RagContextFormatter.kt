/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.rag

import com.nativelm.app.data.db.ScoredChunk

/**
 * Turns retrieved chunks into the grounding block fed to the model, plus the
 * citations shown under the answer.
 *
 * Safety: retrieved text is untrusted (it can contain prompt-like instructions),
 * so the block is fenced with explicit `--- CONTEXT START/END ---` markers and the
 * total is capped at [MAX_CONTEXT_CHARS]. Near-duplicate chunks (a side effect of
 * the chunk overlap) are de-duplicated before formatting.
 */
object RagContextFormatter {

    const val MAX_CONTEXT_CHARS: Int = 4000
    private const val START_MARKER = "--- CONTEXT START ---"
    private const val END_MARKER = "--- CONTEXT END ---"
    private const val MIN_TRUNCATED_BODY = 40
    private const val SNIPPET_LEN = 140
    private const val DEDUP_KEY_LEN = 120

    fun format(chunks: List<ScoredChunk>, titleOf: (Long) -> String): RetrievedContext {
        if (chunks.isEmpty()) return RetrievedContext.EMPTY

        val seen = HashSet<String>()
        val sb = StringBuilder().append(START_MARKER).append('\n')
        val citations = ArrayList<Citation>()
        var budget = MAX_CONTEXT_CHARS

        for (scored in chunks) {
            val chunk = scored.chunk
            val body = chunk.text.trim()
            if (body.isEmpty()) continue
            if (!seen.add(body.lowercase().take(DEDUP_KEY_LEN))) continue // drop near-duplicate

            val title = titleOf(chunk.documentId)
            val header = if (chunk.pageNumber > 0) "[$title, p.${chunk.pageNumber}]" else "[$title]"
            val block = "$header\n$body\n\n"

            if (block.length <= budget) {
                sb.append(block)
                citations += Citation(title, chunk.pageNumber, snippet(body))
                budget -= block.length
            } else {
                // Truncate this final block to what's left, if it's worth including.
                val room = budget - header.length - 2
                if (room >= MIN_TRUNCATED_BODY) {
                    sb.append(header).append('\n').append(body.take(room)).append("\n\n")
                    citations += Citation(title, chunk.pageNumber, snippet(body))
                }
                break
            }
        }

        if (citations.isEmpty()) return RetrievedContext.EMPTY
        sb.append(END_MARKER)
        return RetrievedContext(sb.toString(), citations)
    }

    private fun snippet(body: String): String =
        body.replace('\n', ' ').replace(Regex("\\s+"), " ").trim().take(SNIPPET_LEN)
}
