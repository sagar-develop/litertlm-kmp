# litertlm-kmp

**A Kotlin Multiplatform wrapper around Google's [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) for running Gemma-family models on-device.**

Dual-licensed: **AGPL-3.0** for open-source / research use; **commercial license** available for proprietary distribution — see [`COMMERCIAL.md`](COMMERCIAL.md).

---

## Why this exists

Shipping a production on-device LLM on Android is significantly harder than the LiteRT-LM samples make it look. You need:

- A clean abstraction over the LiteRT-LM Java SDK so your app code stays platform-independent
- A model-management layer that handles 2GB+ artifact downloads with resume + SHA-256 validation
- Hardware-tier logic that picks the right Gemma variant for the device (and refuses gracefully on under-spec hardware)
- Awareness of OEM quirks — **Realme Dynamic RAM Expansion, Xiaomi Memory Extension, OPPO** all inflate `MemTotal` and silently push under-spec devices into the wrong tier
- A function-calling layer that converts your typed Kotlin schema into the OpenAPI JSON LiteRT-LM expects
- All of the above shaped to run identically on Android and iOS so you can share code across both apps

This library solves all six.

## Platform support

| Platform | Core engine | Hardware acceleration | Status |
|---|---|---|---|
| **Android** (API 26+) | Production | GPU / NPU via LiteRT `CompiledModel` | Production-vetted on flagship + mid-tier devices |
| **iOS** (arm64 + Apple Silicon sim) | Architecture-ready | Planned: Metal GPU acceleration via LiteRT-LM Swift APIs | Roadmap — v0.2 |

The common module (`commonMain`) carries the engine state machine, model-catalog typing, Ktor-backed download manager, and function-calling schema conversion. iOS-side native bindings ship in v0.2 using LiteRT-LM's [recently-released](https://ai.google.dev/edge/litert) Swift APIs.

## Quickstart

### Gradle

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
        mavenCentral()
        google()
    }
}

// build.gradle.kts (Android or KMP module)
dependencies {
    implementation("com.github.sagar-develop:litertlm-kmp:v0.1.0")
}
```

### Streaming chat (Android)

```kotlin
val engine: LocalAiEngine = appComponent.localAiEngine

engine.initializeEngine(modelPath = "/data/data/your.app/files/gemma-4-E2B-it.litertlm")

engine.generateStream(
    AiEngineRequest(
        formattedPrompt = "Explain how RoPE positional encodings work.",
        temperature = 0.7f,
        maxTokens = 1024,
    )
).collect { state ->
    when (state) {
        is EngineState.TokenGenerated -> print(state.data)
        is EngineState.Error -> error(state.fault.message ?: "Engine fault")
        else -> Unit
    }
}
```

### Function calling (structured output)

```kotlin
val toolSchema = ToolSchema.Definition(
    name = "extract_event_details",
    description = "Extract structured event details from a sentence.",
    parameters = listOf(
        ToolParameter("title", ToolParameterType.StringT, "Event title.", required = true),
        ToolParameter("duration_minutes", ToolParameterType.IntegerT, "Length in minutes.", required = true),
    ),
)

