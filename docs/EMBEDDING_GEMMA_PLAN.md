# NativeLM — EmbeddingGemma on-device RAG embedder (implementation plan)

> **Status (2026-06-08): IMPLEMENTED** on branch `feat/embedding-gemma` (issue #30).
> Deviations from the plan below, decided during build:
> - **Full 3-tier matrix + reranker** built now (not deferred): USE-Lite@100 (entry) /
>   EmbeddingGemma@256 (mid) / @512 + `ms-marco-MiniLM-L6` cross-encoder reranker
>   (flagship), chosen by a device-RAM **recommendation engine**. One Matryoshka model
>   serves all Gemma tiers; per-dim ObjectBox HNSW entities (128/256/512) added.
> - **Tokenizer: pure-Kotlin**, not onnxruntime-extensions (its `gen_processing_models`
>   doesn't support GemmaTokenizer) nor a Rust/DJL native lib. BPE (embedder) + BERT
>   WordPiece (reranker), both validated bit-for-bit vs HF `transformers`. No extra .so.
> - **Companion download** through the catalogue (graph + `model.onnx_data` + tokenizer);
>   the external-data blob keeps its original name so ORT resolves it.
> - Quick-win **per-document cap** shipped in the retriever (the wrong-PDF fix).
> See `CHANGELOG.md` [Unreleased] and `_session/material/blog-embeddinggemma-rag.md`.


_Branch: `claude/analysis-KV4t2`. Goal: replace the 2018-era Universal Sentence
Encoder (USE-Lite, 100-dim) with **EmbeddingGemma 300M** as the default RAG
embedder, lifting retrieval quality for both chat answers and every Studio
artifact. USE-Lite stays as the low-end / no-download fallback._

## Locked decisions

| Decision | Choice | Why |
|---|---|---|
| **Runtime** | **ONNX Runtime (Android)** + a SentencePiece/HF tokenizer | Self-contained; full control of task-prompts, pooling, normalization, Matryoshka. **No Google telemetry deps** (protects the zero-telemetry stance — commit `d5b5fa9`). KMP/iOS-friendly. Avoids the MediaPipe `TextEmbedder` path that broke before. |
| **Dimension** | **256** (Matryoshka truncation of the 768-native vector) | Best quality/size/speed balance on-device; the migration path already reserved in `DocumentChunkEntity`. ~2.5× storage vs the current 100-dim but far better retrieval; half the index cost of 512. |
| **Rollout** | **Default on capable devices; USE-Lite stays as fallback** | Friction-free first run preserved. Low-end / no-download installs keep working on USE-Lite. Two HNSW indexes coexist; the active embedder selects which one. |

---

## Why the earlier attempt failed (recorded so we don't repeat it)

EmbeddingGemma is a 300M transformer, **not** a TFLite *Task* model. Three
independent landmines, any one of which sinks a naive swap:

1. **Wrong loader.** `MediaPipeEmbeddingEngine` calls
   `TextEmbedder.createFromFile()`, which only accepts TFLite Task models with
   baked-in tokenizer metadata (USE/BERT-style). EmbeddingGemma won't load there
   (or returns garbage). **→ This plan introduces a separate ONNX engine; it does
   not touch the MediaPipe path.**
2. **Dimension lock.** `DocumentChunkEntity.@HnswIndex(dimensions = 100L)` is an
   annotation **literal**. EmbeddingGemma emits 768/512/256 → ObjectBox throws the
   moment a longer vector is inserted/queried. **→ This plan adds a new 256-dim
   entity rather than editing the 100-dim one.**
3. **Missing task prompts.** EmbeddingGemma *requires* instruction prefixes;
   `EmbeddingEngine.embed(text)` is symmetric and `DefaultDocumentRetriever` calls
   `embed(query)` with no role. Even if it loaded, retrieval would look "broken."
   **→ This plan makes the interface task-aware.**

---

## Architecture

```
                         ┌─ EmbeddingTask.QUERY    → "task: search result | query: {q}"
query / chunk text  ──►  │                                                       │
                         └─ EmbeddingTask.DOCUMENT → "title: {t|none} | text: {c}"│
                                                                                  ▼
                                            ┌──────────────────────────────────────┐
                                            │ OnnxEmbeddingEngine (androidMain)      │
                                            │  tokenize (SentencePiece/HF)           │
                                            │  → ORT run → last_hidden_state         │
                                            │  → mean-pool over attention mask       │
                                            │  → truncate to 256 (Matryoshka)        │
                                            │  → L2 normalize                        │
                                            └──────────────────────────────────────┘
                                                                  │ 256-dim FloatArray
                                                                  ▼
   active embedder selects index ──►  GemmaChunkEntity (256-dim HNSW)   [default]
                                      DocumentChunkEntity (100-dim HNSW) [USE fallback / legacy]
```

The **active embedder** is an install-level property (which EMBEDDING model is
downloaded + chosen). It determines (a) which engine `embed*` routes to and
(b) which HNSW entity ingestion/retrieval use. Switching embedders triggers a
**re-index from stored chunk text** (no re-extraction needed).

---

## Interface contract (lands first — Module 0)

```kotlin
// lib/commonMain — EmbeddingEngine.kt  (BREAKING: task-aware)
enum class EmbeddingTask { QUERY, DOCUMENT }

interface EmbeddingEngine {
    /** Output dimension of this embedder (USE-Lite = 100, EmbeddingGemma = 256). */
    val dimensions: Int
    suspend fun initialize(modelPath: String)
    /** [title] is only used for DOCUMENT task on prompt-instructed models; ignored otherwise. */
    suspend fun embed(text: String, task: EmbeddingTask, title: String? = null): FloatArray
}
```

```kotlin
// lib/commonMain — ModelCatalog.kt
enum class ModelFormat { LITERTLM, MEDIAPIPE_TEXT_EMBEDDER, WHISPER_GGML, ONNX_EMBEDDER }

// ModelDescriptor gains companion-file support so the tokenizer ships with the model:
data class ModelDescriptor(
    /* …existing… */
    val companions: List<CompanionFile> = emptyList(),   // NEW — e.g. tokenizer.json
)
data class CompanionFile(val url: String, val fileName: String, val sizeBytes: Long, val sha256: String? = null)
```

```kotlin
// sample-app data.db — new 256-dim chunk entity (parallel to DocumentChunkEntity)
@Entity
class GemmaChunkEntity {
    @Id var id: Long = 0
    @Index var documentId: Long = 0
    @Index var projectId: Long = 0
    var text: String = ""; var pageNumber: Int = 0; var chunkIndex: Int = 0
    @HnswIndex(dimensions = 256L, distanceType = VectorDistanceType.COSINE,
               neighborsPerNode = 48, indexingSearchCount = 200)
    var embedding: FloatArray? = null
    companion object { const val EMBEDDING_DIM = 256 }
}
```

The `DocumentRepository` ingestion/retrieval methods route to the entity matching
the active embedder; `ScoredChunk` stays the common return shape so
`DefaultDocumentRetriever` / `RagContextFormatter` are largely unchanged.

---

## Module breakdown

| Mod | Scope | Key files | Depends on |
|----|-------|-----------|-----------|
| **0** | Contracts: task-aware `EmbeddingEngine`, `ONNX_EMBEDDER` format, `companions` on `ModelDescriptor`, `GemmaChunkEntity` (regen `objectbox-models/default.json`) | `EmbeddingEngine.kt`, `ModelCatalog.kt`, `Entities.kt`, version catalog | — |
| **A** | ONNX engine: ORT session, tokenizer, mean-pool + Matryoshka-256 + L2-norm, task prompts | `OnnxEmbeddingEngine.kt` (androidMain), DI wiring in `AndroidAiEngineComponent.kt` | 0 |
| **B** | Catalog + download: EmbeddingGemma descriptor (`requiresAuth = true`), tokenizer companion download, sha256 pins | `NativeLmModelCatalog.kt`, model-download path | 0 |
| **C** | Repository routing: `GemmaChunkEntity` CRUD + HNSW search; active-embedder selector | `ObjectBoxDocumentRepository.kt`, `RagHolder.kt` | 0 |
| **D** | Ingest/retrieve task wiring: `embedDocument` on ingest, `embedQuery` on retrieve; re-tune distance gate | `DefaultDocumentIngestor.kt`, `DefaultDocumentRetriever.kt` | A,C |
| **E** | Migration: background re-index USE→Gemma from stored text, with progress + resume | new `EmbeddingMigrator.kt`, `NativeLmViewModel.kt` | C,D |
| **F** | UI + gating: embedder shown in Models screen (Recommended/Advanced, Gemma terms), device gating, re-index progress | `ModelManagementScreen.kt`, onboarding terms gate | B,E |
| **G** | Backup/sync compatibility: carry embedder tag; re-index on mismatched import | `BackupManager.kt`, `BackupModels.kt`, sync transport | E |

---

## Migration / re-index plan

Embeddings are **derived data**; `DocumentChunkEntity.text` is already persisted,
so re-indexing never needs the original PDFs.

1. On first run after EmbeddingGemma is downloaded + selected, kick a background
   `EmbeddingMigrator` (resumable, idempotent — skip docs already in `GemmaChunkEntity`).
2. Stream chunks per project → `embed(text, DOCUMENT, title)` → write to
   `GemmaChunkEntity`. Reuse the `IngestState.Embedding(done,total)` progress UI.
3. Until a project is migrated, retrieval **falls back to the 100-dim index** so
   chat keeps working.
4. After a project migrates, delete its old 100-dim chunks to reclaim storage
   (tx-split delete — see gotchas).
5. Low-end devices that never download EmbeddingGemma stay entirely on USE-Lite.

---

## Gotchas (bake these in)

- **HNSW tx-split (carried over):** chunk deletes and parent-doc deletes go in
  **separate transactions**, or HNSW commit deadlocks. Applies to the re-index
  cleanup too.
- **Distance gate is USE-tuned.** `DefaultDocumentRetriever.RELEVANCE_MAX_DISTANCE
  = 0.75` was tuned for USE-Lite's distribution. EmbeddingGemma's cosine spread
  differs — **re-tune per active embedder** (likely a separate constant), or
  off-topic queries will over/under-ground.
- **Task prompts are mandatory.** Query = `task: search result | query: …`;
  Document = `title: {title or "none"} | text: …`. Wrong/missing prompts quietly
  tank recall.
- **Matryoshka order: truncate *then* re-normalize.** Take the first 256 dims of
  the pooled vector, *then* L2-normalize — not the reverse.
- **Tokenizer is the fiddly bit.** Ship `tokenizer.json` as a `companions` file
  (or app asset) and run it via `onnxruntime-extensions` (in-graph) or the HF
  `tokenizers` Android binding. Cap `max_seq_len` (~512) — chunks are ~500 chars
  so this is safe and bounds latency/memory.
- **Latency.** A 300M transformer per chunk is far slower than USE's 6MB model;
  a big PDF can go from seconds to minutes. Mitigate: quantized (INT8/QAT) ONNX,
  XNNPACK threads, batch tokenization, and run ingestion/migration off the main
  thread (ties into the deferred foreground-service download/ingest work in
  `PLAY_STORE.md §9`).
- **Memory coexistence.** Don't embed and generate simultaneously — the LLM is the
  big RAM tenant. Sequence ingestion/migration vs active chat generation.
- **Gemma licensing.** EmbeddingGemma is Gemma-licensed → `requiresAuth = true`,
  `Authorization: Bearer <hf-token>`, surfaced under the **Advanced — Hugging Face
  account** section and the onboarding **terms gate** already built in PR #22.
- **Backup/sync dimension mismatch.** A backup/synced DB may carry vectors from a
  different embedder/dimension. Tag exports with the embedder id; on import with a
  mismatch, **re-index from the included chunk text** rather than trusting vectors.
- **APK/download budget.** ORT Android AAR (~10–20 MB, arm64-only to match the
  existing `abiFilters`) + the quantized ONNX model (~100–200 MB, downloaded, not
  bundled) + tokenizer. Confirm against the size budget.

---

## Dependencies to add

- `com.microsoft.onnxruntime:onnxruntime-android` (full build for op coverage;
  revisit ORT-format + `onnxruntime-mobile` later for size).
- `com.microsoft.onnxruntime:onnxruntime-extensions-android` (in-graph tokenizer),
  **or** the HF `tokenizers` Android binding as fallback.
- arm64-v8a only, consistent with `libwhisper.so` and the LiteRT-LM footprint.

---

## Testing / verify (the ship bar)

On-device on **CPH2723 (release build)**:
1. Fresh ingest of a real PDF → confirm chunks land in `GemmaChunkEntity` (256-dim).
2. **Retrieval quality A/B**: a fixed query set, USE-Lite vs EmbeddingGemma — the
   win must be visible (recall on names/concepts, fewer off-topic citations).
3. **Latency/memory**: per-chunk embed time + peak RAM during ingest; confirm a
   multi-page PDF completes acceptably and coexists with chat.
4. **Migration**: upgrade an install with existing USE-Lite docs → re-index runs,
   shows progress, retrieval keeps working throughout, old vectors reclaimed after.
5. **Low-end fallback**: a device that declines the download stays on USE-Lite and
   functions unchanged.
6. **Distance-gate tuning**: verify off-topic questions still return
   `RetrievedContext.EMPTY` with the re-tuned threshold.

**Done when:** EmbeddingGemma is the default embedder on a capable device, an
existing install migrates cleanly, retrieval quality is visibly better, and the
USE-Lite fallback path still works end-to-end.

---

## Out of scope (follow-ups)

- **Reranker** second stage (cross-encoder) — separate plan; complements this.
- **ORT-format / mobile build** size optimization — after correctness is proven.
- **iOS embedder** — the ONNX engine is KMP-portable; wire `iosMain` later.
- **Token-aware chunking** — still char-based (500/50); revisit independently.
