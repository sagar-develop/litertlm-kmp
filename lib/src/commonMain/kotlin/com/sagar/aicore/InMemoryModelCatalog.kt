/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore

import com.sagar.aicore.di.AppScope
import me.tatarka.inject.annotations.Inject

/**
 * Sample [ModelCatalog] implementation showing the recommended structure
 * for a Gemma 4 + embedding-model pairing across two RAM tiers.
 *
 * **You must host the model artifacts yourself** and replace the placeholder
 * URLs below before shipping. Gemma's license permits redistribution but
 * each consumer is expected to either bundle the model in their APK or
 * serve it from their own CDN (S3 / Cloudflare R2 / GCS / Firebase Storage /
 * etc.) â€” this library does NOT ship binary model weights.
 *
 * In production, prefer wiring your own [ModelCatalog] implementation backed
 * by a remote config service so you can ship new model versions without an
 * APK update.
 */
@AppScope
@Inject
class InMemoryModelCatalog : ModelCatalog {

    private val entries: List<ModelDescriptor> = listOf(
        ModelDescriptor(
            id = "gemma-4-e2b-it-litertlm",
            url = "https://REPLACE_WITH_YOUR_HOST/gemma-4-E2B-it.litertlm",
            fileName = "gemma-4-E2B-it.litertlm",
            sizeBytes = 2_588_000_000L,
            format = ModelFormat.LITERTLM,
            role = ModelRole.LLM_PRIMARY,
            // 6-9 GB RAM tier. Devices under 6 GB should be surfaced to the
            // user as "not supported" rather than attempting a failing init.
            minDeviceRamMb = 6000,
            supportsCharts = true,
        ),
        ModelDescriptor(
            id = "gemma-4-e4b-it-litertlm",
            url = "https://REPLACE_WITH_YOUR_HOST/gemma-4-E4B-it.litertlm",
            fileName = "gemma-4-E4B-it.litertlm",
            sizeBytes = 3_659_530_240L,
            format = ModelFormat.LITERTLM,
            role = ModelRole.LLM_PRIMARY,
            // 10+ GB RAM tier â€” flagship devices get the larger E4B variant.
            minDeviceRamMb = 10000,
            supportsCharts = true,
        ),
        ModelDescriptor(
            id = "universal-sentence-encoder",
            url = "https://REPLACE_WITH_YOUR_HOST/universal_sentence_encoder.tflite",
            fileName = "universal_sentence_encoder.tflite",
            sizeBytes = 6_120_274L,
            format = ModelFormat.MEDIAPIPE_TEXT_EMBEDDER,
            role = ModelRole.EMBEDDING,
            minDeviceRamMb = 0,
        ),
    )

    override fun all(): List<ModelDescriptor> = entries

    override fun byId(id: String): ModelDescriptor? = entries.firstOrNull { it.id == id }

    override fun byRole(role: ModelRole): List<ModelDescriptor> = entries.filter { it.role == role }
}
