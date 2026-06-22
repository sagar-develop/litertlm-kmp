/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.llm

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sagar.aicore.AiEngineRequest
import com.sagar.aicore.chart.ChartInstruction
import com.sagar.aicore.ChatSession
import com.sagar.aicore.ChatTurn
import com.sagar.aicore.DownloadState
import com.sagar.aicore.EngineState
import com.sagar.aicore.ModelDescriptor
import com.sagar.aicore.ModelFormat
import com.sagar.aicore.Language
import com.sagar.aicore.ModelRole
import com.sagar.aicore.SessionState
import com.sagar.aicore.TurnRole
import com.nativelm.app.BuildConfig
import com.nativelm.app.benchmark.BenchConfig
import com.nativelm.app.benchmark.BenchmarkReport
import com.nativelm.app.benchmark.BenchmarkRunner
import com.nativelm.app.benchmark.BenchmarkUiState
import com.nativelm.app.benchmark.toCsv
import com.nativelm.app.benchmark.toJson
import com.nativelm.app.data.AppPreferences
import com.nativelm.app.data.SecureStore
import com.nativelm.app.data.ThemeMode
import com.nativelm.app.data.backup.BackupException
import com.nativelm.app.data.backup.BackupManager
import com.nativelm.app.sync.SyncManager
import com.nativelm.app.sync.SyncPeer
import com.nativelm.app.sync.SyncState
import com.nativelm.app.data.db.ConversationRepository
import com.nativelm.app.data.db.MessageEntity
import com.nativelm.app.data.db.ProjectRepository
import com.nativelm.app.data.db.StudioArtifactEntity
import com.nativelm.app.data.db.StudioRepository
import com.nativelm.app.metrics.MetricsRepository
import com.sagar.aicore.renderAssistantText
import com.sagar.aicore.rag.Citation
import com.sagar.aicore.rag.CitationJson
import com.sagar.aicore.rag.IngestState
import com.sagar.aicore.rag.StoredDocument
import com.nativelm.app.studio.PodcastController
import com.nativelm.app.studio.PodcastPlayState
import com.nativelm.app.studio.ReadAloudState
import com.sagar.aicore.studio.StudioArtifactType
import com.sagar.aicore.studio.StudioGenerator
import com.nativelm.app.studio.TtsController
import com.sagar.aicore.studio.parsePodcast
import com.nativelm.app.voice.AudioRecorder
import com.nativelm.app.voice.WhisperSpeechToText
import com.sagar.aicore.studio.sanitizeStudioMarkdown
import com.sagar.aicore.studio.stripForSpeech
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "NativeLmVM"

/**
 * How many recent conversation turns to re-prefill into the engine's KV cache when a
 * session opens. Bounds the working context so grounded turns (which inject a retrieval
 * block each) have headroom; older turns stay in the transcript but leave the model's
 * window. ~16 turns ≈ 8 exchanges.
 */
private const val MAX_PREFILL_TURNS = 16

private const val BASE_SYSTEM_INSTRUCTION =
    "You are NativeLM, a helpful on-device assistant. Answer clearly and concisely."

const val ROUTE_SPLASH = "splash"
const val ROUTE_ONBOARDING = "onboarding"
const val ROUTE_MODELS = "models"
const val ROUTE_CHAT = "chat"
const val ROUTE_SETTINGS = "settings"
const val ROUTE_DOCUMENTS = "documents"
const val ROUTE_PDF_VIEWER = "pdf_viewer"
const val ROUTE_STUDIO = "studio"
const val ROUTE_BENCHMARK = "benchmark"

/** Grace window before the app re-locks after going to the background (avoids
 *  re-prompting on quick returns like the system file picker). */
private const val LOCK_GRACE_MS = 30_000L

/** State of an in-progress voice-input dictation. */
enum class VoiceState { Idle, Recording, Transcribing }

/** Filename of the on-device Whisper voice model — must match its catalog descriptor. */
const val WHISPER_FILE = "whisper-tiny-q5_1.bin"

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
    /** Document sources grounding this answer (RAG); empty for ordinary chat. */
    val citations: List<Citation> = emptyList(),
) {
    enum class Role { User, Assistant }
}

/**
 * A resolved request to open a cited source at its cited page, with the cited
 * passage to highlight. Held as ViewModel state; the chat screen reacts to it by
 * navigating to the viewer, which reads it back. Covers both PDFs and images
 * ([isImage]); an image is a single "page" shown via the same zoomable viewer.
 */
data class PdfViewTarget(
    val documentId: Long,
    val title: String,
    val localPath: String,
    val pageCount: Int,
    /** 1-indexed cited page; coerced to a valid page by the viewer. */
    val initialPage: Int,
    /** The cited passage, shown in the highlight callout above the page. */
    val highlight: String,
    /** True for image sources (OCR'd): rendered directly, no PdfRenderer. */
    val isImage: Boolean = false,
)

/** A document row for the management screen. */
data class DocumentSummary(
    val id: Long,
    val title: String,
    val pageCount: Int,
    val chunkCount: Int,
    val createdAt: Long,
)

data class ChatState(
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val isGenerating: Boolean = false,
    /** True while the engine re-prefills seeded history ("building understanding"). */
    val isWarming: Boolean = false,
)

/** A conversation row for the drawer list. */
data class ConversationSummary(
    val id: Long,
    val title: String,
    val updatedAt: Long,
)

/** A project (notebook) row for the drawer + save sheet. */
data class ProjectSummary(
    val id: Long,
    val name: String,
    val updatedAt: Long,
)

/** A Studio artifact row for the Studio list. */
data class StudioArtifactSummary(
    val id: Long,
    val type: StudioArtifactType,
    val title: String,
    val scopeLabel: String,
    val createdAt: Long,
)

/** A Studio artifact opened in the viewer. */
data class StudioArtifactView(
    val id: Long,
    val type: StudioArtifactType,
    val title: String,
    val content: String,
    val scopeLabel: String,
    val createdAt: Long,
)

/** Coarse progress of an in-flight Studio generation. */
data class StudioProgress(val phase: String, val current: Int, val total: Int)

/** UI state for the Studio screen of the open project. */
data class StudioState(
    val artifacts: List<StudioArtifactSummary> = emptyList(),
    val generating: Boolean = false,
    val progress: StudioProgress? = null,
    /** Non-null when an artifact is open in the viewer. */
    val open: StudioArtifactView? = null,
    val error: String? = null,
)

class NativeLmViewModel(app: Application) : ViewModel() {

    private val appContext: Application = app
    private val engineHolder = EngineHolder(app)
    private val ragHolder = RagHolder(app, engineHolder)
    private val prefs = AppPreferences(app)
    private val secureStore = SecureStore(app)

    // Voice input (on-device Whisper). The model downloads via the engine model manager.
    private val audioRecorder = AudioRecorder()
    private var speechToText: WhisperSpeechToText? = null
    private val _voiceState = MutableStateFlow(VoiceState.Idle)
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()
    private val backupManager = BackupManager(app)
    private val syncManager = SyncManager(app, backupManager, prefs, viewModelScope)
    private val repo = ConversationRepository()
    private val projectRepo = ProjectRepository()
    private val studioRepo = StudioRepository()
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

