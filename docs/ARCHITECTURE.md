# Architecture

NativeLM is an on-device document-chat app built on **litertlm-kmp**, a Kotlin
Multiplatform engine that wraps Google's LiteRT-LM. Everything — the language model,
the embedder, the vector index, OCR, speech-to-text — runs locally. No account, no
upload, no telemetry. This document explains how the pieces fit together and how the
codebase is organised so the boundary between the reusable **engine** and the
**product** stays clean as it grows.

Two Gradle modules:

- **`:lib`** — the engine (`com.sagar.aicore`). Dual-licensed (AGPL-3.0 / commercial).
  Pure Kotlin Multiplatform: `commonMain` holds platform-neutral contracts and
  orchestration; `androidMain` holds the Android-backed inference implementations;
  `iosMain` carries the iOS roadmap surface.
- **`:sample-app`** — the NativeLM product (`com.nativelm.app`). Android + Compose. It
  supplies the platform-backed stores (ObjectBox, DataStore, SAF, ML Kit OCR) and the
  user-facing experience, and depends on `:lib` — never the other way around.

```mermaid
flowchart TB
    subgraph product["sample-app · NativeLM (com.nativelm.app)"]
        ui["Compose UI<br/>chat · documents · models · settings · studio · sync · lock"]
        vm["NativeLmViewModel"]
        holders["EngineHolder · RagHolder<br/>NativeLmModelCatalog · EmbedderRecommendation"]
        platform["Android platform glue<br/>ObjectBoxDocumentRepository (HNSW)<br/>AndroidTextExtractor + MlKitOcrEngine<br/>AppPreferences (DataStore) · SecureStore"]
    end

    subgraph engine[":lib · litertlm-kmp engine (com.sagar.aicore)"]
        contracts["Contracts (commonMain)<br/>LocalAiEngine · EmbeddingEngine · Reranker<br/>DocumentIngestor · DocumentRetriever · DocumentStore<br/>ModelCatalog · ModelManager"]
        impls["Android impls (androidMain)<br/>LiteRtLmLocalAiEngine (Gemma)<br/>OnnxEmbeddingEngine · OnnxReranker<br/>GemmaBpeTokenizer · BertWordPieceTokenizer"]
    end

    ui --> vm --> holders --> contracts
    holders --> platform
    platform -. implements .-> contracts
    contracts --- impls

    classDef p fill:#eef6ee,stroke:#7FA980,color:#1C1B1A;
    classDef e fill:#f5f3ef,stroke:#9a8f7a,color:#1C1B1A;
    class ui,vm,holders,platform p;
    class contracts,impls e;
```

