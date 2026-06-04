/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore

private const val THINK_OPEN = "<think>"
private const val THINK_CLOSE = "</think>"

/**
 * Reasoning models (Qwen3, DeepSeek-R1-Distill) stream a `<think>…</think>` block
 * of chain-of-thought *before* the answer. We don't want that monologue in the
 * chat bubble, so:
 *
 * - once the block closes, return only what follows it (the answer);
 * - while the block is still open — or its opening tag is only partially streamed
 *   in (`<thi…`) — return an empty string, so the bubble keeps showing its typing
 *   indicator instead of the raw reasoning;
 * - ordinary output (no think tags) is returned unchanged.
 *
 * Pure + streaming-safe: call it on the full accumulated raw text on every token.
 */
fun renderAssistantText(raw: String): String {
    val close = raw.lastIndexOf(THINK_CLOSE)
    if (close >= 0) return raw.substring(close + THINK_CLOSE.length).trimStart()
    if (raw.contains(THINK_OPEN)) return ""
    val trimmed = raw.trimStart()
    if (trimmed.isNotEmpty() && THINK_OPEN.startsWith(trimmed)) return ""
    return raw
}