    // ---- Benchmark (developer tool; issue #38) ----
    private val benchmarkRunner by lazy {
        // Peak PSS comes from the already-running MetricsRepository (cheap cached read) —
        // never an inline Debug.getMemoryInfo() smaps walk, which would skew decode timing.
        BenchmarkRunner(
            appContext, engineHolder, catalog, ::displayName, deviceRamMb,
            currentPssMb = { metrics.snapshot.value.memory.totalPssMb },
        )
    }
    private val _benchmarkState = MutableStateFlow<BenchmarkUiState>(BenchmarkUiState.Idle)
    val benchmarkState: StateFlow<BenchmarkUiState> = _benchmarkState.asStateFlow()
    private var benchmarkJob: Job? = null
    private var lastBenchmarkReport: BenchmarkReport? = null

    private val _chat = MutableStateFlow(ChatState())
    val chat: StateFlow<ChatState> = _chat.asStateFlow()

    private val _conversations = MutableStateFlow<List<ConversationSummary>>(emptyList())
    val conversations: StateFlow<List<ConversationSummary>> = _conversations.asStateFlow()

    /** Id of the open conversation; 0 = an unsaved new chat (created on first send). */
    private val _currentConversationId = MutableStateFlow(0L)
    val currentConversationId: StateFlow<Long> = _currentConversationId.asStateFlow()

    // ---- Projects (notebooks) + sources ----

    private val _projects = MutableStateFlow<List<ProjectSummary>>(emptyList())
    val projects: StateFlow<List<ProjectSummary>> = _projects.asStateFlow()

    /** Open project; 0 = the default general chat (no grounding). */
    private val _currentProjectId = MutableStateFlow(0L)
    val currentProjectId: StateFlow<Long> = _currentProjectId.asStateFlow()

    /** Name of the open project, shown in the chat top bar; null in default chat. */
    private val _currentProjectName = MutableStateFlow<String?>(null)
    val currentProjectName: StateFlow<String?> = _currentProjectName.asStateFlow()

    /** Sources of the open project, for its sources screen. */
    private val _documents = MutableStateFlow<List<DocumentSummary>>(emptyList())
    val documents: StateFlow<List<DocumentSummary>> = _documents.asStateFlow()

    /** Progress of an in-flight source import; null when idle. */
    private val _importState = MutableStateFlow<IngestState?>(null)
    val importState: StateFlow<IngestState?> = _importState.asStateFlow()

    private var importJob: Job? = null

    /** Set when a citation is tapped and its PDF can be opened; the viewer reads it. */
    private val _pdfViewTarget = MutableStateFlow<PdfViewTarget?>(null)
    val pdfViewTarget: StateFlow<PdfViewTarget?> = _pdfViewTarget.asStateFlow()

    /** One-shot user message (e.g. "source not available"); cleared after shown. */
    private val _transientMessage = MutableStateFlow<String?>(null)
    val transientMessage: StateFlow<String?> = _transientMessage.asStateFlow()

    /** Non-null progress label while a backup export/import runs; null when idle. */
    private val _backupBusy = MutableStateFlow<String?>(null)
    val backupBusy: StateFlow<String?> = _backupBusy.asStateFlow()

    /** Non-null progress label while embeddings are re-indexed for a new embedder; null when idle. */
    private val _embeddingMigration = MutableStateFlow<String?>(null)
    val embeddingMigration: StateFlow<String?> = _embeddingMigration.asStateFlow()

    val themeMode: StateFlow<ThemeMode> =
        prefs.themeMode.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    /** Whether the user has enabled biometric / device-credential app lock. */
    val appLockEnabled: StateFlow<Boolean> =
        prefs.appLockEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** The language the model answers in (prompt-only; works cross-lingual over English docs). */
    val outputLanguage: StateFlow<Language> =
        prefs.outputLanguage.stateIn(viewModelScope, SharingStarted.Eagerly, Language.DEFAULT)

    /** True while the app is locked and the [com.nativelm.app.ui.lock.LockScreen] should cover content. */
    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    /** When the app last entered the background, for the lock grace window. */
    private var lastBackgroundedAt = 0L

    /** Friendly name of the active LLM, shown in the chat top bar. */
    val activeModelName: StateFlow<String?> = _activeModelId
        .map { id -> id?.let { catalog.byId(it) }?.let(::displayName) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * The best "no account needed" model for this device: the largest **ungated**
     * (Apache-2.0 / MIT) LLM whose RAM floor the device clears. Surfaced as the
     * "Recommended" pick in Model Management so a fresh install — including a Play
     * Store user with no Hugging Face account — can get a working model with a
     * single tap: no token, no license to accept. Null only on devices below even
     * the smallest model's RAM floor. Device RAM is fixed for the process, so this
     * is computed once on first read.
     */
    val recommendedModelId: String? by lazy {
        catalog.byRole(ModelRole.LLM_PRIMARY)
            .filter { !it.requiresAuth && deviceRamMb >= it.minDeviceRamMb }
            .maxByOrNull { it.minDeviceRamMb }
            ?.id
    }

    /** One-tap convenience: download the device's recommended ungated model. No-op if none fits. */
    fun downloadRecommended() {
        recommendedModelId?.let { download(it) }
    }

    /** Device-tiered RAG embedder recommendation (USE-Lite / EmbeddingGemma @dim + reranker). */
    val recommendedEmbedderTier: EmbedderTier by lazy { EmbedderRecommendation.forDevice(deviceRamMb) }

    /** Catalogue id of the recommended embedder, for the "Recommended" badge in Models. */
    val recommendedEmbedderId: String get() = recommendedEmbedderTier.embedderId

    /** Catalogue id of the recommended reranker (flagship only), or null. */
    val recommendedRerankerId: String?
        get() = if (recommendedEmbedderTier.reranker) EmbedderRecommendation.RERANKER_ID else null

    /**
     * Resolve which embedder to actually use right now and (re)configure the RAG
     * pipeline for it. Preference order: the user's explicit selection, else the
     * device recommendation — but only if its model is fully downloaded; otherwise
     * fall back to USE-Lite so document chat always works. Returns the chosen
     * descriptor. Cheap + idempotent (configureEmbedder no-ops when unchanged).
     */
    private suspend fun resolveAndConfigureEmbedder(): ModelDescriptor {
        val rec = recommendedEmbedderTier
        val selId = prefs.selectedEmbedderId.first()
        val selDim = prefs.embeddingDim.first()
        val candidateId = selId ?: rec.embedderId
        val candidateDim = if (selId != null && selDim > 0) selDim else rec.dim

        val candidate = catalog.byId(candidateId)
        val use = catalog.byId(RagHolder.USE_MODEL_ID)!!
        val (chosen, dim) = if (candidate != null && engineHolder.isModelFullyDownloaded(candidate)) {
            candidate to candidateDim
        } else {
            use to EmbedderRecommendation.DIM_USE
        }

        // Attach the reranker on reranker-eligible tiers, and only when its model is present.
        val rerankerDesc = if (rec.reranker && chosen.id == EmbedderRecommendation.GEMMA_ID) {
            catalog.byId(EmbedderRecommendation.RERANKER_ID)?.takeIf { engineHolder.isModelFullyDownloaded(it) }
        } else {
            null
        }

        ragHolder.configureEmbedder(chosen, dim, rerankerDesc)
        _activeEmbedderId.value = chosen.id
        return chosen
    }

    private val _activeEmbedderId = MutableStateFlow<String?>(null)
    /** The embedder actually in use for RAG (after download/recommendation resolution). */
    val activeEmbedderId: StateFlow<String?> = _activeEmbedderId.asStateFlow()

    /** Explicitly choose the RAG embedder (USE-Lite or EmbeddingGemma). Persists + reconfigures. */
    fun setActiveEmbedder(modelId: String) {
        val desc = catalog.byId(modelId) ?: return
        val dim = when (desc.format) {
            ModelFormat.ONNX_EMBEDDER -> if (recommendedEmbedderTier.dim >= EmbedderRecommendation.DIM_MID) {
                recommendedEmbedderTier.dim
            } else {
                EmbedderRecommendation.DIM_MID
            }
            else -> EmbedderRecommendation.DIM_USE
        }
        viewModelScope.launch {
            prefs.setActiveEmbedder(modelId, dim)
            resolveAndConfigureEmbedder()
            ragHolder.ensureEmbeddingReady()
            refreshModels()
        }
    }

    /** Resolve + configure + initialize the active embedder. */
    private suspend fun embedderReady(): Boolean {
        resolveAndConfigureEmbedder()
        return ragHolder.ensureEmbeddingReady()
    }

    /** Re-index a project into the active embedder's dim if it has only legacy-dim chunks. */
    private suspend fun maybeMigrateProject(projectId: Long) {
        if (projectId <= 0L || !ragHolder.needsMigration(projectId)) return
        _embeddingMigration.value = "Upgrading search index…"
        runCatching {
            ragHolder.migrateProject(projectId) { done, total ->
                _embeddingMigration.value = "Upgrading search index… $done/$total"
            }
        }.onFailure { Napier.e(tag = TAG, throwable = it) { "Embedding migration failed" } }
        _embeddingMigration.value = null
        refreshDocuments()
    }

    private val downloadJobs = mutableMapOf<String, Job>()
    private var generationJob: Job? = null
    private var studioJob: Job? = null
    private var nextChatMessageId = 0L

    /** Studio (artifact generation) state for the open project. */
    private val _studio = MutableStateFlow(StudioState())
    val studio: StateFlow<StudioState> = _studio.asStateFlow()

    /** On-device read-aloud (TTS) for the open artifact; null = nothing playing. */
    private val tts = TtsController(app) {
        _transientMessage.value = "Text-to-speech isn't available on this device."
    }
    val readAloud: StateFlow<ReadAloudState?> = tts.state

    /** On-device two-voice podcast playback for the open artifact; null = nothing playing. */
    private val podcastTts = PodcastController(app) {
        _transientMessage.value = "Text-to-speech isn't available on this device."
    }
    val podcast: StateFlow<PodcastPlayState?> = podcastTts.state

    /** The live KV-cache chat session for the open conversation. */
    private var chatSession: ChatSession? = null
    private var sessionWarmJob: Job? = null

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
        // Start every launch on a fresh chat (Gemini-style). Past conversations
        // stay in the drawer and can be reopened; we don't auto-restore one.
        refreshConversations()
        refreshProjects()
        viewModelScope.launch { decideStartRoute() }
        // After a sync receive imports a bundle, refresh the drawer + projects so the
        // restored chats/notebooks appear without a restart. Reference syncManager.state
        // directly — the `syncState` property is declared later in the class and isn't
        // assigned yet when this init block runs.
        viewModelScope.launch {
            syncManager.state.collect { state ->
                if (state is SyncState.ReceiveComplete) {
                    refreshConversations()
                    refreshProjects()
                }
            }
        }
    }

