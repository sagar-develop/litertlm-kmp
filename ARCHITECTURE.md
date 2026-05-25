# Architecture

This document explains how the modules in `litertlm-kmp` fit together, the design decisions behind the separation, and the platform-specific gotchas the library handles for you.

## Module layout

```
src/
├── commonMain/    ← shared engine state machine, contracts, ModelManager, ToolSchemaConverter
├── androidMain/   ← LiteRT-LM JNI bindings, MediaPipe Text Embedder, OEM-aware HardwareProvider
├── iosMain/       ← iOS PlatformFolders stub (full engine actuals land in v0.2)
└── commonTest/    ← unit tests for SHA-256 streaming + ToolSchemaConverter shape
```

## The four core abstractions

### 1. `LocalAiEngine` — generation interface

```kotlin
interface LocalAiEngine {
    val descriptor: EngineDescriptor
    suspend fun initializeEngine(modelPath: String): EngineState<Unit>
    fun generateStream(request: AiEngineRequest): Flow<EngineState<String>>
    fun formatPrompt(userQuery: String, retrievedContext: String, systemInstruction: String?): String
    fun releaseResources()
}
```

The engine yields a hot `Flow<EngineState>` so callers can stream tokens, observe lifecycle, and react to faults without blocking. `EngineState` is a sealed hierarchy: `Idle`, `Generating`, `TokenGenerated`, `ToolCallEmitted`, `Error`. The structured-output path (`requireStructuredOutput = true`) emits `ToolCallEmitted` instead of streaming text; the free-text path emits one `TokenGenerated` per delta.

The Android `LiteRtLmLocalAiEngine` implementation:
- Serializes all native calls behind a mutex (LiteRT-LM is not thread-safe)
- Lazily initializes the runtime on first generation request
- Holds the mutex across the LiteRT-LM async callback to prevent stream interleaving when multiple coroutines race

### 2. `EmbeddingEngine` — vector encoder

A thin wrapper over MediaPipe's `TextEmbedder` Tasks API. Returns `FloatArray` for each input string. Use it to compute query/document vectors for in-memory cosine similarity in a RAG pipeline. The dimension depends on the bundled embedder model (e.g. 512 for Universal Sentence Encoder, 768 for EmbeddingGemma).

### 3. `EngineRegistry` — RAM-tier-aware selection

Multiple `LocalAiEngine` implementations can coexist in the registry. At init time, the registry consults `HardwareProvider.effectiveRamMb()` and selects the right one:

- 6–9 GB devices → Gemma 4 E2B (smaller, ~2.5 GB on disk, ~3 GB RAM headroom)
- 10+ GB devices → Gemma 4 E4B (larger, ~3.7 GB on disk, more capacity for long contexts)
- Under 6 GB → no engine returned; consumer should surface `DeviceNotSupported` to the user

This avoids the failure mode where you try to load a model that physically won't fit and the OS kills your app.

### 4. `ModelManager` — resumable download + integrity check

Ktor-backed download with:
- Resume support (uses HTTP `Range` headers if the partial file exists)
- Optional SHA-256 validation post-download (lowercase hex, mismatch deletes the file and emits `DownloadState.Error`)
- Atomic temp → final move so a half-downloaded file is never visible to the engine
- `Flow<DownloadState>` for progress UI

## The OEM RAM-expansion gotcha (this is the load-bearing piece)

Realme, Xiaomi, OPPO, vivo, and some Samsung variants ship a "virtual RAM" feature that swaps to flash storage:

- **Realme Dynamic RAM Expansion** (RDRAM / DRE)
- **Xiaomi Memory Extension**
- **OPPO RAM Expansion**

When enabled, these features inflate `MemoryInfo.totalMem` as reported by `ActivityManager.getMemoryInfo()`. A device with 8 GB of physical RAM may report **14 GB** (8 physical + 6 swap). If you size your model tier off `totalMem`, you'll happily load the 4-GB Gemma 4 E4B variant on a device that physically can't hold it, get killed by the LMKD, and look broken.

`AndroidHardwareProvider` detects this by:

1. Reading `MemTotal` from `/proc/meminfo` (the actual physical RAM that the kernel sees)
2. Reading `SwapTotal` from the same file
3. If `SwapTotal > 1 GB`, treating the device as RAM-expansion-enabled and capping the effective RAM at **9 GB** — keeping it in the E2B tier even if `MemTotal` says otherwise

The 1 GB swap threshold filters out normal Linux swap (typically a few hundred MB) from OEM-induced swap (always 4 GB+).

This is the most important practical lesson in this library. If you're rolling your own on-device LLM stack and read nothing else, read [`AndroidHardwareProvider.kt`](src/androidMain/kotlin/com/sagar/aicore/AndroidHardwareProvider.kt).

## Function-calling: `ToolSchemaConverter`

LiteRT-LM consumes function-calling tool definitions as OpenAPI 3.0 JSON. Hand-writing that JSON is error-prone, so the library exposes an engine-agnostic `ToolSchema.Definition` and converts internally:

```kotlin
val def = ToolSchema.Definition(
    name = "extract_event_details",
    description = "Extract structured event details.",
    parameters = listOf(
        ToolParameter("title", ToolParameterType.StringT, "Event title.", required = true),
        ToolParameter("attendees", ToolParameterType.ArrayT(ToolParameterType.StringT), "Names.", required = true),
        ToolParameter("duration_minutes", ToolParameterType.IntegerT, "Length.", required = true),
    ),
)
val json: String = def.toOpenApiJson()
```

The structured-output path on `LocalAiEngine`:

1. Converts your `Definition` to OpenAPI JSON via `toOpenApiJson()`
2. Wraps as an `OpenApiTool` with `automaticToolCalling = false`
3. Sends the prompt with `systemInstruction = "you MUST call the tool"`
4. Reflectively reads `message.toolCalls` from the LiteRT-LM response
5. Emits one `EngineState.ToolCallEmitted(name, arguments)` per call

Tool arguments come back as `Map<String, Any?>`. LiteRT-LM converts camelCase Kotlin parameter names to snake_case schema keys; integer parameters may surface as `Double` (JSON number ambiguity). Coerce accordingly: `arguments["duration_minutes"]?.let { (it as Number).toInt() }`.

## Coroutines + thread discipline

- All native LiteRT-LM calls are serialized behind a `Mutex` inside the engine
- The mutex is held across LiteRT-LM's async callback so concurrent `generateStream` calls don't interleave their tokens
- `ModelManager` uses `Dispatchers.IO` internally for file I/O
- All public suspend functions are safe to call from `Dispatchers.Main`

## DI

The library exposes its surface through a kotlin-inject component (`AiEngineComponent`). Consumers using kotlin-inject can extend the component; consumers using Hilt / Koin / manual wiring can ignore the component and instantiate the implementations directly — every implementation has a no-arg or simple-arg constructor.

`@AppScope` marks long-lived per-app singletons (the engine, embedding engine, hardware provider, etc.). If you use kotlin-inject, scope your provider graph at app start; if you use a different DI framework, treat these as application-scoped singletons.

## Testing

The shipped unit tests cover:

- `Sha256Test` — streaming SHA-256 against canonical empty-input + Wikipedia "abc" reference vectors
- `ToolSchemaConverterTest` — OpenAPI 3.0 shape correctness, primitive type mapping, nested arrays, required-list filtering

Engine-level integration tests (download → load → generate → release) require a connected device + real model weights, and live in the consumer's own test suite. A future `sample-app` module will include a representative integration test.
