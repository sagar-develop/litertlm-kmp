/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore

import kotlinx.coroutines.flow.Flow

interface LocalAiEngine {
    /** Self-description used by [EngineRegistry] for selection + capability gating. */
    val descriptor: EngineDescriptor

    suspend fun initializeEngine(modelPath: String): EngineState<Unit>
    fun generateStream(request: AiEngineRequest): Flow<EngineState<String>>
    fun formatPrompt(
        userQuery: String,
        retrievedContext: String,
        systemInstruction: String?
    ): String
    fun releaseResources()
}

data class AiEngineRequest(
    val formattedPrompt: String,
    /**
     * Multimodal inputs. Empty for text-only prompts. Engines that don't
     * support a given [Attachment] subclass MUST ignore those entries â€”
     * they do not fail the request.
     */
    val attachments: List<Attachment> = emptyList(),
    val temperature: Float = 0.7f,
    val maxTokens: Int = 4096,
    /**
     * Engines that support structured output use [toolSchema] to constrain
     * the model; others ignore the flag.
     */
    val requireStructuredOutput: Boolean = false,
    /**
     * Tool/function-call schema for structured-output mode. Always
     * [ToolSchema.Definition] from orchestrator code (engine-agnostic
     * primitives); engines convert internally. [ToolSchema.OpenApi] is an
     * escape hatch for callers that already have raw OpenAPI JSON.
     */
    val toolSchema: ToolSchema? = null,
    /**
     * Per-call seed for sampling. Drives reproducibility when the same
     * prompt+seed pair must yield the same generation.
     */
    val seed: Long? = null,
)

/**
 * Inputs the engine can receive alongside a text prompt. Today only
 * `Document` is consumed (carried inline in [AiEngineRequest.formattedPrompt]
 * by the orchestrator); [Image] and [Audio] data classes exist so the
 * orchestrator + UI layers can start carrying them ahead of any engine
 * actually wiring vision/audio inference.
 */
sealed class Attachment {
    data class Image(val bytes: ByteArray, val mimeType: String) : Attachment()
    data class Audio(val bytes: ByteArray, val mimeType: String) : Attachment()
    data class Document(val text: String) : Attachment()
}

/**
 * Engine-agnostic representation of a tool/function definition that the
 * model can call. Carried on [AiEngineRequest.toolSchema] when
 * `requireStructuredOutput = true`.
 *
 * Always describe tools as [Definition] from business-logic code
 * (orchestrator) â€” primitives only, no engine SDK types. Each
 * [LocalAiEngine] implementation converts [Definition] to its native
 * tool representation internally. [OpenApi] is an escape hatch for
 * engines that consume raw OpenAPI JSON directly; not used by the
 * orchestrator today.
 */
sealed class ToolSchema {
    data class Definition(
        val name: String,
        val description: String,
        val parameters: List<ToolParameter>,
    ) : ToolSchema()

    data class OpenApi(val json: String) : ToolSchema()
}

data class ToolParameter(
    val name: String,
    val type: ToolParameterType,
    val description: String,
    val required: Boolean = true,
)

/**
 * Primitive types for [ToolParameter]. Engines map these to their native
 * schema vocabulary (JSON Schema "string"/"integer"/"number"/"boolean"/"array").
 */
sealed class ToolParameterType {
    object StringT : ToolParameterType()
    object IntegerT : ToolParameterType()
    object NumberT : ToolParameterType()
    object BooleanT : ToolParameterType()
    data class ArrayT(val itemType: ToolParameterType) : ToolParameterType()
}

sealed class EngineState<out T> {
    object Idle : EngineState<Nothing>()
    object Generating : EngineState<Nothing>()
    data class TokenGenerated<T>(val data: T) : EngineState<T>()
    /**
     * Emitted when the model invoked a tool via structured output rather
     * than streaming free text. Only engines with [EngineDescriptor.supportsTools]
     * = true ever emit this. Free-text callers can ignore it (they only
     * consume [TokenGenerated]); structured-output callers consume this to
     * read the constrained payload.
     *
     * `arguments` keys reflect what the engine's tool-call surface returns
     * (LiteRT-LM converts camelCase Kotlin params to snake_case schema
     * keys; numeric params may surface as Double).
     */
    data class ToolCallEmitted(
        val toolName: String,
        val arguments: Map<String, Any?>,
    ) : EngineState<Nothing>()
    data class Error(val fault: HardwareFault) : EngineState<Nothing>()
}

sealed class HardwareFault(message: String) : Exception(message) {
    class OutOfMemory(message: String = "128K Context exceeded available mobile RAM.") : HardwareFault(message)
    class DelegateFailure(message: String = "NPU/GPU Delegate failed to initialize.") : HardwareFault(message)
    class ModelNotLoaded(message: String = "Engine stream called before weights loaded.") : HardwareFault(message)
}
