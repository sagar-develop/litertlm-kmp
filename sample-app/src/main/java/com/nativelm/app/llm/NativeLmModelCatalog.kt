/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.llm

import com.sagar.aicore.ModelCatalog
import com.sagar.aicore.ModelDescriptor
import com.sagar.aicore.ModelFormat
import com.sagar.aicore.ModelRole

/**
 * NativeLM's model catalog. Unlike the engine's sample [com.sagar.aicore.InMemoryModelCatalog]
 * (placeholder hosts), this points at verified Hugging Face `resolve` URLs.
 *
 * The Gemma `.litertlm` files live in the public `litert-community/gemma-4-*-litert-lm`
 * repos (not gated). [ModelDescriptor.requiresAuth] is kept `true` so the app
 * exercises the token flow — it sends `Authorization: Bearer <hf-token>`, which
 * Hugging Face accepts. For a strictly license-gated source (token mandatory),
 * point these at `google/gemma-3n-E2B-it-litert-lm` / `…-E4B-…` instead.
 */
class NativeLmModelCatalog : ModelCatalog {

    private val entries: List<ModelDescriptor> = listOf(
        ModelDescriptor(
            id = "gemma-4-e2b-it-litertlm",
            url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm?download=true",
            fileName = "gemma-4-E2B-it.litertlm",
            sizeBytes = 2_588_000_000L,
            format = ModelFormat.LITERTLM,
            role = ModelRole.LLM_PRIMARY,
            minDeviceRamMb = 6000,
            requiresAuth = true,
        ),
        ModelDescriptor(
            id = "gemma-4-e4b-it-litertlm",
            url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm?download=true",
            fileName = "gemma-4-E4B-it.litertlm",
            sizeBytes = 3_659_530_240L,
            format = ModelFormat.LITERTLM,
            role = ModelRole.LLM_PRIMARY,
            minDeviceRamMb = 10000,
            requiresAuth = true,
        ),
        ModelDescriptor(
            id = "universal-sentence-encoder",
            // Public MediaPipe model — no auth required.
            url = "https://storage.googleapis.com/mediapipe-models/text_embedder/universal_sentence_encoder/float32/latest/universal_sentence_encoder.tflite",
            fileName = "universal_sentence_encoder.tflite",
            sizeBytes = 6_120_274L,
            format = ModelFormat.MEDIAPIPE_TEXT_EMBEDDER,
            role = ModelRole.EMBEDDING,
            minDeviceRamMb = 0,
            requiresAuth = false,
        ),
    )

    override fun all(): List<ModelDescriptor> = entries
    override fun byId(id: String): ModelDescriptor? = entries.firstOrNull { it.id == id }
    override fun byRole(role: ModelRole): List<ModelDescriptor> = entries.filter { it.role == role }
}
