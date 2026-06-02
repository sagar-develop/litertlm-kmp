# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project loosely follows [Semantic Versioning](https://semver.org/).

> Note on scope: the **engine library** (`lib/`, published as
> `com.sagar:litertlm-kmp`) and the **showcase app** (`sample-app/`, NativeLM)
> live in one repo and share a version line. Entries below note which surface a
> change lands on. The engine library version in `lib/build.gradle.kts` is
> currently `0.3.0`; app-facing milestones (v0.4–v0.6) are tagged on the repo.

## [Unreleased]

### Added
- GitHub Actions CI workflow (`.github/workflows/ci.yml`) that builds the
  library module (`:lib:assemble`) on pushes to `main` and on pull requests.
- `CHANGELOG.md` (this file).
- README: CI status badge and an Installation section pointing at JitPack.

### Changed
- README/doc engine-version references aligned for internal consistency.

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

[Unreleased]: https://github.com/sagar-develop/litertlm-kmp/compare/v0.6.0...HEAD
[0.6.0]: https://github.com/sagar-develop/litertlm-kmp/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/sagar-develop/litertlm-kmp/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/sagar-develop/litertlm-kmp/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/sagar-develop/litertlm-kmp/compare/v0.2.4...v0.3.0
[0.2.4]: https://github.com/sagar-develop/litertlm-kmp/compare/v0.2.0...v0.2.4
[0.2.0]: https://github.com/sagar-develop/litertlm-kmp/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/sagar-develop/litertlm-kmp/releases/tag/v0.1.0
