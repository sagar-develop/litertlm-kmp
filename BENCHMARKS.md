# Benchmarks

On-device performance of `litertlm-kmp` (the engine behind **NativeLM**) on real
mid-range/older Android hardware — not MacBooks or flagship Pixels. Numbers are produced by
the in-app **Benchmark** harness (Settings → Developer → Benchmark), exported to JSON, and
committed verbatim under [`benchmarks/`](benchmarks/). Closes the README "benchmark suite"
roadmap item ([#38](https://github.com/sagar-develop/litertlm-kmp/issues/38)) and provides the
CPU baseline for the planned GPU/NPU work ([#31](https://github.com/sagar-develop/litertlm-kmp/issues/31)).

## Device matrix

| Device | Class | SoC | RAM (MemTotal) | Android |
|---|---|---|---|---|
| Samsung SM-M558B (Galaxy M55s) | phone, mid-range | Snapdragon 7 Gen 1 (SM7450) | 7062 MB | 16 |
| Xiaomi Pad 6 (23043RP34I) | tablet | Snapdragon 870 (SM8250) | 7610 MB | 14 |

Both are 8 GB-class, arm64-v8a, 8 cores. The engine runs LiteRT-LM on the **CPU backend** (6
threads); GPU/NPU are not yet enabled ([#31](https://github.com/sagar-develop/litertlm-kmp/issues/31)).

## LLM results

Decode = steady-state tokens/sec (the number users feel as "typing speed"). TTFT = time to first
token (prefill latency). Each cell is the **median of 3 runs** after 1 warm-up; generation is
bounded to 128 tokens per run for a stable rate. Temperature 0, fixed seed.

### Samsung SM-M558B — Snapdragon 7 Gen 1

| Model | Size | Cold load | Peak RAM | Decode (general) | Decode (long-ctx) | TTFT (short) | TTFT (long-ctx) |
|---|---|---|---|---|---|---|---|
| Qwen3 0.6B | 586 MB | 807 ms | 1701 MB | 3.3 tok/s | 3.3 tok/s | 3082 ms | 11508 ms |
| Gemma 4 E2B | 2468 MB | 1227 ms | 2005 MB | 7.0 tok/s | 7.0 tok/s | 1272 ms | 11001 ms |

### Xiaomi Pad 6 — Snapdragon 870

| Model | Size | Cold load | Peak RAM | Decode (general) | Decode (long-ctx) | TTFT (short) | TTFT (long-ctx) |
|---|---|---|---|---|---|---|---|
| Qwen3 0.6B | 586 MB | 772 ms | 1799 MB | 4.9 tok/s | 4.8 tok/s | 2418 ms | 9726 ms |
| DeepSeek-R1 Distill 1.5B | 1749 MB | 1103 ms | 2170 MB | 7.0 tok/s | 7.0 tok/s | 819 ms | 10805 ms |

Run-to-run variance within a session is small (p90 ≈ median in the raw JSON). Across sessions,
decode drifts with thermal state — an earlier warm run measured Gemma at 5.8 tok/s and long-context
TTFT at ~15 s vs the ~7 tok/s / ~11 s here on a cooled device. The cleanest figures come from an
unplugged, cooled device (see methodology).

## RAG results

End-to-end document-chat path: embedding throughput and `retrieve()` latency (embed query → HNSW
vector search → optional rerank → format) against a freshly-indexed copy of the long-context
passage. Embedder = **USE-Lite** (MediaPipe, 100-dim) — the default, ungated embedder; the harness
auto-fetches it (~6 MB) if absent.

| Device | Embedder | Embed throughput | Indexed chunks | Retrieve latency (median / p90) |
|---|---|---|---|---|
| Samsung SM-M558B | USE-Lite 100-dim | 28.7 texts/s | 5 | 35 ms / 47 ms |
| Xiaomi Pad 6 | USE-Lite 100-dim | 28.5 texts/s | 5 | 40 ms / 46 ms |

Retrieval is **not the bottleneck** in on-device RAG: a query embeds + searches the index in
~35–40 ms, near-identical across both SoCs (USE-Lite is a small, fast model). The cost of grounded
document chat is dominated by the LLM — specifically the prefill (TTFT) of the retrieved context,
not the retrieval itself.

## What the numbers say

- **Decode is context-independent; TTFT is not.** Decode tok/s is nearly identical on the short,
  general, and long-context prompts. TTFT jumps from ~1–3 s to **~10–11 s** on the ~600-word
  long-context prompt — prefill cost grows with input length and is paid before the first token.
  This is the single most important thing to know about on-device long-document Q&A.
- **Smaller isn't always faster.** Qwen3 0.6B (the smallest model) is the *slowest* decoder on
  both devices — slower than the 4× larger Gemma 4 E2B and DeepSeek 1.5B. The Qwen3 0.6B LiteRT-LM
  community build appears unoptimised; raw parameter count is a poor predictor of on-device speed.
- **Retrieval is cheap; generation is the cost.** RAG retrieve() is ~35–40 ms; the LLM that
  consumes the retrieved context is 1000× slower. Optimise the model path, not the vector search.
- **Memory is modest.** Peak process PSS stays ~1.7–2.2 GB even for the 2.6 GB Gemma bundle.
- **SoC class scales as expected.** Gemma 4 E2B at ~7 tok/s on a Snapdragon 7 Gen 1 lines up with
  ~24 tok/s on a Snapdragon 8 Elite flagship — roughly the CPU gap between those tiers.

## Methodology

- In-app harness loads each model into the real LiteRT-LM engine and times its actual token
  stream — TTFT = first token minus send; decode tok/s = `(tokens − 1) / (last − first token)`.
  Peak RAM is the process PSS sampled off the timing-critical path. The engine's own
  `getBenchmarkInfo()` is recorded too but currently returns null, so all figures here are
  app-measured wall-clock.
- Canonical prompt set (committed in code): **short** (one-word answer, a minimum-prefill probe),
  **general** (a ~20-word instruction), **long_context** (a ~600-word passage + summarise).
- RAG: embed 16 canonical sentences (×3) for throughput; index the long-context passage into a
  throwaway project and time `retrieve()` ×5 (the temp project is deleted afterward — it never
  touches the user's notebooks).
- 1 warm-up discarded + N measured runs → median + p90. Determinism: temperature 0, fixed seed.
- Each run records device, SoC, total/effective RAM, app version, battery %, charging state, and
  thermal status.

> **Caveat for these runs:** both devices were **plugged in (charging)** and at "none" thermal
> state at the start. Charging adds heat and decode drifts with it. For the cleanest published
> figures the harness recommends airplane mode + unplugged + cooled; a battery-only pass over
> wireless adb is a planned refinement.

## Reproduce

1. Install a release build of NativeLM and download at least one model (Settings → Models).
2. Settings → **Developer → Benchmark**, pick models + runs, tap **Run benchmark**.
3. **Export JSON / CSV** writes a file you can share. The committed files under `benchmarks/` are
   raw, unedited exports.

## Not yet measured (next phase)

- **Energy / sustained thermal soak** — decode tok/s over a 5–10 min run; battery drain per 1000
  tokens.
- **Prefill tokens/sec** as a first-class number (today inferred from TTFT vs prompt length).
- **EmbeddingGemma (256-dim ONNX) + reranker** RAG numbers (USE-Lite measured here).
- **GPU/NPU backends** ([#31](https://github.com/sagar-develop/litertlm-kmp/issues/31)) — the
  harness is reusable to measure those gains against this CPU baseline.
