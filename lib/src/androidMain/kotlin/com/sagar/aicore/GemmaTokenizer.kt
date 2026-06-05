/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import java.nio.file.Paths

/** Tokenized input for the ONNX embedder: parallel token-id and attention-mask arrays. */
data class TokenizedInput(val ids: LongArray, val attentionMask: LongArray) {
    val length: Int get() = ids.size
}

/**
 * Turns text into model input ids + attention mask for [OnnxEmbeddingEngine].
 * EmbeddingGemma uses the Gemma SentencePiece vocabulary; we load it from the
 * `tokenizer.json` companion that ships next to the model.
 */
interface GemmaTokenizer {
    fun encode(text: String): TokenizedInput
}

/**
 * [GemmaTokenizer] backed by the HuggingFace tokenizers runtime (DJL binding),
 * reading the model's `tokenizer.json`. Truncates to [maxLength] tokens — chunks
 * are ~500 chars so this bounds latency/memory without losing content.
 *
 * Note: the exact padding/truncation knobs depend on the shipped `tokenizer.json`;
 * verify ids/mask shapes against the chosen EmbeddingGemma ONNX export on-device.
 */
class HfGemmaTokenizer(
    tokenizerJsonPath: String,
    private val maxLength: Int = 512,
) : GemmaTokenizer {

    private val tokenizer: HuggingFaceTokenizer =
        HuggingFaceTokenizer.builder()
            .optTokenizerPath(Paths.get(tokenizerJsonPath))
            .optAddSpecialTokens(true)
            .optTruncation(true)
            .optMaxLength(maxLength)
            .build()

    override fun encode(text: String): TokenizedInput {
        val enc = tokenizer.encode(text)
        return TokenizedInput(ids = enc.ids, attentionMask = enc.attentionMask)
    }
}