    private fun refreshConversations() {
        _conversations.value = repo.list(0L).map { ConversationSummary(it.id, it.title, it.updatedAt) }
    }

    // ---- Boot ----

    private suspend fun decideStartRoute() {
        val onboarded = prefs.onboardingCompleted.first()
        if (!onboarded) {
            _startRoute.value = ROUTE_ONBOARDING
            return
        }
        // Apply the app lock before any content is shown. The splash is held until
        // startRoute resolves (below), so setting this first guarantees the lock
        // screen covers the app from the very first frame.
        if (prefs.appLockEnabled.first()) _locked.value = true
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
            val load = toEngineLoad(engineHolder.initializeEngine(path, descriptor.supportsVision))
            _engineLoad.value = load
            // Open the chat session seeded with the restored conversation so the
            // KV cache is warm before the user types (the "building understanding" step).
            if (load is EngineLoad.Ready) openSession(_chat.value.messages)
            refreshModels()
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch { prefs.setOnboardingCompleted(true) }
    }

    // ---- Benchmark (developer tool; issue #38) ----

    /**
     * Run the on-device benchmark matrix over the given downloaded LLMs. Takes over the
     * engine (releasing + reloading each model), so chat is unavailable until it finishes
     * — we restore the user's active model in `finally`. Cancel via [cancelBenchmark].
     */
    fun runBenchmark(modelIds: List<String>, repeats: Int, maxTokens: Int) {
        if (benchmarkJob?.isActive == true || modelIds.isEmpty()) return
        benchmarkJob = viewModelScope.launch(Dispatchers.Default) {
            _benchmarkState.value = BenchmarkUiState.Running("Starting…", 0, modelIds.size, 0f)
            _engineLoad.value = EngineLoad.Loading // chat is unavailable while the benchmark owns the engine
            try {
                val report = benchmarkRunner.run(
                    modelIds = modelIds,
                    config = BenchConfig(
                        warmups = 1,
                        repeats = repeats,
                        maxTokens = maxTokens,
                        temperature = 0f,
                        seed = 12345L,
                    ),
                    appVersionName = BuildConfig.VERSION_NAME,
                    appVersionCode = BuildConfig.VERSION_CODE,
                ) { label, idx, count, tps ->
                    _benchmarkState.value = BenchmarkUiState.Running(label, idx, count, tps)
                }
                lastBenchmarkReport = report
                _benchmarkState.value = BenchmarkUiState.Done(report)
            } catch (_: CancellationException) {
                _benchmarkState.value = BenchmarkUiState.Idle
            } catch (e: Throwable) {
                Napier.e(tag = TAG, throwable = e) { "Benchmark failed" }
                _benchmarkState.value = BenchmarkUiState.Failed(e.message ?: "Benchmark failed")
            } finally {
                // Reload the user's model even if cancelled, so chat works again afterward.
                withContext(NonCancellable) { restoreActiveModelAfterBenchmark() }
            }
        }
    }

    fun cancelBenchmark() {
        benchmarkJob?.cancel()
    }

    fun dismissBenchmarkResult() {
        if (benchmarkJob?.isActive == true) return
        _benchmarkState.value = BenchmarkUiState.Idle
    }

    /** Write the last report to a SAF document as JSON. No-op if nothing has run. */
    fun exportBenchmarkJson(uri: Uri) = writeBenchmark(uri) { it.toJson() }

    /** Write the last report to a SAF document as CSV (one row per model × prompt). */
    fun exportBenchmarkCsv(uri: Uri) = writeBenchmark(uri) { it.toCsv() }

