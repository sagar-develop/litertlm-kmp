# Engine orchestration-layer extraction

Goal: move the reusable, UI-free *orchestration* (RAG, Studio, backup, sync, voice)
out of the `sample-app` (NativeLM) module and into the published engine library
(`lib/`, `com.sagar.aicore`), so the engine is a real on-device AI SDK that NativeLM,
Curio, and commercial licensees all build on — not just a generation wrapper with a
separate app.

The technique throughout is **dependency inversion**: the engine defines interfaces
+ neutral DTOs and holds the logic; the app provides the platform/persistence
implementations and wires them together (same pattern the engine already uses for
`HardwareProvider` / `NetworkProvider` / `PlatformFolders`).

---

## Phase 1 — RAG orchestration + pure utils  ✅ DONE (this branch)

Moved into `lib/src/commonMain/kotlin/com/sagar/aicore/`:

**`rag/` (new package)**
- `RagModels.kt` — neutral DTOs: `StoredDocument`, `StoredChunk`, `NewChunk`,
  `ScoredChunk`, `Citation`, `RetrievedContext`, `IngestState`, `ExtractedDoc`, `Chunk`.
- `RagContracts.kt` — interfaces the app implements: `DocumentStore`,
  `DocumentIngestor`, `DocumentRetriever`, `TextExtractor`, `FileStore`.
- `KeywordSearch.kt`, `TextChunker.kt`, `RagContextFormatter.kt`, `CitationJson.kt`
  — pure logic (BM25 + RRF, chunking, context fencing, citation (de)serialization).
- `DefaultDocumentRetriever.kt`, `DefaultDocumentIngestor.kt` — the hybrid
  retrieval + ingestion pipelines, now written against `DocumentStore` + DTOs.
- `ThinkSpan.kt` (`com.sagar.aicore.renderAssistantText`) — `<think>`-span stripping.

Tests moved to `lib/src/commonTest/.../rag/` (JUnit → kotlin-test, runs on all targets).

**App side (`sample-app`)**
- `ObjectBoxDocumentRepository` now implements `com.sagar.aicore.rag.DocumentStore`
  and is the **only** place ObjectBox entities ↔ engine DTOs are mapped.
- `AndroidTextExtractor` implements engine `TextExtractor`; `AndroidDocumentFileStore`
  implements engine `FileStore`; `RagHolder` wires them into the engine pipelines.
- `OcrEngine`/`MlKitOcrEngine` **stayed in the app** — OCR is an implementation
  detail of the Android extractor, not part of the ingestion contract.
- Deleted the app copies of all moved files; updated imports in `NativeLmViewModel`,
  `ChatScreen`, `DocumentsScreen`, `BackupManager`.

> ⚠️ Build status: this was done in an environment without the Android SDK, so it
> has **not been compiled**. Run `./gradlew :lib:compileKotlinMetadata
> :lib:testDebugUnitTest :sample-app:assembleDebug` and fix any residual import
> nits before release. The decoupling is structural; no behavior was intended to change.

---

## Phase 2 — deferred (next branches)

Each is a self-contained follow-up using the same invert-the-dependency move.

### Studio engine → `com.sagar.aicore.studio`
`StudioGenerator` is **already fully decoupled** (injected `llm` lambda + `Source`
of strings), so the generator + `StudioPrompts` + `StudioArtifactType` + the pure
artifact parsers move almost verbatim. The only friction: the parsers
(`parseTimeline`, mind-map, podcast) are shared with the Compose UI, so the UI
imports shift too. Define a `TtsSpeaker` interface in the engine; `TtsController`
stays in the app and implements it.

### Backup → `com.sagar.aicore.backup`
- `BackupCrypto` (Argon2id + AES-GCM) and `BackupModels` move to `androidMain`/jvm.
- `BackupManager` splits: the `.nlmbak` format + orchestration move (over the store
  interfaces); the `Context`/ObjectBox/`AppPreferences` wiring stays in the app.

### Sync → `com.sagar.aicore.sync`
- `SocketTransfer` (java.net sockets) → engine `androidMain`/jvm.
- `NsdHelper` stays in the app behind a new engine `PeerDiscovery` interface.
- `SyncManager` splits: protocol/state machine → engine; `Context` wiring stays.

### Voice → engine `androidMain`
`WhisperSpeechToText` already implements the engine's `SpeechToText` interface, so
it + `WhisperNative` (JNI) + `AudioRecorder` move down with little change.

### Conversation / project / studio persistence
Promote `ConversationStore` / `ProjectStore` / `StudioStore` interfaces (over DTOs)
into the engine, mirroring `DocumentStore`; ObjectBox impls + entities stay in the app.

---

## Stays in the app (by design)
Compose UI/screens/theme, ObjectBox entities + `ObjectBox.kt`, `AppPreferences` /
`SecureStore` (DataStore), `MainActivity`/`SampleApplication`, the DI/composition
root (`EngineHolder`/`RagHolder`/`NativeLmViewModel`), and `NativeLmModelCatalog`
(the app's specific model list; the `ModelCatalog` interface is already in the engine).