engine.generateStream(
    AiEngineRequest(
        formattedPrompt = "Schedule a 30-minute kickoff for Project Apollo on Tuesday.",
        requireStructuredOutput = true,
        toolSchema = toolSchema,
    )
).collect { state ->
    if (state is EngineState.ToolCallEmitted) {
        println("Extracted: ${state.arguments}")
        // → {title=Project Apollo kickoff, duration_minutes=30}
    }
}
```

### Embedding for RAG

```kotlin
val embeddings: EmbeddingEngine = appComponent.embeddingEngine
val vector: FloatArray = embeddings.embed("Your document chunk here")
// → 512-dim float vector ready for cosine similarity against an in-memory store
```

### Model download with progress + SHA-256

```kotlin
val manager: ModelManager = appComponent.modelManager
manager.downloadModel(
    url = "https://your-cdn/gemma-4-E2B-it.litertlm",
    modelName = "gemma-4-E2B-it.litertlm",
    expectedSha256 = "...",  // optional, fails atomically on mismatch
).collect { state ->
    when (state) {
        is DownloadState.Downloading -> updateProgressBar(state.progress)
        is DownloadState.Success -> launchEngine(state.localPath)
        is DownloadState.Error -> showError(state.message)
        else -> Unit
    }
}
```

## Hosting your model weights

This library does **not** ship binary model weights. Gemma's license permits redistribution but each consumer is expected to either bundle the model in their APK or serve it from their own CDN (S3 / Cloudflare R2 / GCS / Firebase Storage / etc.).

The bundled [`InMemoryModelCatalog`](src/commonMain/kotlin/com/sagar/aicore/InMemoryModelCatalog.kt) is a sample with placeholder URLs — replace before shipping, or implement your own [`ModelCatalog`](src/commonMain/kotlin/com/sagar/aicore/ModelCatalog.kt) backed by remote config.

## Architecture at a glance

```
            ┌─────────────────────────────────────────┐
            │     Your app (Compose / SwiftUI / …)    │
            └─────────────────────────────────────────┘
                              │
            ┌─────────────────▼─────────────────────────┐
            │  EngineRegistry  ←  HardwareProvider     │
            │  (picks LiteRT vs MediaPipe vs fallback  │
            │   based on device RAM tier)              │
            └─────────────────┬─────────────────────────┘
                              │
       ┌──────────────────────┼────────────────────────┐
       ▼                      ▼                        ▼
 LocalAiEngine          EmbeddingEngine          ModelManager
 (LiteRT-LM /           (MediaPipe Tasks         (Ktor download
  Gemma 4)              Text Embedder)            + SHA-256 +
                                                  atomic move)
```

See [`ARCHITECTURE.md`](ARCHITECTURE.md) for the full design rationale, including how the RAM-tier policy works and why the OEM RAM-expansion detection is necessary.

## Enterprise support, custom implementations & architectural advising

If your team is migrating from cloud LLM APIs to on-device inference, designing a Kotlin Multiplatform AI stack, or needs a commercial license for proprietary distribution:

**[sgupta8874@gmail.com](mailto:sgupta8874@gmail.com)**

Typical engagements:

- **Commercial licensing** (see [`COMMERCIAL.md`](COMMERCIAL.md))
- **Architectural advisory** — KMP module layout, agent patterns on top of `LocalAiEngine`, cloud-to-edge migration playbooks
- **Custom implementations** — fine-tune integration, multi-model orchestration, RAG pipelines, function-calling schemas tuned to your domain

## Roadmap

- **v0.1** (this release) — Android target production-ready. iOS targets compile (commonMain shared infrastructure) but native engine bindings are not yet wired.
- **v0.2** — iOS native engine implementation using LiteRT-LM's Swift Metal-accelerated APIs.
- **v0.3** — Benchmark suite (tokens/sec, RAM ceiling, battery drain) across a representative device matrix.
- **v0.4** — Optional sample app showing chat + RAG + function-calling on a single screen.

## License

Dual-licensed under the **GNU Affero General Public License v3.0** ([`LICENSE`](LICENSE)) for open-source / research use, and under a **commercial license** for proprietary distribution ([`COMMERCIAL.md`](COMMERCIAL.md)).

Copyright © 2026 Sagar Gupta.

## Built with

- [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) (Apache-2.0) — Google's on-device LLM runtime
- [MediaPipe](https://github.com/google-ai-edge/mediapipe) (Apache-2.0) — text-embedder bindings
- [Ktor](https://ktor.io) (Apache-2.0) — HTTP client for model downloads
- [Okio](https://square.github.io/okio/) (Apache-2.0) — streaming file I/O + SHA-256
- [kotlin-inject](https://github.com/evant/kotlin-inject) (Apache-2.0) — compile-time DI