    private fun writeBenchmark(uri: Uri, render: (BenchmarkReport) -> String) {
        val report = lastBenchmarkReport ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                appContext.contentResolver.openOutputStream(uri)?.use {
                    it.write(render(report).toByteArray())
                }
            }.onFailure { Napier.e(tag = TAG, throwable = it) { "Benchmark export failed" } }
        }
    }

    /** Share the last report's JSON via the system share sheet. */
    fun shareBenchmarkJson() {
        val report = lastBenchmarkReport ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_TEXT, report.toJson())
        }
        runCatching {
            appContext.startActivity(
                Intent.createChooser(send, "Share benchmark results")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Napier.e(tag = TAG, throwable = it) { "Benchmark share failed" } }
    }

    private suspend fun restoreActiveModelAfterBenchmark() {
        val descriptor = _activeModelId.value?.let { catalog.byId(it) }
        if (descriptor == null) {
            _engineLoad.value = EngineLoad.Idle
            return
        }
        engineHolder.release()
        val load = toEngineLoad(
            engineHolder.initializeEngine(engineHolder.modelPath(descriptor.fileName), descriptor.supportsVision),
        )
        _engineLoad.value = load
        if (load is EngineLoad.Ready) openSession(_chat.value.messages)
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
            engineHolder.downloadModel(descriptor, headers)
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
            val result = toEngineLoad(
                engineHolder.initializeEngine(engineHolder.modelPath(descriptor.fileName), descriptor.supportsVision),
            )
            _engineLoad.value = result
            if (result is EngineLoad.Ready) {
                _activeModelId.value = modelId
                prefs.setSelectedModelId(modelId)
                openSession(_chat.value.messages)
            }
            refreshModels()
        }
    }

    fun deleteModel(modelId: String) {
        val descriptor = catalog.byId(modelId) ?: return
        downloadJobs.remove(modelId)?.cancel()
        engineHolder.deleteModel(descriptor.fileName)
        descriptor.companions.forEach { engineHolder.deleteModel(it.fileName) }
        transientStatus.remove(modelId)
        if (_activeModelId.value == modelId) {
            _activeModelId.value = null
            viewModelScope.launch { prefs.setSelectedModelId(null) }
        }
        refreshModels()
    }

    // ---- Chat ----

    fun setInput(text: String) = _chat.update { it.copy(input = text) }

    // ---- Voice input (on-device Whisper speech-to-text) ----

    /** True once the Whisper voice model has been downloaded. */
    fun isVoiceModelReady(): Boolean = engineHolder.isModelDownloaded(WHISPER_FILE)

    /**
     * Tap the mic: if the Whisper model isn't downloaded yet, fetch it (with progress);
     * otherwise start recording. Caller must hold RECORD_AUDIO.
     */
    fun startVoiceRecording() {
        if (_voiceState.value != VoiceState.Idle) return
        if (!engineHolder.isModelDownloaded(WHISPER_FILE)) {
            _transientMessage.value = "Download the voice model in Settings → Models first."
            return
        }
        runCatching { audioRecorder.start(viewModelScope) }
            .onSuccess { _voiceState.value = VoiceState.Recording }
            .onFailure { _transientMessage.value = "Couldn't start the microphone." }
    }

    fun cancelVoiceRecording() {
        audioRecorder.cancel()
        _voiceState.value = VoiceState.Idle
    }

    /** Stop recording, transcribe on-device, and put the text in the input field. */
    fun stopVoiceAndTranscribe() {
        if (_voiceState.value != VoiceState.Recording) return
        _voiceState.value = VoiceState.Transcribing
        viewModelScope.launch {
            try {
                val pcm = audioRecorder.stop()
                if (pcm.isEmpty()) return@launch
                val stt = speechToText
                    ?: WhisperSpeechToText.fromModelFile(engineHolder.modelPath(WHISPER_FILE)).also { speechToText = it }
                // Force Whisper to the selected answer language (the language chip): the
                // tiny model's auto-detect is weak for non-English (it romanizes Hindi into
                // gibberish), so honoring the user's choice gives correct native-script
                // transcription. English speakers keep "en".
                val text = stt.transcribe(pcm, languageCode = outputLanguage.value.code)
                if (text.isNotBlank()) {
                    val existing = _chat.value.input
                    setInput(if (existing.isBlank()) text else "$existing $text")
                } else {
                    _transientMessage.value = "Didn't catch that — try again."
                }
            } catch (e: Throwable) {
                android.util.Log.e(TAG, "Voice transcription failed", e)
                _transientMessage.value = "Voice transcription failed."
            } finally {
                _voiceState.value = VoiceState.Idle
            }
        }
    }

    fun sendChatMessage() {
        val input = _chat.value.input.trim()
        if (input.isBlank() || _chat.value.isGenerating || _chat.value.isWarming) return
        if (_engineLoad.value !is EngineLoad.Ready) return
        val session = chatSession ?: return

        // Lazily create the conversation row on the first message (so a blank
        // "New chat" doesn't clutter the drawer until it has content).
        if (_currentConversationId.value == 0L) {
            val now = System.currentTimeMillis()
            _currentConversationId.value = repo.create(
                projectId = _currentProjectId.value,
                title = snippetTitle(input),
                now = now,
            )
            refreshConversations()
        }

        val userMsg = ChatMessage(ChatMessage.Role.User, input, id = ++nextChatMessageId)
        val assistantMsg = ChatMessage(ChatMessage.Role.Assistant, "", isStreaming = true, id = ++nextChatMessageId)
        _chat.update {
            it.copy(messages = it.messages + userMsg + assistantMsg, input = "", isGenerating = true)
        }

        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            metrics.tokens.requestStarted()
            // RAG: when grounding is on, retrieve context, fold it into this turn's
            // prompt, and surface citations under the answer. Otherwise send only the
            // new message so the KV cache keeps TTFT flat.
            //
            // Grounded turns inject a fresh retrieval block every turn; left in the
            // stateful KV cache these accumulate and eventually overflow the on-device
            // model's context window, truncating the reply to a token or two. So for a
            // grounded turn we re-open the session first (re-prefilling only the bounded
            // visible transcript), which flushes prior turns' stale grounding. Ordinary
            // chat keeps the warm session for flat TTFT.
            val active = if (_currentProjectId.value > 0L) {
                reopenSessionAndAwait(_chat.value.messages.dropLast(2))
                chatSession ?: return@launch
            } else {
                session
            }
            val turnPrompt = withLanguage(ragTurnPrompt(input))
            val request = AiEngineRequest(formattedPrompt = turnPrompt, temperature = 0.7f, maxTokens = 1024)
            // Accumulate the raw stream separately so we can hide reasoning models'
            // <think>…</think> span; the message text shows only the rendered answer.
            val rawAnswer = StringBuilder()
            active.sendTurn(request).collect { state ->
                when (state) {
                    is EngineState.TokenGenerated<String> -> {
                        metrics.tokens.tokenReceived()
                        rawAnswer.append(state.data)
                        val display = renderAssistantText(rawAnswer.toString())
                        _chat.update { s ->
                            val updated = s.messages.toMutableList()
                            val last = updated.lastOrNull() ?: return@update s
                            updated[updated.lastIndex] = last.copy(text = display)
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
                        persistCurrent()
                    }
                    EngineState.Idle -> {
                        _chat.update { s ->
                            val updated = s.messages.toMutableList()
                            val last = updated.lastOrNull() ?: return@update s
                            updated[updated.lastIndex] = last.copy(isStreaming = false)
                            s.copy(messages = updated, isGenerating = false)
                        }
                        metrics.tokens.requestEnded()
                        persistCurrent()
                        maybeGenerateTitle()
                    }
                    else -> Unit
                }
            }
        }
    }

    /** Stop the in-flight generation, keeping whatever has streamed so far. */
    fun stopGeneration() {
        if (!_chat.value.isGenerating) return
        chatSession?.cancel() // interrupt the native decode loop, not just the collector
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
        persistCurrent()
    }

    /** Start a fresh conversation: clear the thread and open an empty session.
     *  The conversation row is created lazily on the first message. */
    fun newChat() {
        generationJob?.cancel()
        generationJob = null
        resetStudioState()
        metrics.tokens.requestEnded()
        _currentProjectId.value = 0L
        _currentProjectName.value = null
        _currentConversationId.value = 0L
        _chat.value = ChatState()
        if (_engineLoad.value is EngineLoad.Ready) openSession(emptyList())
    }

    /** Open an existing conversation from the drawer: load its messages and warm
     *  a session seeded with them (the "building understanding" step). */
    fun openConversation(id: Long) {
        if (id == _currentConversationId.value && !_chat.value.isGenerating) return
        generationJob?.cancel()
        generationJob = null
        resetStudioState()
        metrics.tokens.requestEnded()
        _currentProjectId.value = 0L
        _currentProjectName.value = null
        val msgs = repo.messages(id).mapIndexed { i, e -> e.toChatMessage(i.toLong()) }
        _currentConversationId.value = id
        nextChatMessageId = msgs.size.toLong()
        _chat.value = ChatState(messages = msgs)
        if (_engineLoad.value is EngineLoad.Ready) openSession(msgs)
    }

    fun renameConversation(id: Long, title: String) {
        val clean = title.trim().ifBlank { return }
        repo.rename(id, clean)
        refreshConversations()
    }

    fun deleteConversation(id: Long) {
        repo.delete(id)
        if (id == _currentConversationId.value) newChat()
        refreshConversations()
    }

    /** Persist the open conversation's messages and bump its last-activity time. */
    private fun persistCurrent() {
        val id = _currentConversationId.value
        if (id == 0L) return
        val entities = _chat.value.messages
            .filter { it.text.isNotEmpty() }
            .mapIndexed { i, m -> m.toEntity(i.toLong()) }
        repo.saveMessages(id, entities)
        repo.touch(id, System.currentTimeMillis())
        refreshConversations()
    }

    /** After the first full exchange, replace the snippet title with a model-
     *  generated one (one-shot, stateless — doesn't touch the chat KV cache). */
    private fun maybeGenerateTitle() {
        val id = _currentConversationId.value
        if (id == 0L) return
        if (_currentProjectId.value != 0L) return // project chats keep the project name
        val completed = _chat.value.messages.filter { it.text.isNotEmpty() }
        if (completed.size != 2) return // only after the first user+assistant pair
        val firstUser = completed.firstOrNull { it.role == ChatMessage.Role.User }?.text ?: return
        val firstReply = completed.firstOrNull { it.role == ChatMessage.Role.Assistant }?.text ?: return
        val snapshot = _chat.value.messages
        viewModelScope.launch {
            val prompt = "Summarize this conversation as a short title of 3 to 5 words. " +
                "Reply with ONLY the title, no quotes, no punctuation at the end.\n\n" +
                "User: $firstUser\nAssistant: ${firstReply.take(400)}"
            // LiteRT-LM allows one conversation per engine, so free the chat
            // session, run the one-shot title, then silently reopen the session
            // (seeded, warming hidden) so the next turn keeps its KV cache.
            chatSession?.close()
            chatSession = null
            val sb = StringBuilder()
            runCatching {
                engineHolder.generate(
                    AiEngineRequest(formattedPrompt = withLanguage(prompt), temperature = 0.3f, maxTokens = 24),
                ).collect { st -> if (st is EngineState.TokenGenerated<String>) sb.append(st.data) }
            }
            val title = sb.toString()
                .lineSequence()
                .map { it.trim().trim('"', '\'', '*', '#').trim().removeSuffix(".") }
                .firstOrNull { it.isNotBlank() }
                ?.take(60)
            openSession(snapshot, showWarming = false)
            if (!title.isNullOrBlank() && id == _currentConversationId.value) {
                repo.rename(id, title)
                refreshConversations()
            }
        }
    }

    /**
     * The system instruction for the active model. The chart-emitting fragment is
     * appended ONLY for models flagged [ModelDescriptor.supportsCharts]; tiny models
     * (e.g. Qwen3 0.6B) parrot the chart examples into unrelated answers, so they
     * chat with the plain instruction instead.
     */
    private fun systemInstruction(): String {
        val supportsCharts = _activeModelId.value?.let { catalog.byId(it) }?.supportsCharts == true
        return if (supportsCharts) "$BASE_SYSTEM_INSTRUCTION\n\n${ChartInstruction.SYSTEM}"
        else BASE_SYSTEM_INSTRUCTION
    }

    /**
     * Open a stateful chat session, seeding it with [history] (re-prefilled once,
     * surfaced as [ChatState.isWarming]). Closes any prior session. The KV cache
     * is then reused across turns, so we no longer re-send history each message.
     */
    private fun openSession(history: List<ChatMessage>, showWarming: Boolean = true) {
        sessionWarmJob?.cancel()
        // Re-prefill only the most recent turns. The KV cache is bounded, and in a
        // grounded project each live turn also injects a retrieval block; re-seeding an
        // unbounded history would leave too little room and the model would truncate its
        // reply to a token or two. Keeping a sliding window preserves recent continuity
        // while leaving headroom for grounding. Older turns drop out of the model's
        // working memory but remain visible in the transcript.
        val turns = history.filter { it.text.isNotEmpty() }
            .takeLast(MAX_PREFILL_TURNS)
            .map {
                ChatTurn(
                    role = if (it.role == ChatMessage.Role.User) TurnRole.USER else TurnRole.ASSISTANT,
                    text = it.text,
                )
            }
        val session = engineHolder.openChatSession(turns, systemInstruction())
        chatSession = session
        if (!showWarming) {
            _chat.update { it.copy(isWarming = false) }
            return
        }
        sessionWarmJob = viewModelScope.launch {
            session.state.collect { st ->
                _chat.update { it.copy(isWarming = st is SessionState.Warming) }
            }
        }
    }

    /**
     * Re-open the chat session seeded with [history] and suspend until it is Ready (or
     * Failed). Used before a grounded turn to FLUSH the grounding blocks that prior turns
     * left in the KV cache: re-prefilling only the visible transcript (capped by
     * [MAX_PREFILL_TURNS]) keeps the working context bounded, so a long grounded chat
     * doesn't overflow the on-device model's window and truncate its reply. Visible
     * history is preserved; only the now-stale retrieval blocks drop out.
     */
    private suspend fun reopenSessionAndAwait(history: List<ChatMessage>) {
        openSession(history, showWarming = false)
        val session = chatSession ?: return
        session.state.first { it is SessionState.Ready || it is SessionState.Failed }
    }

    /** Build one turn's prompt, grounding in the open project's sources (if any). */
    private suspend fun ragTurnPrompt(input: String): String {
        val projectId = _currentProjectId.value
        if (projectId <= 0L || !embedderReady()) return input
        val ctx = ragHolder.retrieve(projectId, input)
        if (ctx.isEmpty) return input
        attachCitations(ctx.citations)
        return buildString {
            append(ctx.contextText).append("\n\n")
            append("User question: ").append(input).append('\n')
            append("Answer using only the context above. If it doesn't contain the answer, say so plainly.")
        }
    }

    /** Tag the in-flight assistant message with its grounding sources. */
    private fun attachCitations(citations: List<Citation>) {
        if (citations.isEmpty()) return
        _chat.update { s ->
            val updated = s.messages.toMutableList()
            val last = updated.lastOrNull() ?: return@update s
            updated[updated.lastIndex] = last.copy(citations = citations)
            s.copy(messages = updated)
        }
    }

    /**
     * Resolve a tapped [Citation] to its source file (PDF or image) and, if the
     * file is still on disk, publish a [PdfViewTarget] for the viewer to pick up.
     * Sources without a retained file (text bubbles, or files imported before
     * local copies existed) surface a one-shot message instead.
     */
    fun openCitation(citation: Citation, onOpen: () -> Unit) {
        viewModelScope.launch {
            val doc = ragHolder.document(citation.documentId)
            val path = doc?.localPath.orEmpty()
            val onDisk = path.isNotBlank() && File(path).exists()
            val isPdf = doc?.mimeType == "application/pdf"
            val isImage = doc?.mimeType?.startsWith("image/") == true
            if (doc != null && onDisk && (isPdf || isImage)) {
                _pdfViewTarget.value = PdfViewTarget(
                    documentId = doc.id,
                    title = doc.title,
                    localPath = path,
                    pageCount = doc.pageCount,
                    initialPage = citation.pageNumber,
                    highlight = citation.snippet,
                    isImage = isImage,
                )
                onOpen()
            } else {
                _transientMessage.value = "Original file isn't available for this source."
            }
        }
    }

    fun clearPdfViewTarget() {
        _pdfViewTarget.value = null
    }

    fun consumeTransientMessage() {
        _transientMessage.value = null
    }

    private fun snippetTitle(input: String): String =
        input.trim().replace('\n', ' ').take(40).ifBlank { "New chat" }

    private fun MessageEntity.toChatMessage(msgId: Long): ChatMessage = ChatMessage(
        role = if (role == MessageEntity.ROLE_ASSISTANT) ChatMessage.Role.Assistant else ChatMessage.Role.User,
        text = text,
        id = msgId,
        citations = CitationJson.decode(citationsJson),
    )

    private fun ChatMessage.toEntity(order: Long): MessageEntity = MessageEntity().apply {
        role = if (this@toEntity.role == ChatMessage.Role.Assistant) {
            MessageEntity.ROLE_ASSISTANT
        } else {
            MessageEntity.ROLE_USER
        }
        text = this@toEntity.text
        createdAt = order
        citationsJson = CitationJson.encode(this@toEntity.citations)
    }

    // ---- Projects (notebooks) ----

    fun refreshProjects() {
        _projects.value = projectRepo.list().map { ProjectSummary(it.id, it.name, it.updatedAt) }
    }

    /** Create a notebook and return its id (used by "New project" and the save sheet). */
    fun createProject(name: String): Long {
        val clean = name.trim().ifBlank { "Untitled project" }
        val id = projectRepo.create(clean, System.currentTimeMillis())
        refreshProjects()
        return id
    }

    fun renameProject(id: Long, name: String) {
        val clean = name.trim().ifBlank { return }
        projectRepo.rename(id, clean)
        if (id == _currentProjectId.value) _currentProjectName.value = clean
        refreshProjects()
    }

    fun deleteProject(id: Long) {
        viewModelScope.launch {
            repo.deleteForProject(id)
            ragHolder.deleteDocumentsOfProject(id)
            studioRepo.deleteForProject(id)
            projectRepo.delete(id)
            if (id == _currentProjectId.value) newChat()
            refreshProjects()
        }
    }

    /** Open a notebook's grounded chat (one per project; created on first open). */
    fun openProject(id: Long) {
        val project = projectRepo.get(id) ?: return
        generationJob?.cancel()
        generationJob = null
        resetStudioState()
        metrics.tokens.requestEnded()
        val convId = repo.firstForProject(id)?.id
            ?: repo.create(id, project.name, System.currentTimeMillis())
        _currentProjectId.value = id
        _currentProjectName.value = project.name
        val msgs = repo.messages(convId).mapIndexed { i, e -> e.toChatMessage(i.toLong()) }
        _currentConversationId.value = convId
        nextChatMessageId = msgs.size.toLong()
        _chat.value = ChatState(messages = msgs)
        refreshDocuments()
        viewModelScope.launch {
            embedderReady() // warm embedder
            maybeMigrateProject(id) // re-index into the active embedder's dim if needed
        }
        if (_engineLoad.value is EngineLoad.Ready) openSession(msgs)
    }

    // ---- Sources (per project) ----

    fun refreshDocuments() {
        val projectId = _currentProjectId.value
        viewModelScope.launch {
            _documents.value = if (projectId <= 0L) {
                emptyList()
            } else {
                ragHolder.documents(projectId).map {
                    DocumentSummary(it.id, it.title, it.pageCount, it.chunkCount, it.createdAt)
                }
            }
        }
    }

    /** Import a picked file as a source of the open project. */
    fun importDocument(uri: String, displayName: String?) {
        val projectId = _currentProjectId.value
        if (projectId <= 0L || importJob?.isActive == true) return
        importJob = launchIngest { ragHolder.ingestFile(projectId, uri, displayName) }
    }

    /** Save a chat bubble's text as a source of [projectId] (from the save sheet). */
    fun saveBubbleToProject(projectId: Long, text: String) {
        if (projectId <= 0L || text.isBlank() || importJob?.isActive == true) return
        importJob = launchIngest(refreshFor = projectId) {
            ragHolder.ingestText(projectId, snippetTitle(text), text)
        }
    }

    /** Save a bubble into the currently-open project (project chat). */
    fun saveBubbleToCurrentProject(text: String) = saveBubbleToProject(_currentProjectId.value, text)

    /** Run an ingest flow, surfacing [IngestState] and refreshing sources when it lands in the open project. */
    private fun launchIngest(
        refreshFor: Long = _currentProjectId.value,
        flow: suspend () -> kotlinx.coroutines.flow.Flow<IngestState>,
    ): Job = viewModelScope.launch {
        _importState.value = IngestState.Extracting
        if (!prepareEmbedding()) {
            _importState.value = IngestState.Failed("Couldn't prepare the embedding model.")
            return@launch
        }
        flow().collect { state ->
            _importState.value = state
            if (state is IngestState.Done && refreshFor == _currentProjectId.value) refreshDocuments()
        }
    }

    fun clearImportState() {
        _importState.value = null
    }

    fun deleteDocument(id: Long) {
        viewModelScope.launch {
            ragHolder.deleteDocument(id)
            refreshDocuments()
        }
    }

    /** Make the embedder usable: download the small USE model if needed, then init. */
    private suspend fun prepareEmbedding(): Boolean {
        val desc = resolveAndConfigureEmbedder()
        if (ragHolder.ensureEmbeddingReady()) return true
        // Only USE-Lite auto-downloads (tiny, ungated). Gated EmbeddingGemma is
        // user-downloaded from Models; resolve already fell back to USE-Lite here
        // if the recommended Gemma wasn't present.
        if (desc.id == RagHolder.USE_MODEL_ID) {
            if (!downloadEmbeddingModel()) return false
            return ragHolder.ensureEmbeddingReady()
        }
        return false
    }

    private suspend fun downloadEmbeddingModel(): Boolean {
        val descriptor = catalog.byId(RagHolder.USE_MODEL_ID) ?: return false
        if (engineHolder.isModelDownloaded(descriptor.fileName)) return true
        var success = false
        engineHolder.downloadModel(descriptor.url, descriptor.fileName, descriptor.sha256)
            .collect { state ->
                when (state) {
                    is DownloadState.Success -> success = true
                    is DownloadState.Error -> {
                        Napier.e(tag = TAG) { "USE model download failed: ${state.message}" }
                        success = false
                    }
                    else -> Unit
                }
            }
        if (success) refreshModels()
        return success
    }

    // ---- Settings ----

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { prefs.setThemeMode(mode) }
    }

    fun setOutputLanguage(language: Language) {
        viewModelScope.launch { prefs.setOutputLanguage(language) }
    }

    /**
     * Append the output-language directive to a generation prompt. No-op for English (the
     * model's default), so the common path stays clean; for other languages this is the
     * one-line, prompt-only mechanism that makes the answer come out in [outputLanguage]
     * (with strict-script emphasis for Kannada/Punjabi).
     */
    private fun withLanguage(prompt: String): String {
        val lang = outputLanguage.value
        if (lang == Language.ENGLISH) return prompt
        return "$prompt\n\nOutput language: ${lang.outputDirective}."
    }

    // ---- Local encrypted backup (export / import) ----

    /** Export the whole knowledge base to [uri] as an encrypted `.nlmbak`. Clears [passphrase]. */
    fun exportBackup(uri: Uri, passphrase: CharArray) {
        viewModelScope.launch {
            _backupBusy.value = "Backing up…"
            try {
                appContext.contentResolver.openOutputStream(uri)?.use { out ->
                    backupManager.export(out, passphrase, BuildConfig.VERSION_NAME, System.currentTimeMillis())
                } ?: throw BackupException("Couldn't open the destination file.")
                _transientMessage.value = "Backup saved."
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Backup export failed", e)
                _transientMessage.value = backupError(e)
            } finally {
                passphrase.fill(' ')
                _backupBusy.value = null
            }
        }
    }

    /** Restore (additively) from the `.nlmbak` at [uri]. Clears [passphrase]. */
    fun importBackup(uri: Uri, passphrase: CharArray) {
        viewModelScope.launch {
            _backupBusy.value = "Restoring…"
            try {
                val result = appContext.contentResolver.openInputStream(uri)?.use { input ->
                    backupManager.import(input, passphrase, prefs)
                } ?: throw BackupException("Couldn't open the backup file.")
                refreshConversations()
                refreshProjects()
                _transientMessage.value =
                    "Restored ${result.projects} project(s) and ${result.documents} source(s)."
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Backup import failed", e)
                _transientMessage.value = backupError(e)
            } finally {
                passphrase.fill(' ')
                _backupBusy.value = null
            }
        }
    }

    private fun backupError(e: Exception): String =
        (e as? BackupException)?.message ?: "Backup failed. Please try again."

    // ---- Local peer-to-peer sync (NSD/mDNS + socket; no cloud) ----

    val syncState: StateFlow<SyncState> = syncManager.state

    /** Begin advertising this device and serving an encrypted bundle to a peer that connects. */
    fun startSyncSend() = syncManager.startSend(BuildConfig.VERSION_NAME)

    /** Begin discovering nearby devices that are sending. */
    fun startSyncReceive() = syncManager.startReceive()

    /** Connect to a discovered sender and pull its bundle. */
    fun connectSyncPeer(peer: SyncPeer) = syncManager.connectTo(peer)

    /** Decrypt + import the received bundle using the code shown on the sender. */
    fun importSyncReceived(code: CharArray) = syncManager.importReceived(code)

    /** Stop any in-flight sync and reset. */
    fun cancelSync() = syncManager.cancel()

    // ---- App lock ----

    fun setAppLockEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setAppLockEnabled(enabled) }
    }

    /** Called by the lock screen once biometric / device-credential auth succeeds. */
    fun onUnlocked() {
        _locked.value = false
    }

    /** App moved to the background: stamp the time so we can re-lock after a grace window. */
    fun onEnterBackground() {
        lastBackgroundedAt = System.currentTimeMillis()
    }

    /**
     * App returned to the foreground. Re-lock only if lock is enabled and the app was
     * backgrounded for longer than [LOCK_GRACE_MS] — the grace window avoids re-prompting
     * on quick returns (e.g. the system file picker during import/export).
     */
    fun onEnterForeground() {
        if (appLockEnabled.value &&
            lastBackgroundedAt > 0L &&
            System.currentTimeMillis() - lastBackgroundedAt > LOCK_GRACE_MS
        ) {
            _locked.value = true
        }
    }

    // ---- Studio (artifact generation from a project's sources) ----

    /** Open/refresh the Studio screen for the current project: reload its artifacts. */
    fun openStudio() {
        _studio.update { it.copy(error = null) }
        refreshArtifacts()
    }

    private fun refreshArtifacts() {
        val projectId = _currentProjectId.value
        viewModelScope.launch {
            val list = if (projectId <= 0L) {
                emptyList()
            } else {
                studioRepo.list(projectId).map {
                    StudioArtifactSummary(
                        id = it.id,
                        type = StudioArtifactType.fromName(it.type),
                        title = it.title,
                        scopeLabel = it.scopeLabel,
                        createdAt = it.createdAt,
                    )
                }
            }
            _studio.update { it.copy(artifacts = list) }
        }
    }

    /**
     * Generate a Studio artifact of [type] from the open project's sources via
     * map-reduce. [sourceId] 0 = whole project, else a single source. Frees the chat
     * session for the duration (LiteRT-LM allows one conversation per engine), then
     * reopens it.
     */
    fun generate(type: StudioArtifactType, sourceId: Long = 0L) {
        val projectId = _currentProjectId.value
        if (projectId <= 0L || _studio.value.generating) return
        if (_engineLoad.value !is EngineLoad.Ready) {
            _studio.update { it.copy(error = "Load a model first to use Studio.") }
            return
        }
        val startConvId = _currentConversationId.value
        tts.stop()
        podcastTts.stop()
        studioJob?.cancel()
        studioJob = viewModelScope.launch {
            _studio.update { it.copy(generating = true, progress = StudioProgress("Preparing", 0, 0), error = null) }
            chatSession?.close()
            chatSession = null
            try {
                val sources = buildStudioSources(projectId, sourceId)
                check(sources.isNotEmpty()) { "This project has no readable sources yet." }
                val scopeLabel = studioScopeLabel(sourceId)
                val generator = StudioGenerator(llm = { prompt, maxTokens -> studioOneShot(prompt, maxTokens) })
                val onProgress: (StudioGenerator.Progress) -> Unit = { p ->
                    _studio.update { it.copy(progress = StudioProgress(p.phase, p.current, p.total)) }
                }
                val content = when (type) {
                    StudioArtifactType.BRIEFING -> generator.briefing(sources, scopeLabel, onProgress)
                    StudioArtifactType.FAQ -> generator.faq(sources, scopeLabel, onProgress)
                    StudioArtifactType.KEY_TOPICS -> generator.keyTopics(sources, scopeLabel, onProgress)
                    StudioArtifactType.STUDY_GUIDE -> generator.studyGuide(sources, scopeLabel, onProgress)
                    StudioArtifactType.TIMELINE -> generator.timeline(sources, scopeLabel, onProgress)
                    StudioArtifactType.MIND_MAP -> generator.mindMap(sources, scopeLabel, onProgress)
                    StudioArtifactType.AUDIO_OVERVIEW -> generator.audioOverview(sources, scopeLabel, onProgress)
                    StudioArtifactType.PODCAST -> generator.podcast(sources, scopeLabel, onProgress)
                }
                val now = System.currentTimeMillis()
                val title = studioTitleFrom(content, type, scopeLabel)
                val id = studioRepo.put(
                    StudioArtifactEntity().apply {
                        this.projectId = projectId
                        this.type = type.name
                        this.title = title
                        this.content = content
                        this.sourceId = sourceId
                        this.scopeLabel = scopeLabel
                        createdAt = now
                        updatedAt = now
                    },
                )
                refreshArtifacts()
                _studio.update {
                    it.copy(
                        generating = false,
                        progress = null,
                        open = StudioArtifactView(id, type, title, content, scopeLabel, now),
                    )
                }
            } catch (c: CancellationException) {
                _studio.update { it.copy(generating = false, progress = null) }
                throw c
            } catch (e: Exception) {
                Napier.e("Studio briefing failed", e, tag = TAG)
                _studio.update { it.copy(generating = false, progress = null, error = e.message ?: "Generation failed.") }
            } finally {
                // Reopen the chat session — but only if we're still on the same
                // conversation (the user may have switched projects mid-generation).
                if (_currentConversationId.value == startConvId && _engineLoad.value is EngineLoad.Ready) {
                    openSession(_chat.value.messages, showWarming = false)
                }
            }
        }
    }

    /** Cancel an in-flight Studio generation (the chat session is reopened by the job's finally). */
    fun cancelStudio() {
        studioJob?.cancel()
        studioJob = null
    }

    /** Drop Studio state when the open conversation/project changes. */
    private fun resetStudioState() {
        studioJob?.cancel()
        studioJob = null
        _studio.value = StudioState()
    }

    fun openArtifact(id: Long) {
        viewModelScope.launch {
            val a = studioRepo.get(id) ?: return@launch
            _studio.update {
                it.copy(
                    open = StudioArtifactView(
                        a.id, StudioArtifactType.fromName(a.type), a.title, a.content, a.scopeLabel, a.createdAt,
                    ),
                )
            }
        }
    }

    fun closeArtifact() {
        tts.stop()
        podcastTts.stop()
        _studio.update { it.copy(open = null) }
    }

    /** Play/pause read-aloud of the open artifact via on-device TTS (Studio 7a). */
    fun toggleReadAloud(artifact: StudioArtifactView) {
        podcastTts.stop()
        tts.toggle(artifact.id, stripForSpeech(artifact.content))
    }

    /** Play/pause two-voice podcast playback of the open artifact (Studio 7b). */
    fun togglePodcast(artifact: StudioArtifactView) {
        tts.stop()
        podcastTts.toggle(artifact.id, parsePodcast(artifact.content))
    }

    /**
     * Seed the open project's grounded chat with [question] and send it — the
     * "ask about this topic" hook from a Key Topics artifact. Closes the Studio
     * viewer; the caller navigates back to the chat to watch the answer stream.
     */
    fun askInChat(question: String) {
        if (question.isBlank()) return
        closeArtifact()
        setInput(question)
        sendChatMessage()
    }

    fun clearStudioError() = _studio.update { it.copy(error = null) }

    fun deleteArtifact(id: Long) {
        viewModelScope.launch {
            studioRepo.delete(id)
            _studio.update {
                if (it.open?.id == id) {
                    tts.stop()
                    podcastTts.stop()
                    it.copy(open = null)
                } else {
                    it
                }
            }
            refreshArtifacts()
        }
    }

    /** Regenerate an artifact with the same type + scope (produces a fresh artifact). */
    fun regenerateArtifact(id: Long) {
        viewModelScope.launch {
            val a = studioRepo.get(id) ?: return@launch
            generate(StudioArtifactType.fromName(a.type), a.sourceId)
        }
    }

    /** Build the per-source chunk text for map-reduce, grouped by document, in reading order. */
    private suspend fun buildStudioSources(projectId: Long, sourceId: Long): List<StudioGenerator.Source> {
        val titles = ragHolder.documents(projectId).associate { it.id to it.title }
        return ragHolder.chunksForProject(projectId, sourceId)
            .groupBy { it.documentId }
            .map { (docId, list) ->
                StudioGenerator.Source(
                    title = titles[docId] ?: "Source",
                    chunks = list.map { it.text },
                )
            }
            .filter { src -> src.chunks.any { it.isNotBlank() } }
    }

    private suspend fun studioScopeLabel(sourceId: Long): String =
        if (sourceId <= 0L) "Whole project" else ragHolder.document(sourceId)?.title ?: "Source"

    /** Prefer the artifact's own H1 title; fall back to "<Type> · <scope>". */
    private fun studioTitleFrom(content: String, type: StudioArtifactType, scopeLabel: String): String {
        val h1 = content.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("# ") }
            ?.removePrefix("# ")?.trim()
        return (h1?.takeIf { it.isNotBlank() } ?: "${type.label} · $scopeLabel").take(80)
    }

    /** One-shot, stateless generation for Studio map-reduce; strips <think> spans + LaTeX. */
    private suspend fun studioOneShot(prompt: String, maxTokens: Int): String {
        val sb = StringBuilder()
        engineHolder.generate(
            AiEngineRequest(formattedPrompt = prompt, temperature = 0.3f, maxTokens = maxTokens),
        ).collect { st -> if (st is EngineState.TokenGenerated<String>) sb.append(st.data) }
        return sanitizeStudioMarkdown(renderAssistantText(sb.toString()).trim())
    }

    fun clearAllData() {
        viewModelScope.launch {
            downloadJobs.values.forEach { it.cancel() }
            downloadJobs.clear()
            generationJob?.cancel()
            sessionWarmJob?.cancel()
            chatSession = null
            importJob?.cancel()
            engineHolder.release()
            catalog.all().forEach { d ->
                engineHolder.deleteModel(d.fileName)
                d.companions.forEach { engineHolder.deleteModel(it.fileName) }
            }
            secureStore.clearHfToken()
            _conversations.value.forEach { repo.delete(it.id) }
            studioJob?.cancel()
            _projects.value.forEach { p ->
                repo.deleteForProject(p.id)
                ragHolder.deleteDocumentsOfProject(p.id)
                studioRepo.deleteForProject(p.id)
                projectRepo.delete(p.id)
            }
            _studio.value = StudioState()
            ragHolder.deleteAllSourceFiles()
            _pdfViewTarget.value = null
            _currentProjectId.value = 0L
            _currentProjectName.value = null
            _importState.value = null
            _currentConversationId.value = 0L
            refreshConversations()
            refreshProjects()
            refreshDocuments()
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
            // Multi-file models (ONNX: graph + weights + tokenizer) only count as
            // present once every companion is on disk, not just the primary file.
            val onDisk = engineHolder.isModelFullyDownloaded(d)
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
        "qwen3-0_6b-litertlm" -> "Qwen3 0.6B"
        "gemma3-1b-it-int4-litertlm" -> "Gemma 3 1B (INT4)"
        "deepseek-r1-distill-qwen-1_5b-litertlm" -> "DeepSeek-R1 Distill 1.5B"
        "gemma-4-e2b-it-litertlm" -> "Gemma 4 E2B"
        "gemma-4-e4b-it-litertlm" -> "Gemma 4 E4B"
        "phi-4-mini-instruct-litertlm" -> "Phi-4 mini"
        "qwen3-4b-litertlm" -> "Qwen3 4B"
        "universal-sentence-encoder" -> "Universal Sentence Encoder"
        "whisper-tiny-q5_1" -> "Whisper Tiny (voice input)"
        "embeddinggemma-300m-onnx" -> "EmbeddingGemma 300M"
        "ms-marco-minilm-l6-onnx" -> "MiniLM Reranker"
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
        tts.shutdown()
        podcastTts.shutdown()
    }

    companion object {
        fun factory(app: Application) = viewModelFactory {
            initializer { NativeLmViewModel(app) }
        }
    }
}