The key architectural rule: **the product talks to the engine only through contracts**
(`LocalAiEngine`, `EmbeddingEngine`, `DocumentRetriever`, `DocumentStore`, …). The
product *provides* the storage implementations (e.g. `ObjectBoxDocumentRepository`
implements the engine's `DocumentStore`) but never reaches into engine internals. That
inversion is what lets the same engine power a second app (a kids' learning app, Curio)
through a Gradle composite build.

---

## Engine internals (`:lib`)

The engine is organised around small, swappable contracts in `commonMain`, each with an
Android implementation in `androidMain`. Inference backends are deliberately
**telemetry-free**: the LLM runs on LiteRT-LM (CPU), and the embedder/reranker run on
**ONNX Runtime** (Microsoft, no Google/Play dependency) rather than MediaPipe — a
conscious choice to protect the zero-telemetry promise.

```mermaid
flowchart LR
    subgraph common["commonMain — contracts & orchestration"]
        lae["LocalAiEngine<br/>(chat, stateful KV session)"]
        ee["EmbeddingEngine<br/>(task-aware: QUERY / DOCUMENT)"]
        rr["Reranker<br/>(cross-encoder, optional)"]
        ing["DocumentIngestor"]
        ret["DocumentRetriever"]
        store["DocumentStore"]
        cat["ModelCatalog · ModelManager<br/>ModelDescriptor · CompanionFile"]
        rag["RAG support<br/>TextChunker · KeywordSearch (BM25+RRF)<br/>RagConfig · RagContextFormatter"]
    end

    subgraph android["androidMain — inference backends"]
        litert["LiteRtLmLocalAiEngine<br/>Gemma via LiteRT-LM (CPU)"]
        onnxE["OnnxEmbeddingEngine<br/>EmbeddingGemma-300M (ONNX)"]
        useE["MediaPipeEmbeddingEngine<br/>USE-Lite 100-dim (entry tier)"]
        onnxR["OnnxReranker<br/>ms-marco MiniLM-L6 (ONNX)"]
        tok["GemmaBpeTokenizer · BertWordPieceTokenizer<br/>(pure-Kotlin, validated vs HF)"]
    end

    lae -. impl .-> litert
    ee -. impl .-> onnxE
    ee -. impl .-> useE
    rr -. impl .-> onnxR
    onnxE --> tok
    onnxR --> tok
    ing --> ee
    ing --> store
    ret --> ee
    ret --> rr
    ret --> store
    ret --> rag

    classDef c fill:#f5f3ef,stroke:#9a8f7a,color:#1C1B1A;
    classDef a fill:#eef2f6,stroke:#6a86a8,color:#1C1B1A;
    class lae,ee,rr,ing,ret,store,cat,rag c;
    class litert,onnxE,useE,onnxR,tok a;
```

Beyond core inference, the engine also hosts: **Studio** (`studio/` — generating
artifacts like mind maps, timelines, podcasts from documents), **Sync** (`sync/` — P2P
device-to-device transfer over NSD/mDNS + TCP, GMS-free), **Backup** (`backup/` —
passphrase-encrypted `.nlmbak` export, Argon2id + AES-256-GCM), and **Chart**
(`chart/`). Speech-to-text (`SpeechToText`) is wired to on-device Whisper in the app.

---

## The RAG pipeline

This is the heart of the product: grounding answers in the user's own documents with
citations. There are two phases — **ingestion** (when a document is imported) and
**retrieval** (when a question is asked).

```mermaid
flowchart TB
    subgraph ingest["Ingestion — on import"]
        i1["PDF / image / text"]
        i2["AndroidTextExtractor<br/>(+ MlKitOcrEngine for scans)"]
        i3["TextChunker<br/>(≈500 chars, 50 overlap)"]
        i4["EmbeddingEngine.embed(text, DOCUMENT)<br/>EmbeddingGemma → Matryoshka dim"]
        i5["ObjectBox HNSW<br/>(per-dim entity: 100/128/256/512)"]
        i1 --> i2 --> i3 --> i4 --> i5
    end

    subgraph retrieve["Retrieval — on each question"]
        q0["User question"]
        q1["EmbeddingEngine.embed(query, QUERY)"]
        qV["Vector arm<br/>HNSW k-NN, distance-gated"]
        qK["Keyword arm<br/>BM25 over term-matching chunks"]
        gate["Document relevance gate<br/>dominance (best doc + ties)<br/>+ title-match override"]
        fuse["Reciprocal Rank Fusion<br/>+ per-document cap"]
        rerankStep["Reranker (≥8 GB tiers)<br/>cross-encoder re-score top pool"]
        topk["Top-k chunks → grounding block<br/>(RagContextFormatter, size-capped)"]
        llm["LocalAiEngine<br/>(stateful KV; grounding re-flushed per turn)"]
        ans["Answer + citations"]

        q0 --> q1 --> qV
        q0 --> qK
        qV --> gate
        qK --> gate
        gate --> fuse --> rerankStep --> topk --> llm --> ans
    end

    i5 -. queried by .-> qV
    i5 -. queried by .-> qK

    classDef ing fill:#eef6ee,stroke:#7FA980,color:#1C1B1A;
    classDef ret fill:#f5f3ef,stroke:#9a8f7a,color:#1C1B1A;
    class i1,i2,i3,i4,i5 ing;
    class q0,q1,qV,qK,gate,fuse,rerankStep,topk,llm,ans ret;
```

A few design decisions worth calling out, because they came from real failure modes
(see [`_session/material/blog-embedding-enhancements.md`](../_session/material/blog-embedding-enhancements.md)):

- **Hybrid retrieval.** The vector arm finds semantic matches; the BM25 keyword arm
  recovers exact strings (names, IDs, codenames) that a small embedder ranks poorly. The
  two rankings merge with Reciprocal Rank Fusion.
- **Document relevance gate.** With several similar documents (e.g. a car, a life, and a
  health insurance policy in one project), lexical overlap on words like
  "insurance"/"premium" used to let an answer ground on the *wrong* document. The gate
  keeps only the document(s) the vector arm clearly favours, and a **title-match
  override** lets a query that names a document by its title ("car" → a *CarPolicy*
  source) ground on that document over a higher-scoring but wrong one.
- **Stateful KV, flushed grounding.** The chat session keeps a warm KV cache for flat
  time-to-first-token. But injecting a fresh grounding block every turn would accumulate
  in that cache and eventually overflow the on-device context window — so grounded turns
  re-prefill only the bounded visible transcript, flushing stale grounding.

---

## Device-tiered model selection

On-device inference must fit the phone. `EmbedderRecommendation.forDevice(ramMb)` mirrors
the LLM tiering and picks the embedder, the Matryoshka dimension, and whether to run the
reranker — keyed on effective RAM (after the OEM RAM-expansion cap). One downloaded
EmbeddingGemma model is truncated per tier; entry devices stay on the no-download,
ungated USE-Lite.

```mermaid
flowchart LR
    ram{"effective RAM"}
    ram -->|"≥ 10 GB"| t4["EmbeddingGemma @512<br/>+ reranker"]
    ram -->|"8–10 GB"| t3["EmbeddingGemma @256<br/>+ reranker"]
    ram -->|"6–8 GB"| t2["EmbeddingGemma @256"]
    ram -->|"< 6 GB"| t1["USE-Lite @100<br/>(no download, ungated)"]

    classDef n fill:#f5f3ef,stroke:#9a8f7a,color:#1C1B1A;
    class t1,t2,t3,t4 n;
```

The same recommendation surfaces in the Models screen as a *Recommended* badge, and the
download flow pulls the model plus its companions (the ONNX external-data weights blob
and the tokenizer) on-device — gated models reuse the Hugging Face token flow.

---

## Visualising growth

This file is the intentional, reviewed view of the architecture — kept in `docs/` so it
evolves alongside the code (transparent-dev model). For the *organic* view of how the
codebase grew over time, the repository history can be rendered with
[Gource](https://gource.io/) (an animated, file-by-file visualisation of the git log).
See [`docs/gource.md`](gource.md) for the recipe used to produce the growth clip.
