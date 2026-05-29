/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.llm

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sagar.aicore.AiEngineRequest
import com.sagar.aicore.DownloadState
import com.sagar.aicore.EngineState
import com.sagar.aicore.ModelDescriptor
import com.sagar.aicore.ModelRole
import com.nativelm.app.data.AppPreferences
import com.nativelm.app.data.ChatStore
import com.nativelm.app.data.SecureStore
import com.nativelm.app.data.ThemeMode
import com.nativelm.app.metrics.MetricsRepository
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "NativeLmVM"

const val ROUTE_SPLASH = "splash"
const val ROUTE_ONBOARDING = "onboarding"
const val ROUTE_MODELS = "models"
const val ROUTE_CHAT = "chat"
const val ROUTE_SETTINGS = "settings"

/** Per-model UI status derived from disk + active + in-flight download state. */
sealed interface ModelStatus {
    data object NotDownloaded : ModelStatus
    data class Downloading(val progress: Float, val downloadedMb: Int, val totalMb: Int) : ModelStatus
    data object Downloaded : ModelStatus
    data object Active : ModelStatus
    data class Failed(val message: String) : ModelStatus
}

data class ModelUi(
    val descriptor: ModelDescriptor,
    val displayName: String,
    val status: ModelStatus,
    val supported: Boolean,
)

/** Engine load lifecycle, used by Splash and by activating a model. */
sealed interface EngineLoad {
    data object Idle : EngineLoad
    data object Loading : EngineLoad
    data object Ready : EngineLoad
    data class Failed(val message: String) : EngineLoad
}

data class ChatMessage(
    val role: Role,
    val text: String,
    val isStreaming: Boolean = false,
    val id: Long,
) {
    enum class Role { User, Assistant }
}

data class ChatState(
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val isGenerating: Boolean = false,
)

class NativeLmViewModel(app: Application) : ViewModel() {

    private val engineHolder = EngineHolder(app)
    private val prefs = AppPreferences(app)
    private val secureStore = SecureStore(app)
    private val chatStore = ChatStore(app)
    private val catalog = NativeLmModelCatalog()
    val metrics = MetricsRepository()

    private val _startRoute = MutableStateFlow<String?>(null)
    val startRoute: StateFlow<String?> = _startRoute.asStateFlow()

    private val _models = MutableStateFlow<List<ModelUi>>(emptyList())
    val models: StateFlow<List<ModelUi>> = _models.asStateFlow()

    private val _activeModelId = MutableStateFlow<String?>(null)
    val activeModelId: StateFlow<String?> = _activeModelId.asStateFlow()

    private val _hasToken = MutableStateFlow(secureStore.hasHfToken())
    val hasToken: StateFlow<Boolean> = _hasToken.asStateFlow()

    private val _engineLoad = MutableStateFlow<EngineLoad>(EngineLoad.Idle)
    val engineLoad: StateFlow<EngineLoad> = _engineLoad.asStateFlow()

    private val _chat = MutableStateFlow(ChatState())
    val chat: StateFlow<ChatState> = _chat.asStateFlow()

