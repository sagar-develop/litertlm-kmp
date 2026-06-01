/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import com.sagar.aicore.di.AppScope
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        // Footprint spans the LiteRT-LM lineup this engine runs: ~0.6 GB for the
        // Gemma 3 1B INT4 text bundle up to ~3.7 GB for Gemma 4 E4B. Per-model
        // RAM gating lives in the consumer's ModelCatalog (minDeviceRamMb per
        // descriptor); this floor is just the smallest tier the engine targets.
        approximateMemoryFootprintMb = 3800,
        minDeviceRamMb = 4000,
        consumes = ModelFormat.LITERTLM,
    )

    private var engine: Engine? = null
    private val mutex = Mutex()

    /** The single live chat session; opening a new one closes this. */
    private var currentSession: LiteRtLmChatSession? = null

    override suspend fun initializeEngine(modelPath: String, supportsVision: Boolean): EngineState<Unit> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (engine != null) return@withLock EngineState.TokenGenerated(Unit)
                try {
                    // Text backend = CPU with 6 threads. Phase-0 on-device
                    // benchmarking (CPH2723) showed GPU/NPU can't load the
                    // litert-community .litertlm bundles (GPU executor errors;
                    // NPU needs a TF_LITE_AUX section the bundle lacks), so CPU
                    // is the only viable path. Among thread counts, 6 gave the
                    // best decode tok/s while leaving the 2 prime cores free for
                    // the UI.
                    //
                    // Vision is wired ONLY for multimodal bundles (Gemma 4 E2B/E4B):
                    // a text-only bundle (e.g. Gemma 3 1B INT4) fails init when a
                    // vision backend is attached, and skipping it also frees memory
                    // — which is what lets the small INT4 models fit low-RAM devices.
                    val visionBackend = if (supportsVision) Backend.CPU() else null
                    engine = Engine(
                        EngineConfig(
                            modelPath = modelPath,
                            backend = Backend.CPU(numOfThreads = 6),
                            visionBackend = visionBackend,
                            maxNumImages = if (supportsVision) 1 else null,
                        ),
                    ).also { it.initialize() }
                    Napier.d(tag = TAG) {
                        "Engine initialized at $modelPath with backend=CPU(6), " +
                            "visionBackend=${if (supportsVision) "CPU" else "none"}"
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

    override fun openChatSession(
        history: List<ChatTurn>,
        systemInstruction: String?,
        temperature: Float,
    ): ChatSession {
        // One KV cache at a time on a single native engine — close any prior session.
        currentSession?.close()
        val session = LiteRtLmChatSession(engine, history, systemInstruction, temperature)
        currentSession = session
        return session
    }

    /** Maps an [AiEngineRequest]'s sampling fields to LiteRT-LM's [SamplerConfig].
     *  topK/topP use Gemma's standard values; the request drives temperature + seed. */
    private fun samplerFor(request: AiEngineRequest): SamplerConfig =
        SamplerConfig(SAMPLER_TOP_K, SAMPLER_TOP_P, request.temperature.toDouble(), request.seed?.toInt() ?: SAMPLER_SEED)

    /**
     * Stateful chat session over a persistent [Conversation]. The Conversation
     * retains its KV cache across [sendTurn] calls, so each turn only prefills
     * the new message. [history] is seeded as `ConversationConfig.initialMessages`
     * and re-prefilled once while [state] is [SessionState.Warming].
     */
    private inner class LiteRtLmChatSession(
        private val activeEngine: Engine?,
        history: List<ChatTurn>,
        systemInstruction: String?,
        temperature: Float,
    ) : ChatSession {

        private val _state = MutableStateFlow<SessionState>(SessionState.Warming)
        override val state: StateFlow<SessionState> = _state.asStateFlow()

        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        @Volatile
        private var conversation: Conversation? = null

        init {
            scope.launch {
                if (activeEngine == null) {
                    _state.value = SessionState.Failed(HardwareFault.ModelNotLoaded())
                    return@launch
                }
                mutex.withLock {
                    try {
                        val initial = history.map { turn ->
                            if (turn.role == TurnRole.USER) Message.user(turn.text)
                            else Message.model(turn.text)
                        }
                        val sampler = SamplerConfig(SAMPLER_TOP_K, SAMPLER_TOP_P, temperature.toDouble(), SAMPLER_SEED)
                        val config = if (systemInstruction.isNullOrBlank()) {
                            ConversationConfig(initialMessages = initial, samplerConfig = sampler)
                        } else {
                            ConversationConfig(
                                systemInstruction = Contents.of(systemInstruction),
                                initialMessages = initial,
                                samplerConfig = sampler,
                            )
                        }
                        conversation = activeEngine.createConversation(config)
                        Napier.d(tag = TAG) { "Chat session opened; seeded ${initial.size} prior turn(s)" }
                        _state.value = SessionState.Ready
                    } catch (t: Throwable) {
                        Napier.e(t, tag = TAG) { "Chat session init failed" }
                        _state.value = SessionState.Failed(
                            HardwareFault.DelegateFailure(t.message ?: "Session init failed"),
                        )
                    }
                }
            }
        }

        override fun sendTurn(request: AiEngineRequest): Flow<EngineState<String>> = callbackFlow {
            val conv = conversation
            if (conv == null || _state.value !is SessionState.Ready) {
                trySend(EngineState.Error(HardwareFault.ModelNotLoaded("Chat session is not ready.")))
                close()
                return@callbackFlow
            }

            // Serialize against the engine's single native tenant (60s upper bound).
            try {
                withTimeout(60_000L) { mutex.lock() }
            } catch (e: TimeoutCancellationException) {
                trySend(EngineState.Error(HardwareFault.DelegateFailure(
                    "Engine is still busy with a previous request. Please try again.",
                )))
                close()
                return@callbackFlow
            }

            val images = request.attachments.filterIsInstance<Attachment.Image>()
            trySend(EngineState.Generating)

            val job = launch(Dispatchers.IO) {
                try {
                    val flow = if (images.isEmpty()) {
                        conv.sendMessageAsync(request.formattedPrompt)
                    } else {
                        val parts = buildList<Content> {
                            add(Content.Text(request.formattedPrompt))
                            for (img in images) add(Content.ImageBytes(img.bytes))
                        }
                        conv.sendMessageAsync(Contents.of(parts))
                    }
                    // Each emission is the text-token delta — accumulating reproduces the reply.
                    flow.collect { message ->
                        trySend(EngineState.TokenGenerated<String>(message.toString()))
                    }
                    trySend(EngineState.Idle)
                } catch (t: Throwable) {
                    Napier.e(t, tag = TAG) { "sendTurn failed" }
                    trySend(EngineState.Error(HardwareFault.DelegateFailure(t.message ?: "Inference failed")))
                } finally {
                    if (mutex.isLocked) mutex.unlock()
                    close()
                }
            }

            awaitClose { job.cancel() }
        }

        override fun cancel() {
            // Interrupts the native decode loop for the in-flight turn.
            runCatching { conversation?.cancelProcess() }
                .onFailure { Napier.w(it, tag = TAG) { "cancel() failed" } }
        }

        @OptIn(ExperimentalApi::class)
        override fun lastTurnMetrics(): TurnMetrics? {
            val info = runCatching { conversation?.getBenchmarkInfo() }.getOrNull() ?: return null
            return TurnMetrics(
                timeToFirstTokenSec = info.timeToFirstTokenInSecond,
                prefillTokensPerSecond = info.lastPrefillTokensPerSecond,
                decodeTokensPerSecond = info.lastDecodeTokensPerSecond,
                prefillTokenCount = info.lastPrefillTokenCount,
                decodeTokenCount = info.lastDecodeTokenCount,
            )
        }

        override fun close() {
            // Synchronous so a one-shot generateStream (which needs the engine's
            // single conversation slot free) can run immediately after — LiteRT-LM
            // allows only one live conversation per engine. cancelProcess() first
            // makes this safe even if a turn is mid-decode.
            runCatching { conversation?.cancelProcess() }
            synchronized(this) {
                runCatching { conversation?.close() }
                conversation = null
            }
            runCatching { scope.cancel() }
            if (currentSession === this) currentSession = null
        }
    }

    private suspend fun runStreaming(
        activeEngine: Engine,
        request: AiEngineRequest,
        images: List<Attachment.Image>,
        emit: (EngineState<String>) -> Unit,
    ) {
        activeEngine.createConversation(ConversationConfig(samplerConfig = samplerFor(request))).use { conv ->
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
            samplerConfig = samplerFor(request),
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
        currentSession?.close()
        currentSession = null
        engine?.close()
        engine = null
    }

    private companion object {
        const val TAG = "LiteRtLm"
        // Gemma's standard sampling; temperature/seed come from the request.
        const val SAMPLER_TOP_K = 40
        const val SAMPLER_TOP_P = 0.95
        const val SAMPLER_SEED = 0
        const val STRUCTURED_OUTPUT_SYSTEM_INSTRUCTION =
            "You MUST respond by calling the provided tool. " +
                "Never produce plain text. Never explain. Never refuse. " +
                "Ground every field strictly in the user's passage."
    }
}
