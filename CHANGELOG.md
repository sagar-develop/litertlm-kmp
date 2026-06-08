# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project loosely follows [Semantic Versioning](https://semver.org/).

> Note on scope: the **engine library** (`lib/`, published as
> `com.sagar:litertlm-kmp`) and the **showcase app** (`sample-app/`, NativeLM)
> live in one repo and share a single version line. Entries below note which
> surface a change lands on. The engine library version in
> `lib/build.gradle.kts` tracks the latest release (`0.9.0`).

## [Unreleased]

### Added
- **EmbeddingGemma RAG embedder (device-tiered, telemetry-free)** — optional upgrade
  from USE-Lite (100-dim) to **EmbeddingGemma-300M** for document retrieval, run on
  **ONNX Runtime** (no Google/Play telemetry deps). One downloaded model serves every
  tier via **Matryoshka** truncation; a **recommendation engine** picks the embedder +
  dim by device RAM (USE-Lite <6 GB · Gemma@256 6–9 GB · Gemma@512 + reranker ≥10 GB),
  surfaced as a "Recommended" badge in the in-app model catalogue. The model and its
  companion files (weights blob + tokenizer) download on-device through the catalogue;
  nothing is bundled in the APK. (engine + app)
- **Cross-encoder reranker (flagship)** — optional second-stage `ms-marco-MiniLM-L6`
  rerank over the top fused candidates, gated to high-RAM devices. (engine + app)
- **Pure-Kotlin tokenizers** — BPE (EmbeddingGemma) and BERT WordPiece (reranker),
  reading the HuggingFace `tokenizer.json`, validated bit-for-bit against the reference
  `transformers` tokenizer. No native tokenizer lib, KMP-portable. (engine)
- **Task-aware embeddings** — `EmbeddingEngine` now distinguishes query vs document
  (instruction prefixes), required for EmbeddingGemma's asymmetric retrieval. (engine)

### Changed
- **Retrieval precision** — added a **per-document cap** so one large source can't fill
  every top-k slot (the "answered from the wrong PDF" failure), plus wider candidate
  pools and a larger grounding budget. Ships independent of the embedder upgrade. (engine)
- **Backup/sync** — backups now carry chunk embeddings from every embedder dim and tag
  each chunk with its dim; cross-embedder restores re-index from the included text
  instead of being rejected (backup schema v2). (app)

### Migration
- Existing projects re-index from stored chunk text into the active embedder's index on
  next open when the embedder changes — no re-import/OCR needed; the USE-Lite index is
  kept as a fallback.

## [0.9.0] — 2026-06-05

### Added
- **Charts in chat** — the model can answer with a **chart** (bar / line / pie),
  rendered natively in the chat bubble. The engine ships a compact chart spec
  plus a **tolerant parser** that recovers chart JSON whether the model fences it
  as ` ```chart `, a generic ` ```json `, or emits it untagged or without a
  `type`; the chart instruction is gated to models capable of following it.
  (engine + app)
- **Adaptive multi-device UI** — a Compose layout that adapts across phone,
  foldable, and tablet/large screens using Material 3 window size classes and the
  adaptive navigation-suite + list-detail panes. (app)
- **Project-led navigation drawer + composer attach** — the drawer surfaces
  **Projects** above recent chats, each section capped to stay scannable (with a
  "Show all projects" expander), and the composer gains a **`+` attach button**
  to import a source (PDF / image / text) straight into the current project. (app)
- **No-account first run** — the model catalog now leads with a **Recommended**
  section of ungated (Apache-2.0 / MIT) models that download with no Hugging Face
  token; the app picks the best one that fits the device's RAM
  (`recommendedModelId`). The license-gated **Gemma** tier moves behind a
  collapsible **Advanced — Hugging Face account** section together with the token
  field. This makes a fresh install (including a Play Store user with no HF
  account) reach a working model in one tap.
- **First-run terms/source gate** — the final onboarding slide links the AGPL
  source and Google's Gemma Terms, and clarifies that downloaded models carry
  their own licenses.
- **Play Store readiness** — added [`PRIVACY.md`](PRIVACY.md) (hosted-ready,
  zero-telemetry privacy policy) and [`PLAY_STORE.md`](PLAY_STORE.md) (a detailed
  submission checklist: AAB, signing, Data Safety answers, permissions, content
  rating, listing assets, compliance, and follow-ups). The policy is also served
  as a static GitHub Pages site under [`docs/`](docs/), and **Settings → About →
  Privacy policy** opens the hosted URL in-app.

### Changed
- **Engine extraction** — the RAG orchestration (ingestor / retriever / chunker /
  keyword search / context formatter), the **Studio** generators, the backup
  crypto, and the P2P **socket transport** moved out of the sample app into the
  reusable `:lib` engine (`com.sagar.aicore.*`), with domain tunables
  externalized into config objects. The app is now a thin showcase over the
  library; the engine is independently consumable. (engine)
- **Richer chat rendering** — improved Markdown rendering in chat bubbles
  alongside the new inline charts. (app)
- **Cleartext traffic disabled** — `usesCleartextTraffic="false"` plus a
  `network_security_config.xml` (`cleartextTrafficPermitted=false`). Model
  downloads are HTTPS; local peer-to-peer sync uses raw sockets carrying
  AES-GCM ciphertext and is unaffected.
- Engine library version bumped to `0.9.0`.

## [0.8.0] — 2026-06-03

