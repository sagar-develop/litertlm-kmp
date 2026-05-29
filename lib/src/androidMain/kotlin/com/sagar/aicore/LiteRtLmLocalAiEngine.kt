/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.tool
import com.sagar.aicore.di.AppScope
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.tatarka.inject.annotations.Inject

/**
 * Gemma 4 (E2B / E4B) on Android via LiteRT-LM 0.11.0. Sole [LocalAiEngine].
 *
 * - LiteRT-LM's `Engine.createConversation()` handles Gemma 4 chat
 *   templating internally; [formatPrompt] just composes system + context +
 *   user into one string and lets the engine wrap.
 * - Multimodal: image attachments flow through `EngineConfig.visionBackend`
 *   + `Content.ImageBytes` (see [runStreaming] / [runStructured]).
 * - Structured output goes through `automaticToolCalling = false` —
 *   LiteRT-LM does NOT invoke the Kotlin `@Tool` method in that mode;
 *   args land in `message.toolCalls[].arguments` as `Map<String, Any?>`
 *   and are passed through verbatim via [EngineState.ToolCallEmitted].
 *   The caller owns key conventions (LiteRT-LM emits snake_case keys and
 *   Doubles for Int params — see EngineState.ToolCallEmitted doc).
 */
@AppScope
@Inject
class LiteRtLmLocalAiEngine(
    private val hardwareProvider: HardwareProvider,
) : LocalAiEngine {

    override val descriptor: EngineDescriptor = EngineDescriptor(
        id = "litert-lm-gemma-4",
        displayName = "Gemma 4 (LiteRT-LM)",
        supportsTools = true,
        // Multimodal Gemma 4 (E2B / E4B) vision is wired through
        // EngineConfig.visionBackend + Content.ImageBytes attachments below.
        // The .litertlm bundles for these variants ship vision-encoder
        // weights; a text-only Gemma 4 build would fail at init when
        // visionBackend is non-null. Audio plumbing is not wired yet.
        supportsVision = true,
        supportsAudio = false,
        maxContextTokens = 8192,
        // 2.59–3.66 GB on disk depending on E2B/E4B; in-memory varies by
        // backend. Empirical headroom for the loaded engine + KV cache.
        approximateMemoryFootprintMb = 3800,
        // < 6 GB devices surface DeviceNotSupported; E2B serves the
        // 6–9 GB tier, E4B serves 10+ GB.
        minDeviceRamMb = 6000,
        consumes = ModelFormat.LITERTLM,
    )

    private var engine: Engine? = null
    private val mutex = Mutex()

    override suspend fun initializeEngine(modelPath: String): EngineState<Unit> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (engine != null) return@withLock EngineState.TokenGenerated(Unit)
                try {
                    // Wire vision. visionBackend = CPU is the safe default;
                    // GPU vision delegates exist but vary by device driver and
                    // aren't worth the support burden. The text backend stays
                    // defaulted (the engine picks the best available).
                    // maxNumImages = 1 matches a single-image-per-turn flow;
                    // raise it if a UI ever needs multiple images per message.
                    engine = Engine(
                        EngineConfig(
                            modelPath = modelPath,
                            visionBackend = Backend.CPU(),
                            maxNumImages = 1,
                        ),
                    ).also { it.initialize() }
                    Napier.d(tag = TAG) {
                        "Engine initialized at $modelPath with visionBackend=CPU, maxNumImages=1"
                    }
                    EngineState.TokenGenerated(Unit)
                } catch (t: Throwable) {
                    Napier.e(t, tag = TAG) { "Engine init failed" }
                    EngineState.Error(HardwareFault.DelegateFailure(t.message ?: "LiteRT-LM init failed"))
                }
            }
        }

    override fun generateStream(request: AiEngineRequest): Flow<EngineState<String>> = callbackFlow {
        val activeEngine = engine
        if (activeEngine == null) {
            trySend(EngineState.Error(HardwareFault.ModelNotLoaded()))
            close()
            return@callbackFlow
        }

        // Single-tenant guard — serialize generateStream calls so native is
        // never asked to handle overlapping requests. 60s upper bound.
        try {
            withTimeout(60_000L) { mutex.lock() }
        } catch (e: TimeoutCancellationException) {
            trySend(EngineState.Error(HardwareFault.DelegateFailure(
                "Engine is still busy with a previous request. Please try again."
            )))
            close()
            return@callbackFlow
        }

        if (request.seed != null) {
            Napier.v(tag = TAG) {
                "seed=${request.seed} received; LiteRT-LM doesn't expose a per-call seed — " +
                    "orchestrator inlines it into the prompt for variation."
            }
        }
        // Image attachments flow into LiteRT-LM via Content.ImageBytes wrapped
        // in a Contents bundle alongside the text prompt. Non-image attachments
        // (audio) still log + drop here — audio plumbing is not wired yet.
        val imageAttachments = request.attachments.filterIsInstance<Attachment.Image>()
        val nonImageDropped = request.attachments.size - imageAttachments.size
        if (nonImageDropped > 0) {
            Napier.v(tag = TAG) {
                "$nonImageDropped non-image attachment(s) ignored — only image input is wired."
            }
        }

        trySend(EngineState.Generating)

        val job = launch(Dispatchers.IO) {
            try {
                if (request.requireStructuredOutput && request.toolSchema != null) {
                    runStructured(activeEngine, request, imageAttachments) { trySend(it) }
                } else {
                    runStreaming(activeEngine, request, imageAttachments) { trySend(it) }
                }
                trySend(EngineState.Idle)
            } catch (t: Throwable) {
                Napier.e(t, tag = TAG) { "generateStream failed" }
                trySend(EngineState.Error(HardwareFault.DelegateFailure(t.message ?: "Inference failed")))
            } finally {
                if (mutex.isLocked) mutex.unlock()
                close()
            }
        }

        awaitClose { job.cancel() }
    }

    private suspend fun runStreaming(
        activeEngine: Engine,
        request: AiEngineRequest,
        images: List<Attachment.Image>,
        emit: (EngineState<String>) -> Unit,
    ) {
        activeEngine.createConversation().use { conv ->
            val flow = if (images.isEmpty()) {
                // Text-only path stays on the String overload.
                conv.sendMessageAsync(request.formattedPrompt)
            } else {
                // Multimodal — bundle text + image bytes into Contents.
                val parts = buildList<Content> {
                    add(Content.Text(request.formattedPrompt))
                    for (img in images) add(Content.ImageBytes(img.bytes))
                }
                conv.sendMessageAsync(Contents.of(parts))
            }
            flow.collect { message ->
                // sendMessageAsync emits Flow<Message>; toString() gives the
                // text-token delta per emission (per LiteRT-LM Kotlin docs +
                // verified in spike — accumulating these reproduces the full
                // response).
                emit(EngineState.TokenGenerated<String>(message.toString()))
            }
        }
    }

    private fun runStructured(
        activeEngine: Engine,
        request: AiEngineRequest,
        images: List<Attachment.Image>,
        emit: (EngineState<String>) -> Unit,
    ) {
        val jsonSpec: String = when (val schema = request.toolSchema) {
            is ToolSchema.Definition -> schema.toOpenApiJson()
            is ToolSchema.OpenApi -> schema.json
            null -> {
                emit(EngineState.Error(HardwareFault.DelegateFailure(
                    "runStructured called with null toolSchema"
                )))
                return
            }
        }

        val openApiTool = object : OpenApiTool {
            override fun getToolDescriptionJsonString(): String = jsonSpec
            // Unused with automaticToolCalling=false — LiteRT-LM populates
            // message.toolCalls instead of calling this.
            override fun execute(args: String): String = "ok"
        }
        val config = ConversationConfig(
            systemInstruction = Contents.of(STRUCTURED_OUTPUT_SYSTEM_INSTRUCTION),
            tools = listOf(tool(openApiTool)),
            automaticToolCalling = false,
        )

        activeEngine.createConversation(config).use { conv ->
            val message = if (images.isEmpty()) {
                conv.sendMessage(request.formattedPrompt, emptyMap())
            } else {
                val parts = buildList<Content> {
                    add(Content.Text(request.formattedPrompt))
                    for (img in images) add(Content.ImageBytes(img.bytes))
                }
                conv.sendMessage(Contents.of(parts), emptyMap())
            }
            val calls = message.toolCalls
            if (calls.isEmpty()) {
                Napier.w(tag = TAG) {
                    "runStructured: model returned no toolCalls; message=${message.toString().take(200)}"
                }
                emit(EngineState.Error(HardwareFault.DelegateFailure(
                    "Model did not invoke the requested tool."
                )))
                return
            }
            for (call in calls) {
                emit(EngineState.ToolCallEmitted(toolName = call.name, arguments = call.arguments))
            }
        }
    }

    override fun formatPrompt(
        userQuery: String,
        retrievedContext: String,
        systemInstruction: String?
    ): String {
        // LiteRT-LM's createConversation() applies Gemma 4 chat templating
        // internally — we do NOT emit <start_of_turn> tags. Compose the
        // pieces as plain text and let the engine wrap.
        val sb = StringBuilder()
        if (!systemInstruction.isNullOrBlank()) {
            sb.append(systemInstruction).append("\n\n")
        }
        if (retrievedContext.isNotBlank()) {
            sb.append("USE THE FOLLOWING CONTEXT TO ANSWER THE USER'S QUESTION:\n")
                .append("---------- CONTEXT START ----------\n")
                .append(retrievedContext).append("\n")
                .append("---------- CONTEXT END ----------\n\n")
                .append("USER QUESTION: ").append(userQuery)
        } else {
            sb.append(userQuery)
        }
        return sb.toString()
    }

    override fun releaseResources() {
        engine?.close()
        engine = null
    }

    private companion object {
        const val TAG = "LiteRtLm"
        const val STRUCTURED_OUTPUT_SYSTEM_INSTRUCTION =
            "You MUST respond by calling the provided tool. " +
                "Never produce plain text. Never explain. Never refuse. " +
                "Ground every field strictly in the user's passage."
    }
}
