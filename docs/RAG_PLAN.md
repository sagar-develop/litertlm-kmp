# NativeLM v0.4 — Document RAG implementation plan

_Branch: `feat/rag-v0.4`. Created 2026-05-31. This is the build that turns NativeLM from "Local AI" into real **on-device document chat** — switch the marketing framing only AFTER this ships._

## What's already done (engine is RAG-ready)
Confirmed by reading the code, not the docs:
- `lib/.../EmbeddingEngine.kt` — `suspend fun embed(text): FloatArray` (100-dim) — **exists**, + `MediaPipeEmbeddingEngine` Android impl.
- `LocalAiEngine.formatPrompt(userQuery, retrievedContext, systemInstruction)` — **already takes retrieved context.**
- `Attachment.Document(text)` in the request API — **exists.**
- `NativeLmModelCatalog` already lists the Universal Sentence Encoder (EMBEDDING role, ~6 MB).
- ObjectBox already wired in `sample-app` (`ConversationEntity`, `MessageEntity`, `ObjectBox.kt`, `objectbox-models/default.json`).
- KV-cache `ChatSession` works (v0.3).

**Implication:** v0.4 is app-layer assembly + a util lift. No engine surgery.

## What's missing (the v0.4 work)
1. `DocumentEntity` + `DocumentChunkEntity` (ObjectBox, HNSW 100-dim COSINE).
2. `DocumentRepository` impl (HNSW `nearestNeighbors`, tx-split delete).
3. PDF/text extraction (`TextExtractor` + PDFBox) + `TextChunker` (lift from predecessor).
4. `DocumentIngestor` (extract → chunk → embed → store, with progress) + `DocumentRetriever` (embed query → search → format).
5. Document import UI (SAF picker) + document management screen.
6. RAG toggle in `ChatScreen` + retrieval hook in `NativeLmViewModel.sendChatMessage()` + citation footer.

---

## The interface contract (Module 0 — keystone, lands first)

All package roots under `com.nativelm.app`. These signatures are the integration boundary; once committed, modules build in parallel against them.

```kotlin
// data.db — entities (ObjectBox) — OWNED BY CLAUDE (entity/model-file changes are conflict-prone)
@Entity
data class DocumentEntity(
    @Id var id: Long = 0,
    var title: String = "", var sourceUri: String = "", var mimeType: String = "",
    var pageCount: Int = 0, var chunkCount: Int = 0, var createdAt: Long = 0,
)
@Entity
data class DocumentChunkEntity(
    @Id var id: Long = 0,
    @Index var documentId: Long = 0,
    var text: String = "", var pageNumber: Int = 0, var chunkIndex: Int = 0,
    @HnswIndex(dimensions = 100, distanceType = VectorDistanceType.COSINE,
               neighborsPerNode = 48, indexingSearchCount = 200)
    var embedding: FloatArray? = null,
) // content-aware equals/hashCode on embedding

// data.db — vector store
interface DocumentRepository {
    suspend fun createDocument(title: String, uri: String, mime: String, pageCount: Int): Long
    suspend fun addChunks(documentId: Long, chunks: List<DocumentChunkEntity>)
    suspend fun findSimilarChunks(queryEmbedding: FloatArray, k: Int, documentIds: List<Long>? = null): List<ScoredChunk>
    suspend fun listDocuments(): List<DocumentEntity>
    suspend fun deleteDocument(documentId: Long) // tx-split: chunks then doc
}
data class ScoredChunk(val chunk: DocumentChunkEntity, val score: Double)

// rag.extract — extraction & chunking
interface TextExtractor { suspend fun extract(uri: String, displayName: String?): ExtractedDoc }
data class ExtractedDoc(val text: String, val pageCount: Int, val mimeType: String)
class TextChunker(val chunkSize: Int = 500, val overlap: Int = 50) {
    fun chunk(text: String): List<Chunk>
}
data class Chunk(val text: String, val index: Int, val pageNumber: Int = 0)

// rag — orchestration
interface DocumentIngestor { fun ingest(uri: String, displayName: String?): Flow<IngestState> }
sealed interface IngestState {
    data object Extracting : IngestState
    data class Chunking(val total: Int) : IngestState
    data class Embedding(val done: Int, val total: Int) : IngestState
    data class Done(val documentId: Long, val chunkCount: Int) : IngestState
    data class Failed(val error: String) : IngestState
}
interface DocumentRetriever {
    suspend fun retrieve(query: String, k: Int = 5, documentIds: List<Long>? = null): RetrievedContext
}
data class RetrievedContext(val contextText: String, val citations: List<Citation>) {
    val isEmpty get() = citations.isEmpty()
    companion object { val EMPTY = RetrievedContext("", emptyList()) }
}
data class Citation(val documentTitle: String, val pageNumber: Int, val snippet: String)

// rag — context formatting (prompt-injection-safe)
object RagContextFormatter {
    const val MAX_CONTEXT_CHARS = 4000
    // wraps chunks in "--- CONTEXT START ---" / "--- CONTEXT END ---", caps total at MAX_CONTEXT_CHARS
    fun format(chunks: List<ScoredChunk>, titleOf: (Long) -> String): RetrievedContext
}
```