### Added
- **App lock** — optional biometric / device-credential gate to open the app (a UI lock; at-rest DB encryption deferred).
- **Local encrypted backup** — export/import your whole knowledge base to a passphrase-encrypted `.nlmbak` file you control (Argon2id → AES-256-GCM). No server, no account, no key we hold.
- **Local peer-to-peer sync** — beam your data device-to-device over Wi-Fi (NSD/mDNS + socket), GMS-free. No cloud.
- **Multilingual answers** — pick the language the model answers in (English + Indian + global languages), prompt-only, including cross-lingual over English documents.
- **On-device voice input** — dictate by voice, transcribed locally via Whisper (whisper.cpp); the Whisper model is a downloadable "Audio" entry in the model catalog.

### Changed
- Engine library version bumped to `0.8.0`.

## [0.6.1] — 2026-06-03

### Fixed
- **Zero-telemetry guarantee restored.** ML Kit (on-device OCR) transitively
  bundles Google's `datatransport` pipeline, which auto-initialized on startup
  and uploaded usage/diagnostics to `firebaselogging.googleapis.com`. Removed the
  CCT backend and its upload schedulers via manifest merge so nothing ever leaves
  the device; on-device OCR is unaffected (verified on-device: image import still
  OCRs and indexes). Also corrects the app `versionName`, which incorrectly read
  `0.5.0` in the v0.6.0 build.

## [0.6.0] — 2026-06-03

### Added
- **NativeLM Studio** — an on-device document studio that generates artifacts
  by a map-reduce pass over a project's sources, all running locally on Gemma:
  - **Briefing**, **FAQ**, **Key Topics** (with an ask-in-chat hook),
    **Study Guide**, **Timeline** (dated-event extraction), and **Mind Map**
    (nested outline → pan/zoom node graph).
  - **Audio Overview** — a single-narrator spoken script played back via
    on-device Text-to-Speech.
  - **Podcast** — a two-host dialogue with two distinct on-device voices.
  - Eight artifact types in total, surfaced through a redesigned generate panel
    and a source-selection bottom sheet, plus a polished artifact viewer.

## [0.5.0] — 2026-06-02

### Added
- **On-device OCR** for scanned PDFs and images, so non-selectable documents
  become searchable RAG sources.
- **Hybrid retrieval** combining keyword and vector search, improving recall on
  exact-term queries that pure vector search could miss.
- Citation experience: tap a citation to open the source PDF at the cited page,
  in-page citation highlight, and pinch-to-zoom in the viewer.
- Citations now persist, so source chips survive reopening a chat.

## [0.4.0] — 2026-06-01

### Added
- **On-device document RAG** in the NativeLM app: import a PDF/text source and
  the app extracts → chunks (page-aware) → embeds (MediaPipe USE-Lite,
  100-dim) → stores in an **ObjectBox HNSW** vector index → retrieves
  project-scoped, relevance-gated context → answers grounded with **citations**.
- **Projects** (notebooks) scope each chat to its own sources; default chat
  stays general.
- Cross-device model catalogue (Gemma + non-Gemma, e.g. Qwen3-0.6B), input
  chips, and a license popup.

### Changed
- Engine `consumer-rules.pro` now keeps the MediaPipe text-embedder + Flogger +
  protobuf surfaces, so RAG embedding survives R8 minification in consumer
  release builds.

## [0.3.0] — 2026-05-30

### Added
- **Stateful KV-cache chat sessions** (`openChatSession` / `ChatSession`):
  lossless multi-turn memory with no history re-sending; time-to-first-token
  stays flat as a conversation grows.
- **Real native cancellation** of in-flight generation (`cancel()`).
- Explicit `EngineConfig.backend` selection and `SamplerConfig`
  temperature/seed plumbing.
- NativeLM showcase app: conversation history (ObjectBox), model-generated
  titles, and a signed, R8-minified release build.

## [0.2.4] — 2026-05-25

### Added
- **Multimodal vision**: image attachments flow through
  `EngineConfig.visionBackend` + `Content.ImageBytes`; `descriptor.supportsVision`
  is now `true` on multimodal Gemma variants.

## [0.2.0] — 2026-05-25

### Added
- `sample-app/` Compose Android app exercising the library, with live CPU + RAM
  + tokens/sec metrics overlay.

### Changed
- Library restructured into the `:lib` subproject; published as
  `com.sagar:litertlm-kmp` (the `0.2.1`–`0.2.3` tags carry packaging/publishing
  fixes on top of this).

## [0.1.0] — 2026-05-25

### Added
- Initial library release. Kotlin Multiplatform wrapper around Google's
  LiteRT-LM for running Gemma-family models on-device. Android target
  production-ready; iOS targets compile with native engine bindings deferred.
- Core engine state machine, model-catalog typing, Ktor-backed download manager
  with resume + SHA-256 validation, OEM-aware hardware-tier provider, and
  function-calling schema conversion (typed Kotlin → OpenAPI JSON).

[Unreleased]: https://github.com/sagar-develop/litertlm-kmp/compare/v0.8.0...HEAD
[0.8.0]: https://github.com/sagar-develop/litertlm-kmp/compare/v0.6.1...v0.8.0
[0.6.1]: https://github.com/sagar-develop/litertlm-kmp/compare/v0.6.0...v0.6.1
[0.6.0]: https://github.com/sagar-develop/litertlm-kmp/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/sagar-develop/litertlm-kmp/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/sagar-develop/litertlm-kmp/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/sagar-develop/litertlm-kmp/compare/v0.2.4...v0.3.0
[0.2.4]: https://github.com/sagar-develop/litertlm-kmp/compare/v0.2.0...v0.2.4
[0.2.0]: https://github.com/sagar-develop/litertlm-kmp/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/sagar-develop/litertlm-kmp/releases/tag/v0.1.0
