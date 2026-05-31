/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface LocalAiEngine {
    /** Self-description used by [EngineRegistry] for selection + capability gating. */
    val descriptor: EngineDescriptor

    /**
     * Loads the model at [modelPath] into a native engine. [supportsVision]
     * tells the engine whether the bundle is multimodal: a vision backend is
     * attached only when true (text-only bundles fail init with one attached,
     * and skipping it saves memory). Defaults to true for backward compatibility.
     */
    suspend fun initializeEngine(modelPath: String, supportsVision: Boolean = true): EngineState<Unit>

    /**
     * Stateless one-shot generation. Each call runs in its own throwaway
     * conversation — no memory of prior calls. Use for structured output,
     * title generation, and any single-turn task. For multi-turn chat with
     * KV-cache reuse, use [openChatSession] instead.
     */
    fun generateStream(request: AiEngineRequest): Flow<EngineState<String>>

    /**
     * Opens a stateful multi-turn chat session backed by a persistent KV cache.
     *
     * [history] seeds prior turns and is re-prefilled once when the session
     * opens (surfaced as [SessionState.Warming] — the UI's "building
     * understanding" moment); pass an empty list for a brand-new chat. After
     * warming, [ChatSession.sendTurn] reuses the KV cache, so each turn pays
     * prefill for the new message only — not for the whole history again.
     *
     * Only one session is live at a time: opening a new one closes any prior
     * session (a single native engine holds a single KV cache). Engines without
     * session support may throw [UnsupportedOperationException].
     */
    fun openChatSession(
        history: List<ChatTurn> = emptyList(),
        systemInstruction: String? = null,
        temperature: Float = 0.7f,
    ): ChatSession

    fun formatPrompt(
        userQuery: String,
        retrievedContext: String,
        systemInstruction: String?
    ): String
    fun releaseResources()
}

/** A prior conversation turn used to seed a [ChatSession] via [LocalAiEngine.openChatSession]. */
data class ChatTurn(val role: TurnRole, val text: String)

enum class TurnRole { USER, ASSISTANT }

/**
 * A live multi-turn chat session. Holds a persistent KV cache so context from
 * earlier turns is reused rather than re-prefilled. Close it (or open another)
 * to free the cache.
 */
interface ChatSession {
    /** Warming (re-prefilling seeded history) → Ready → … ; Failed on init error. */
    val state: StateFlow<SessionState>

    /**
     * Streams a reply to a new user turn, reusing the KV cache from prior turns.
     * Mirrors [LocalAiEngine.generateStream]'s [EngineState] stream.
     */
    fun sendTurn(request: AiEngineRequest): Flow<EngineState<String>>

    /** Interrupts the in-flight turn via native cancel. No-op when idle. */
    fun cancel()

    /** Exact metrics for the most recently completed turn (engine-reported, not
     *  estimated), or null before the first turn finishes. */
    fun lastTurnMetrics(): TurnMetrics?

    /** Releases the session and its KV cache. */
    fun close()
}

/** Engine-reported timing for one generated turn. */
data class TurnMetrics(
    val timeToFirstTokenSec: Double,
    val prefillTokensPerSecond: Double,
    val decodeTokensPerSecond: Double,
    val prefillTokenCount: Int,
    val decodeTokenCount: Int,
)

sealed class SessionState {
    /** Re-prefilling seeded history; not yet ready to accept a turn. */
    object Warming : SessionState()
    /** Ready to accept [ChatSession.sendTurn]. */
    object Ready : SessionState()
    /** Session failed to initialize. */
    data class Failed(val fault: HardwareFault) : SessionState()
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
 * Inputs the engine can receive alongside a text prompt. [Image] is consumed
 * by engines whose [EngineDescriptor.supportsVision] is true (wired through
 * LiteRT-LM `Content.ImageBytes`); `Document` is carried inline in
 * [AiEngineRequest.formattedPrompt] by the caller. [Audio] exists so the
 * caller + UI layers can carry it ahead of any engine wiring audio inference.
 * Engines ignore attachment subclasses they don't support — they never fail
 * the request.
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
    class OutOfMemory(message: String = "Context length exceeded available memory.") : HardwareFault(message)
    class DelegateFailure(message: String = "NPU/GPU Delegate failed to initialize.") : HardwareFault(message)
    class ModelNotLoaded(message: String = "Engine stream called before weights loaded.") : HardwareFault(message)
}
