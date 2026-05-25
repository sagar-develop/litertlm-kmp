package com.sagar.aicore

import com.sagar.aicore.di.AppScope
import me.tatarka.inject.annotations.Inject

/**
 * Hardcoded [ModelCatalog] for Phase 1. Replaces the loose URL/filename
 * string constants previously held in `SetupViewModel` — both Setup and
 * the [EngineRegistry] read from here so they can't disagree.
 *
 * Future: backed by a Supabase `model_catalog` table so admins can ship
 * fine-tuned variants without an APK update (see docs/plans/supabase-backend.md
 * §5.1).
 */
@AppScope
@Inject
class InMemoryModelCatalog : ModelCatalog {

    private val entries: List<ModelDescriptor> = listOf(
        ModelDescriptor(
            id = "gemma-4-e2b-it-litertlm",
            url = "https://firebasestorage.googleapis.com/v0/b/learnlm-f8868.firebasestorage.app/o/models%2Fllm%2Fgemma-4-E2B-it.litertlm?alt=media&token=a6894a65-8c8b-4582-acc8-4b5d51c9d238",
            fileName = "gemma-4-E2B-it.litertlm",
            sizeBytes = 2_588_000_000L,
            format = ModelFormat.LITERTLM,
            role = ModelRole.LLM_PRIMARY,
            // 6-9 GB tier (CPH2723 = 8 GB runs comfortably). < 6 GB devices
            // surface SetupUiState.DeviceNotSupported.
            minDeviceRamMb = 6000,
        ),
        ModelDescriptor(
            id = "gemma-4-e4b-it-litertlm",
            url = "https://firebasestorage.googleapis.com/v0/b/learnlm-f8868.firebasestorage.app/o/models%2Fllm%2Fgemma-4-E4B-it.litertlm?alt=media&token=244afafa-c0cc-4181-af6e-a4e2cf70233c",
            fileName = "gemma-4-E4B-it.litertlm",
            sizeBytes = 3_659_530_240L,
            format = ModelFormat.LITERTLM,
            role = ModelRole.LLM_PRIMARY,
            // 10+ GB tier — flagship devices get the larger E4B variant.
            minDeviceRamMb = 10000,
        ),
        ModelDescriptor(
            id = "universal-sentence-encoder",
            url = "https://firebasestorage.googleapis.com/v0/b/learnlm-f8868.firebasestorage.app/o/models%2Fembeddings%2Funiversal_sentence_encoder.tflite?alt=media&token=7ea824d1-2986-420c-8f4a-9ff2be0c0b7d",
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