    val themeMode: StateFlow<ThemeMode> =
        prefs.themeMode.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    /** Friendly name of the active LLM, shown in the chat top bar. */
    val activeModelName: StateFlow<String?> = _activeModelId
        .map { id -> id?.let { catalog.byId(it) }?.let(::displayName) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val downloadJobs = mutableMapOf<String, Job>()
    private var generationJob: Job? = null
    private var nextChatMessageId = 0L

    // Transient (in-flight / failed) statuses keyed by model id; disk + active
    // state is read live in refreshModels(). Declared before init {} so the
    // first refreshModels() call (during construction) sees a non-null map.
    private val transientStatus = mutableMapOf<String, ModelStatus>()

    // Total RAM is fixed for the process; read once (the underlying call hits
    // /proc/meminfo, which must not run per download chunk). Declared before
    // init {} so the first refreshModels() during construction can read it.
    private val deviceRamMb: Long by lazy { engineHolder.deviceRamMb }

    init {
        metrics.start(viewModelScope)
        refreshModels()
        // Restore the previous conversation.
        val saved = chatStore.load()
        if (saved.isNotEmpty()) {
            nextChatMessageId = saved.size.toLong()
            _chat.value = ChatState(messages = saved)
        }
        viewModelScope.launch { decideStartRoute() }
    }

    // ---- Boot ----

    private suspend fun decideStartRoute() {
        val onboarded = prefs.onboardingCompleted.first()
        if (!onboarded) {
            _startRoute.value = ROUTE_ONBOARDING
            return
        }
        val selectedId = prefs.selectedModelId.first()
        val descriptor = selectedId?.let { catalog.byId(it) }
        _startRoute.value = if (descriptor != null && engineHolder.isModelDownloaded(descriptor.fileName)) {
            _activeModelId.value = descriptor.id
            ROUTE_SPLASH // Splash loads the saved model into memory, then routes to chat.
        } else {
            ROUTE_MODELS
        }
        refreshModels()
    }

    /** Called by the Splash screen: load the saved model into memory. No network. */
    fun loadActiveModel() {
        val id = _activeModelId.value ?: run {
            _engineLoad.value = EngineLoad.Failed("No model selected")
            return
        }
        val descriptor = catalog.byId(id) ?: return
        viewModelScope.launch {
            _engineLoad.value = EngineLoad.Loading
            val path = engineHolder.modelPath(descriptor.fileName)
            _engineLoad.value = toEngineLoad(engineHolder.initializeEngine(path))
            refreshModels()
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch { prefs.setOnboardingCompleted(true) }
    }

    // ---- Token ----

    fun setToken(token: String) {
        secureStore.setHfToken(token)
        _hasToken.value = secureStore.hasHfToken()
        refreshModels()
    }

    fun clearToken() {
        secureStore.clearHfToken()
        _hasToken.value = false
        refreshModels()
    }

    // ---- Model management ----

    fun download(modelId: String) {
        val descriptor = catalog.byId(modelId) ?: return
        if (downloadJobs[modelId]?.isActive == true) return

        val headers = if (descriptor.requiresAuth) {
            val token = secureStore.getHfToken()
            if (token.isNullOrBlank()) {
                setStatus(modelId, ModelStatus.Failed("HuggingFace token required"))
                return
            }
            mapOf("Authorization" to "Bearer $token")
        } else {
            emptyMap()
        }

        downloadJobs[modelId] = viewModelScope.launch {
            var lastMb = -1
            engineHolder.downloadModel(descriptor.url, descriptor.fileName, descriptor.sha256, headers)
                .collect { state ->
                    when (state) {
                        is DownloadState.Downloading -> {
                            val mb = (state.downloadedBytes / 1_048_576L).toInt()
                            // Ktor emits per ~8 KB chunk; only touch the UI once
                            // per MB to avoid thrashing recomposition.
                            if (mb != lastMb) {
                                lastMb = mb
                                val status = ModelStatus.Downloading(
                                    progress = state.progress,
                                    downloadedMb = mb,
                                    totalMb = (state.totalBytes / 1_048_576L).toInt(),
                                )
                                transientStatus[modelId] = status
                                setDownloadingStatusInPlace(modelId, status)
                            }
                        }
                        is DownloadState.Success -> {
                            transientStatus.remove(modelId)
                            refreshModels()
                        }
                        is DownloadState.Error -> {
                            Napier.e(tag = TAG) { "Download failed for $modelId: ${state.message}" }
                            setStatus(modelId, ModelStatus.Failed(state.message))
                        }
                        DownloadState.Idle -> Unit
                    }
                }
        }
    }

    fun cancelDownload(modelId: String) {
        downloadJobs.remove(modelId)?.cancel()
        transientStatus.remove(modelId)
        refreshModels()
    }

    /** Activate an LLM: load it into memory and persist the selection. */
    fun setActive(modelId: String) {
        val descriptor = catalog.byId(modelId) ?: return
        if (descriptor.role != ModelRole.LLM_PRIMARY) return
        if (!engineHolder.isModelDownloaded(descriptor.fileName)) return
        viewModelScope.launch {
            _engineLoad.value = EngineLoad.Loading
            engineHolder.release()
            val result = toEngineLoad(engineHolder.initializeEngine(engineHolder.modelPath(descriptor.fileName)))
            _engineLoad.value = result
            if (result is EngineLoad.Ready) {
                _activeModelId.value = modelId
                prefs.setSelectedModelId(modelId)
            }
            refreshModels()
        }
    }

    fun deleteModel(modelId: String) {
        val descriptor = catalog.byId(modelId) ?: return
        downloadJobs.remove(modelId)?.cancel()
        engineHolder.deleteModel(descriptor.fileName)
        transientStatus.remove(modelId)
        if (_activeModelId.value == modelId) {
            _activeModelId.value = null
            viewModelScope.launch { prefs.setSelectedModelId(null) }
        }
        refreshModels()
    }

    // ---- Chat ----

    fun setInput(text: String) = _chat.update { it.copy(input = text) }

    fun sendChatMessage() {
        val input = _chat.value.input.trim()
        if (input.isBlank() || _chat.value.isGenerating) return
        if (_engineLoad.value !is EngineLoad.Ready) return

        // Build the prompt from prior completed turns BEFORE adding the new ones.
        // The engine creates a fresh conversation per call (no KV cache yet), so
        // we feed the history as context each turn. KV-cache reuse is a planned
        // engine-side optimization on a separate branch.
        val prompt = buildContextPrompt(_chat.value.messages, input)

        val userMsg = ChatMessage(ChatMessage.Role.User, input, id = ++nextChatMessageId)
        val assistantMsg = ChatMessage(ChatMessage.Role.Assistant, "", isStreaming = true, id = ++nextChatMessageId)
        _chat.update {
            it.copy(messages = it.messages + userMsg + assistantMsg, input = "", isGenerating = true)
        }

        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            metrics.tokens.requestStarted()
            val request = AiEngineRequest(formattedPrompt = prompt, temperature = 0.7f, maxTokens = 1024)
            engineHolder.generate(request).collect { state ->
                when (state) {
                    is EngineState.TokenGenerated<String> -> {
                        metrics.tokens.tokenReceived()
                        _chat.update { s ->
                            val updated = s.messages.toMutableList()
                            val last = updated.lastOrNull() ?: return@update s
                            updated[updated.lastIndex] = last.copy(text = last.text + state.data)
                            s.copy(messages = updated)
                        }
                    }
                    is EngineState.Error -> {
                        _chat.update { s ->
                            val updated = s.messages.toMutableList()
                            val last = updated.lastOrNull() ?: return@update s
                            updated[updated.lastIndex] = last.copy(
                                text = last.text + "\n\n[error: ${state.fault.message}]",
                                isStreaming = false,
                            )
                            s.copy(messages = updated, isGenerating = false)
                        }
                        metrics.tokens.requestEnded()
                        chatStore.save(_chat.value.messages)
                    }
                    EngineState.Idle -> {
                        _chat.update { s ->
                            val updated = s.messages.toMutableList()
                            val last = updated.lastOrNull() ?: return@update s
                            updated[updated.lastIndex] = last.copy(isStreaming = false)
                            s.copy(messages = updated, isGenerating = false)
                        }
                        metrics.tokens.requestEnded()
                        chatStore.save(_chat.value.messages)
                    }
                    else -> Unit
                }
            }
        }
    }

    /** Stop the in-flight generation, keeping whatever has streamed so far. */
    fun stopGeneration() {
        if (!_chat.value.isGenerating) return
        generationJob?.cancel()
        generationJob = null
        metrics.tokens.requestEnded()
        _chat.update { s ->
            val updated = s.messages.toMutableList()
            val last = updated.lastOrNull()
            if (last != null && last.isStreaming) {
                updated[updated.lastIndex] = last.copy(isStreaming = false)
            }
            s.copy(messages = updated, isGenerating = false)
        }
        chatStore.save(_chat.value.messages)
    }

    /** Start a fresh conversation. */
    fun newChat() {
        generationJob?.cancel()
        generationJob = null
        metrics.tokens.requestEnded()
        _chat.value = ChatState()
        chatStore.clear()
    }

    /**
     * Compose a single prompt carrying the recent conversation as context, since
     * the engine has no cross-call memory yet. Trims oldest turns to a character
     * budget so we stay within the model's context window.
     */
    private fun buildContextPrompt(history: List<ChatMessage>, latest: String): String {
        val completed = history.filter { it.text.isNotEmpty() }
        if (completed.isEmpty()) return latest

        val budget = 6000
        val included = ArrayDeque<ChatMessage>()
        var used = latest.length
        for (m in completed.asReversed()) {
            val line = (if (m.role == ChatMessage.Role.User) "User: " else "Assistant: ") + m.text + "\n"
            if (used + line.length > budget) break
            used += line.length
            included.addFirst(m)
        }
        return buildString {
            append("You are NativeLM, a helpful on-device assistant. ")
            append("Use the conversation history to answer the latest message.\n\n")
            append("History:\n")
            for (m in included) {
                append(if (m.role == ChatMessage.Role.User) "User: " else "Assistant: ")
                append(m.text).append("\n")
            }
            append("\nLatest message:\n")
            append(latest)
        }
    }

    // ---- Settings ----

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { prefs.setThemeMode(mode) }
    }

