/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.litertlmsample.llm

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sagar.aicore.AiEngineRequest
import com.sagar.aicore.DownloadState
import com.sagar.aicore.EngineState
import com.sagar.aicore.ToolParameter
import com.sagar.aicore.ToolParameterType
import com.sagar.aicore.ToolSchema
import com.sagar.litertlmsample.BuildConfig
import com.sagar.litertlmsample.metrics.MetricsRepository
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "SampleVM"

sealed class SetupState {
    data object Idle : SetupState()
    data class Downloading(val progress: Float, val downloadedMb: Int, val totalMb: Int) : SetupState()
    data object Initializing : SetupState()
    data object Ready : SetupState()
    data class Failed(val message: String) : SetupState()
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

data class FunctionCallState(
    val prompt: String = DEFAULT_FUNCTION_CALL_PROMPT,
    val schemaPreview: String = DEFAULT_SCHEMA_PREVIEW,
    val extractedJson: String = "",
    val isGenerating: Boolean = false,
    val error: String? = null,
)

class SampleViewModel(app: Application) : ViewModel() {

    private val engineHolder = EngineHolder(app)
    val metrics = MetricsRepository()

    private val _setup = MutableStateFlow<SetupState>(SetupState.Idle)
    val setup: StateFlow<SetupState> = _setup.asStateFlow()

    private val _chat = MutableStateFlow(ChatState())
    val chat: StateFlow<ChatState> = _chat.asStateFlow()

    private val _functionCall = MutableStateFlow(FunctionCallState())
    val functionCall: StateFlow<FunctionCallState> = _functionCall.asStateFlow()

    val descriptorDisplayName: String get() = engineHolder.descriptor.displayName

    private var currentGeneration: Job? = null
    private var nextChatMessageId: Long = 0L
    private fun newChatMessageId(): Long = ++nextChatMessageId

    init {
        metrics.start(viewModelScope)
        // If model is already on disk from a previous run, jump straight to
        // engine init.
        if (engineHolder.isModelDownloaded(BuildConfig.MODEL_FILE_NAME)) {
            initEngineFromDisk()
        }
    }

    fun setInput(text: String) {
        _chat.update { it.copy(input = text) }
    }

    fun startDownloadAndInit() {
        if (_setup.value is SetupState.Downloading || _setup.value is SetupState.Initializing) return
        viewModelScope.launch {
            engineHolder.downloadModel(BuildConfig.MODEL_URL, BuildConfig.MODEL_FILE_NAME)
                .collect { state ->
                    when (state) {
                        is DownloadState.Downloading -> {
                            _setup.value = SetupState.Downloading(
                                progress = state.progress,
                                downloadedMb = (state.downloadedBytes / 1_048_576L).toInt(),
                                totalMb = (state.totalBytes / 1_048_576L).toInt(),
                            )
                        }
                        is DownloadState.Success -> {
                            initEngineFromDisk()
                        }
                        is DownloadState.Error -> {
                            Napier.e(tag = TAG) { "Download failed: ${state.message}" }
                            _setup.value = SetupState.Failed(state.message)
                        }
                        DownloadState.Idle -> Unit
                    }
                }
        }
    }

    private fun initEngineFromDisk() {
        viewModelScope.launch {
            _setup.value = SetupState.Initializing
            val result = engineHolder.initializeEngine(
                engineHolder.modelPath(BuildConfig.MODEL_FILE_NAME)
            )
            _setup.value = when (result) {
                is EngineState.TokenGenerated -> SetupState.Ready
                is EngineState.Error -> SetupState.Failed(result.fault.message ?: "Init failed")
                else -> SetupState.Failed("Unexpected init state: $result")
            }
        }
    }

    fun sendChatMessage() {
        val input = _chat.value.input.trim()
        if (input.isBlank() || _chat.value.isGenerating) return

        val userMsg = ChatMessage(ChatMessage.Role.User, input, id = newChatMessageId())
        val assistantMsg = ChatMessage(
            ChatMessage.Role.Assistant, "", isStreaming = true,
            id = newChatMessageId(),
        )
        _chat.update {
            it.copy(
                messages = it.messages + userMsg + assistantMsg,
                input = "",
                isGenerating = true,
            )
        }

        currentGeneration?.cancel()
        currentGeneration = viewModelScope.launch {
            metrics.tokens.requestStarted()
            val request = AiEngineRequest(
                formattedPrompt = input,
                temperature = 0.7f,
                maxTokens = 1024,
            )
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
                    }
                    EngineState.Idle -> {
                        _chat.update { s ->
                            val updated = s.messages.toMutableList()
                            val last = updated.lastOrNull() ?: return@update s
                            updated[updated.lastIndex] = last.copy(isStreaming = false)
                            s.copy(messages = updated, isGenerating = false)
                        }
                        metrics.tokens.requestEnded()
                    }
                    else -> Unit
                }
            }
        }
    }

    fun runFunctionCall() {
        if (_functionCall.value.isGenerating) return
        val prompt = _functionCall.value.prompt
        _functionCall.update {
            it.copy(extractedJson = "", isGenerating = true, error = null)
        }

        currentGeneration?.cancel()
        currentGeneration = viewModelScope.launch {
            metrics.tokens.requestStarted()
            val schema = ToolSchema.Definition(
                name = "extract_event_details",
                description = "Extract structured event details from a sentence.",
                parameters = listOf(
                    ToolParameter("title", ToolParameterType.StringT, "Event title.", required = true),
                    ToolParameter("duration_minutes", ToolParameterType.IntegerT, "Length in minutes.", required = true),
                ),
            )
            val request = AiEngineRequest(
                formattedPrompt = prompt,
                requireStructuredOutput = true,
                toolSchema = schema,
            )
            engineHolder.generate(request).collect { state ->
                when (state) {
                    is EngineState.ToolCallEmitted -> {
                        metrics.tokens.tokenReceived()
                        _functionCall.update {
                            it.copy(
                                extractedJson = state.arguments.entries.joinToString(
                                    prefix = "{\n  ",
                                    postfix = "\n}",
                                    separator = ",\n  ",
                                ) { "\"${it.key}\": ${formatValue(it.value)}" },
                            )
                        }
                    }
                    is EngineState.Error -> {
                        _functionCall.update {
                            it.copy(error = state.fault.message, isGenerating = false)
                        }
                        metrics.tokens.requestEnded()
                    }
                    EngineState.Idle -> {
                        _functionCall.update { it.copy(isGenerating = false) }
                        metrics.tokens.requestEnded()
                    }
                    else -> Unit
                }
            }
        }
    }

    fun setFunctionCallPrompt(p: String) {
        _functionCall.update { it.copy(prompt = p) }
    }

    private fun formatValue(v: Any?): String = when (v) {
        null -> "null"
        is String -> "\"$v\""
        is Number, is Boolean -> v.toString()
        is List<*> -> v.joinToString(prefix = "[", postfix = "]") { formatValue(it) }
        else -> "\"$v\""
    }

    override fun onCleared() {
        super.onCleared()
        metrics.stop()
    }

    companion object {
        fun factory(app: Application) = viewModelFactory {
            initializer { SampleViewModel(app) }
        }
    }
}

private const val DEFAULT_FUNCTION_CALL_PROMPT =
    "Schedule a 30-minute kickoff for Project Apollo on Tuesday."

private const val DEFAULT_SCHEMA_PREVIEW = """{
  "name": "extract_event_details",
  "parameters": {
    "title": "string",
    "duration_minutes": "integer"
  }
}"""