---

## Module breakdown + parallelization map

| Mod | Scope | Files (all NEW unless noted) | Owner | Depends on |
|----|-------|------|-------|-----------|
| **0** | Contracts + entities + PDFBox dep + nav stubs | `data/db/Entities.kt` (edit), interfaces above, `gradle/libs.versions.toml` (edit), `objectbox-models/default.json` (regen) | **Claude** | — |
| **A** | Extraction + chunking | `rag/extract/TextExtractor.kt`, `AndroidTextExtractor.kt`, `TextChunker.kt` + unit tests | **Antigravity** | 0 (interfaces) |
| **B** | Vector store impl | `data/db/ObjectBoxDocumentRepository.kt` + tests | **Claude** | 0 (entities) |
| **C** | Ingestion + retrieval | `rag/DefaultDocumentIngestor.kt`, `DefaultDocumentRetriever.kt`, `RagContextFormatter.kt` + tests | **Claude** | A+B interfaces |
| **D** | Import + manage UI | `ui/documents/DocumentImportScreen.kt`, `DocumentListScreen.kt`, import-progress UI | **Antigravity** | 0 (interfaces) |
| **E** | Chat integration + verify | `llm/NativeLmViewModel.kt` (edit), `ui/chat/ChatScreen.kt` (edit), nav wiring, citation footer | **Claude** | A–D |

### Why this split avoids merge conflicts
- **Claude owns every edit to shared/hot files**: `Entities.kt`, the regenerated `objectbox-models/default.json`, `NativeLmViewModel.kt`, `ChatScreen.kt`, and the version catalog. These are the only files two agents would otherwise collide on.
- **Antigravity only CREATES NEW files in NEW packages** (`rag/extract/`, `ui/documents/`). It builds against the interfaces Claude commits in Module 0. Zero edits to entities, ViewModel, or the ObjectBox model.
- PDFBox dependency is added by Claude in Module 0 so Antigravity never touches `build.gradle.kts`.
- Worktrees: Antigravity works on `feat/rag-extract-ui` (separate branch/worktree); Claude on `feat/rag-v0.4`. Claude merges A+D in.

### Sequencing
1. **Claude → Module 0**, commit + push `feat/rag-v0.4`. (Unblocks everyone.)
2. **Parallel:** Antigravity does A+D on `feat/rag-extract-ui`; Claude does B+C on `feat/rag-v0.4`.
3. **Claude → Module E**: merge A+D, wire integration, **verify on CPH2723** (ingest a PDF → ask a grounded question → see citations). On-device pass = the "shipped" bar.
4. Ship v0.4: anchor blog → staggered posts (document-chat framing now valid).

---

## Gotchas (from the predecessor — bake these in)
- **HNSW tx-split (landmine #3):** chunk deletes and the parent-doc delete must be in **separate** transactions, or HNSW commit deadlocks. `deleteDocument` = delete chunks (tx1) → delete doc (tx2).
- **Dimension lock = 100** (USE-Lite). Reserve a `256` migration path for EmbeddingGemma but don't build it now. Query-time dimension mismatch throws.
- **Prompt-injection:** retrieved chunks go into the prompt verbatim. `RagContextFormatter` MUST wrap them in explicit `--- CONTEXT START/END ---` boundaries and cap at ~4000 chars.
- **PDFBox init:** call `PDFBoxResourceLoader.init(appContext)` once, off the UI thread, before first extract.
- **SAF `content://` URIs** have opaque tails — use the `displayName` for extension detection, not the URI.
- **Binary guard** in text extraction: sample first ~1KB; if >30% non-printable, reject (avoid crash on garbage).
- **Chunk dedup:** the 50-char overlap produces near-duplicate neighbors — `distinctBy` on a normalized snippet before sending to the prompt.
- **Char-based chunking** (500/50) is what the predecessor shipped; keep it for v0.4, note token-aware chunking as a v0.5 refinement.

## Predecessor lift sources (`D:\android-studio\projects\LearnLM`)
- `shared/ai-orchestrator/.../util/TextExtractor.kt` + `androidMain/.../AndroidTextExtractor.kt` (PDFBox, `com.tom_roush:pdfbox-android:2.0.x`)
- `shared/ai-orchestrator/.../util/TextChunker.kt`
- `shared/database/.../entities/Question.kt` (HNSW pattern → adapt to `DocumentChunkEntity`)
- `shared/database/androidMain/.../ObjectBoxQuestionRepository.kt` (`nearestNeighbors` + `findWithScores`)
- `DefaultNotebookOrchestrator.kt` (Vaidya branch `feature/litert-lm-integration`) — ingestion + `askQuestion` flow
- `DocumentChunk` entity from commit `50206ad`
