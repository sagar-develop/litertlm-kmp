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

This library solves all six. The bundled `sample-app/` demonstrates the entire flow with a live CPU & RAM metrics overlay so you can see exactly what running Gemma on-device looks like.

## Platform support

| Platform | Core engine | Hardware acceleration | Status |
|---|---|---|---|
| **Android** (API 24+) | Production | GPU / NPU via LiteRT delegate selection | Production-vetted on flagship + mid-tier devices |
| **iOS** (arm64 + Apple Silicon sim) | Architecture-ready | Planned: Metal GPU acceleration via LiteRT-LM Swift APIs | Roadmap — v0.3 |

The common module (`lib/src/commonMain`) carries the engine state machine, model-catalog typing, Ktor-backed download manager, and function-calling schema conversion. iOS-side native bindings ship in v0.3 using LiteRT-LM's Swift APIs.

## Quickstart — adding the library to your app

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
    implementation("com.github.sagar-develop:litertlm-kmp:v0.2.0")
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

## Running the sample app

The `sample-app/` module is a single-activity Compose app that exercises the entire library — streaming chat, function-calling, and a live CPU & RAM metrics overlay that updates 4× per second so you can see exactly what Gemma 4 is doing to your device while it generates.

### 1. Host the model weights yourself

The repo does **not** ship binary model weights — Gemma's license permits redistribution but each consumer is responsible for hosting. The sample app downloads from any HTTPS URL you point it at on first launch. Recommended path: **download from HuggingFace, host on Firebase Storage**.

#### Step 1 — Download a Gemma 4 LiteRT-LM `.litertlm` from HuggingFace

LiteRT-LM-formatted Gemma weights live on the [litert-community](https://huggingface.co/litert-community) HuggingFace org:

- Sign in to HuggingFace and accept the Gemma terms-of-use on the model card (one-time).
- Download the `.litertlm` artifact for the variant you want — Gemma 4 E2B (~2.5 GB, fits 6–9 GB RAM devices) is the safe default.
- Keep the file on disk for the next step.

#### Step 2 — Upload to Firebase Storage

If you don't already have a Firebase project, [console.firebase.google.com](https://console.firebase.google.com) → **Add project** (free Spark plan is sufficient).

1. In the Firebase console, open **Storage** → **Get started** → keep production rules → choose your region.
2. Click **Upload file** → pick the `.litertlm` you downloaded. Wait for the upload to complete (the file is ~2.5 GB; 5–15 min depending on your uplink).
3. Click the uploaded file → switch to the **Name** column → click the small "download" icon → "**Copy access token URL**". The URL looks like:
   ```
   https://firebasestorage.googleapis.com/v0/b/YOUR-PROJECT.firebasestorage.app/o/gemma-4-E2B-it.litertlm?alt=media&token=...
   ```
4. Treat this URL as semi-public — anyone with it can download from your bucket. Firebase Storage's free tier covers ~10 GB egress / month; for higher volume, use Cloudflare R2 or S3 instead.

#### Step 3 — Paste the URL into `sample-app/local.properties`

```bash
cp sample-app/local.properties.template sample-app/local.properties
```

Then edit `sample-app/local.properties`:

```properties
model.url=https://firebasestorage.googleapis.com/v0/b/YOUR-PROJECT.firebasestorage.app/o/gemma-4-E2B-it.litertlm?alt=media&token=YOUR-TOKEN
model.fileName=gemma-4-E2B-it.litertlm
model.sizeBytes=2588000000
```

`local.properties` is gitignored — your URL and token never get committed.

### 2. Build and install

```bash
./gradlew :sample-app:installDebug
adb shell am start -n com.sagar.litertlmsample/.MainActivity
```

First launch shows the Setup screen → **Download & initialize**. The download happens once (5–15 min depending on your network) and persists in `/data/data/com.sagar.litertlmsample/files/models/`. Subsequent launches skip straight to the Ready state.

### 3. What you'll see

- **Chat tab** — streaming responses from on-device Gemma 4. No network, no API key, no cloud bill.
- **Function calling tab** — typed schema → OpenAPI JSON → structured extraction round-trip in real time.
- **Live metrics overlay** — flowing CPU% history line, per-core utilization bars (your phone's big.LITTLE cores at work), RAM PSS, tokens/sec, time-to-first-token. Updates every 250 ms.

## Architecture at a glance

```
            ┌─────────────────────────────────────────┐
            │     Your app (Compose / SwiftUI / …)    │
            └─────────────────────────────────────────┘
                              │
            ┌─────────────────▼─────────────────────────┐
            │  EngineRegistry  ←  HardwareProvider      │
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

- **v0.1** — initial library release. Android target production-ready, iOS targets compile but native engine bindings deferred.
- **v0.2** (this release) — `sample-app/` Compose Android app with live CPU + RAM + tokens/sec metrics overlay. Library restructured into `:lib` subproject; published as `com.sagar:litertlm-kmp`.
- **v0.3** — iOS native engine implementation via LiteRT-LM's Swift Metal-accelerated APIs.
- **v0.4** — Benchmark suite (tokens/sec, RAM ceiling, battery drain) across a representative device matrix.

## Repo layout

```
litertlm-kmp/
├── lib/                       ← the published library
│   ├── src/commonMain/        ← engine interfaces, ModelManager, ToolSchemaConverter
│   ├── src/androidMain/       ← LiteRT-LM JNI, MediaPipe text embedder, OEM-aware HardwareProvider
│   └── src/iosMain/           ← iOS PlatformFolders (full engine actuals — v0.3)
├── sample-app/                ← Compose Android app demonstrating the library
│   ├── src/main/kotlin/com/sagar/litertlmsample/
│   │   ├── metrics/           ← CpuMonitor, MemoryMonitor, TokenRateMonitor
│   │   ├── llm/               ← EngineHolder + SampleViewModel
│   │   └── ui/                ← Chat / FunctionCall / MetricsOverlay
│   └── local.properties.template  ← model URL config; copy to local.properties
├── ARCHITECTURE.md            ← module layout + design rationale
└── COMMERCIAL.md              ← dual-licensing terms
```

## License

Dual-licensed under the **GNU Affero General Public License v3.0** ([`LICENSE`](LICENSE)) for open-source / research use, and under a **commercial license** for proprietary distribution ([`COMMERCIAL.md`](COMMERCIAL.md)).

Copyright © 2026 Sagar Gupta.

## Built with

- [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) (Apache-2.0) — Google's on-device LLM runtime
- [MediaPipe](https://github.com/google-ai-edge/mediapipe) (Apache-2.0) — text-embedder bindings
- [Ktor](https://ktor.io) (Apache-2.0) — HTTP client for model downloads
- [Okio](https://square.github.io/okio/) (Apache-2.0) — streaming file I/O + SHA-256
- [kotlin-inject](https://github.com/evant/kotlin-inject) (Apache-2.0) — compile-time DI
- [Jetpack Compose](https://developer.android.com/jetpack/compose) (Apache-2.0) — sample-app UI
- [Napier](https://github.com/AAkira/Napier) (Apache-2.0) — KMP-friendly logging
