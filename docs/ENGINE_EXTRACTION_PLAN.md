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

## Phase 2 — Studio engine → `com.sagar.aicore.studio`  ✅ DONE

`StudioGenerator` was already decoupled (injected `llm` lambda + a `Source` of
strings), so the generator, `StudioPrompts`, `StudioArtifactType`, and every
tolerant parser (FAQ / topics / timeline / mind-map / podcast / study-guide, with
their models) moved almost verbatim — pure Kotlin, multiplatform-safe.
`sanitizeStudioMarkdown` / `stripForSpeech` were promoted `internal → public`.
`TtsController` / `PodcastController` stay in the app (Android `TextToSpeech`
playback) and import `PodcastTurn` / `stripForSpeech` from the engine. No
`TtsSpeaker` interface was needed — generation is engine-side, playback is app-side.

## Phase 3 — Backup crypto → `com.sagar.aicore.backup`  ✅ DONE

`BackupCrypto` (Argon2id key derivation + AES-256-GCM) moved to `androidMain`; `lib`
gained the `signal-argon2` dependency. The reusable crypto primitive is now engine
infrastructure. **`BackupManager`, `BackupModels`, the `.nlmbak` codec stay app-side**
— that orchestration reads the ObjectBox boxes directly, remaps ids, and touches
`Context`/`AppPreferences`/`@Serializable`. A full codec extraction is gated on the
persistence stores below (and would add the kotlinx-serialization compiler plugin to
the engine, which it deliberately omits today).

## Phase 4 — P2P transport → `com.sagar.aicore.sync`  ✅ DONE

`SocketTransfer` (plain-TCP, length-prefixed file transfer of the already-encrypted
`.nlmbak`) moved to `androidMain` — a clean, reusable transport. `NsdHelper` (NSD/mDNS
discovery) and `SyncManager` (the `Context` + `BackupManager` orchestration / state
machine) stay app-side; a future `PeerDiscovery` engine interface can follow.

## Phase 5 — Voice  ✅ ALREADY CORRECTLY SPLIT (no change)

The `SpeechToText` interface already lives in the engine; `WhisperSpeechToText`
implements it in the app. The Whisper impl (`WhisperSpeechToText` / `WhisperNative`
JNI / `AudioRecorder`) **must stay in the app** because the `libwhisper.so` native
build (`externalNativeBuild` / CMake / whisper.cpp) is defined in `sample-app` —
moving the wrapper would drag that native build into every consumer of the published
library. Interface in engine + native impl in app is the correct end-state.

## Phase 6 — Conversation / project / studio persistence  ⏸ DEFERRED BY DESIGN

Unlike `DocumentStore` (which the engine's RAG pipeline consumes), nothing in the
engine consumes conversation/project/artifact persistence today — `ConversationRepository`
/ `ProjectRepository` / `StudioRepository` are concrete ObjectBox classes used only by
the app's ViewModel. Promoting them to engine interfaces over DTOs now would be a
pervasive entity→DTO rewrite of the ViewModel for **speculative API with no consumer**.
Do it when a real engine-side consumer appears (the natural trigger is the backup-codec
extraction in Phase 3), so the contract is shaped by an actual caller.

## Configurability — domain tunables externalized  ✅ DONE

Once the engine owned RAG + Studio, it also carried that logic's *tuning* as
hardcoded constants and prompt text — opinions a consumer couldn't change without
forking. Those are now externalized as config objects (current values kept as
defaults, so every existing call site is unchanged):

- **`RagConfig`** — chunk size/overlap, relevance-distance gate, vector/keyword pool
  sizes, max context chars. `DefaultDocumentRetriever(…, config = RagConfig())`.
- **`StudioConfig`** — map-reduce window/group sizes, reduce passes, and per-artifact
  token budgets. `StudioGenerator(llm, prompts, config = StudioConfig())`.
- **`StudioPrompts`** is now an **interface** (`DefaultStudioPrompts` ships the
  wording) so a consumer can fully override prompt tone/language/instructions.
- **`renderAssistantText(raw, openTag, closeTag)`** — reasoning-span delimiters are
  overridable (default `<think>`/`</think>`).
- **`SocketTransfer.openServerSocket(port = SYNC_PORT)`** — sync port overridable.

What remains intentionally hardcoded: the `InMemoryModelCatalog` sample (consumers
supply their own `ModelCatalog`) and the `Language` strict-script table.

---

## Stays in the app (by design)
Compose UI/screens/theme, ObjectBox entities + `ObjectBox.kt`, `AppPreferences` /
`SecureStore` (DataStore), `MainActivity`/`SampleApplication`, the DI/composition
root (`EngineHolder`/`RagHolder`/`NativeLmViewModel`), and `NativeLmModelCatalog`
(the app's specific model list; the `ModelCatalog` interface is already in the engine).
