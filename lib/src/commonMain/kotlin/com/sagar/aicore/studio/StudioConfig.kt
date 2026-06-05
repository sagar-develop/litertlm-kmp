/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore.studio

/**
 * Map-reduce sizing + per-artifact token budgets for [StudioGenerator], so a
 * consumer can tune Studio for a different model/context window without forking.
 * Defaults are the values NativeLM shipped with.
 *
 * @param mapWindowChars max characters packed into one MAP (summarize) window.
 * @param mapTokens token budget for a MAP call.
 * @param reduceGroupChars max characters per REDUCE (combine) group.
 * @param reduceTokens token budget for a REDUCE call.
 * @param maxDigestChars cap on the reduced digest fed to the final prompt.
 * @param maxReducePasses fold passes before giving up on shrinking the digest.
 * @param *Tokens token budget for each final artifact prompt.
 */
data class StudioConfig(
    val mapWindowChars: Int = 3000,
    val mapTokens: Int = 256,
    val reduceGroupChars: Int = 3500,
    val reduceTokens: Int = 384,
    val maxDigestChars: Int = 4000,
    val maxReducePasses: Int = 3,
    val briefingTokens: Int = 768,
    val faqTokens: Int = 1024,
    val topicsTokens: Int = 768,
    val studyGuideTokens: Int = 1280,
    val timelineTokens: Int = 1280,
    val mindMapTokens: Int = 1024,
    val audioOverviewTokens: Int = 1024,
    val podcastTokens: Int = 1536,
)
