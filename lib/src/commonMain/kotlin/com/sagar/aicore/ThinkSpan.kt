/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore

/** Default reasoning-span delimiters (Qwen3 / DeepSeek-R1-Distill). */
const val DEFAULT_THINK_OPEN: String = "<think>"
const val DEFAULT_THINK_CLOSE: String = "</think>"

/**
 * Reasoning models stream a `<think>…</think>` block of chain-of-thought *before*
 * the answer. We don't want that monologue in the chat bubble, so:
 *
 * - once the block closes, return only what follows it (the answer);
 * - while the block is still open — or its opening tag is only partially streamed
 *   in (`<thi…`) — return an empty string, so the bubble keeps showing its typing
 *   indicator instead of the raw reasoning;
 * - ordinary output (no think tags) is returned unchanged.
 *
 * Pure + streaming-safe: call it on the full accumulated raw text on every token.
 * [openTag] / [closeTag] default to `<think>` / `</think>` but are overridable for
 * models that fence reasoning differently.
 */
fun renderAssistantText(
    raw: String,
    openTag: String = DEFAULT_THINK_OPEN,
    closeTag: String = DEFAULT_THINK_CLOSE,
): String {
    val close = raw.lastIndexOf(closeTag)
    if (close >= 0) return raw.substring(close + closeTag.length).trimStart()
    if (raw.contains(openTag)) return ""
    val trimmed = raw.trimStart()
    if (trimmed.isNotEmpty() && openTag.startsWith(trimmed)) return ""
    return raw
}