    fun clearAllData() {
        viewModelScope.launch {
            downloadJobs.values.forEach { it.cancel() }
            downloadJobs.clear()
            generationJob?.cancel()
            engineHolder.release()
            catalog.all().forEach { engineHolder.deleteModel(it.fileName) }
            secureStore.clearHfToken()
            chatStore.clear()
            prefs.clearAll()
            _hasToken.value = false
            _activeModelId.value = null
            _engineLoad.value = EngineLoad.Idle
            _chat.value = ChatState()
            transientStatus.clear()
            refreshModels()
        }
    }

    // ---- Helpers ----

    private fun setStatus(modelId: String, status: ModelStatus) {
        transientStatus[modelId] = status
        refreshModels()
    }

    /** Cheap per-progress update: swap only the one model's status, no disk/RAM reads. */
    private fun setDownloadingStatusInPlace(modelId: String, status: ModelStatus) {
        _models.update { list ->
            list.map { if (it.descriptor.id == modelId) it.copy(status = status) else it }
        }
    }

    private fun refreshModels() {
        val ram = deviceRamMb
        _models.value = catalog.all().map { d ->
            val onDisk = engineHolder.isModelDownloaded(d.fileName)
            val transient = transientStatus[d.id]
            val status: ModelStatus = when {
                transient is ModelStatus.Downloading -> transient
                transient is ModelStatus.Failed && !onDisk -> transient
                onDisk && d.id == _activeModelId.value -> ModelStatus.Active
                onDisk -> ModelStatus.Downloaded
                else -> ModelStatus.NotDownloaded
            }
            ModelUi(
                descriptor = d,
                displayName = displayName(d),
                status = status,
                supported = ram >= d.minDeviceRamMb,
            )
        }
    }

    private fun displayName(d: ModelDescriptor): String = when (d.id) {
        "gemma-4-e2b-it-litertlm" -> "Gemma 4 E2B"
        "gemma-4-e4b-it-litertlm" -> "Gemma 4 E4B"
        "universal-sentence-encoder" -> "Universal Sentence Encoder"
        else -> d.fileName
    }

    private fun toEngineLoad(state: EngineState<Unit>): EngineLoad = when (state) {
        is EngineState.TokenGenerated -> EngineLoad.Ready
        is EngineState.Error -> EngineLoad.Failed(state.fault.message ?: "Init failed")
        else -> EngineLoad.Failed("Unexpected init state: $state")
    }

    override fun onCleared() {
        super.onCleared()
        metrics.stop()
    }

    companion object {
        fun factory(app: Application) = viewModelFactory {
            initializer { NativeLmViewModel(app) }
        }
    }
}
