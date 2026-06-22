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

## Results

Decode = steady-state tokens/sec (the number users feel as "typing speed"). TTFT = time to first
token (prefill latency). Each cell is the **median of 3 runs** after 1 warm-up; generation is
bounded to 128 tokens per run for a stable rate. Temperature 0, fixed seed.

### Samsung SM-M558B — Snapdragon 7 Gen 1

| Model | Size | Cold load | Peak RAM | Decode (general) | Decode (long-ctx) | TTFT (short) | TTFT (long-ctx) |
|---|---|---|---|---|---|---|---|
| Qwen3 0.6B | 586 MB | 1066 ms | 1705 MB | 3.1 tok/s | 3.0 tok/s | 3472 ms | 15086 ms |
| Gemma 4 E2B | 2468 MB | 1814 ms | 2025 MB | 5.8 tok/s | 5.6 tok/s | 1686 ms | 15011 ms |

### Xiaomi Pad 6 — Snapdragon 870

| Model | Size | Cold load | Peak RAM | Decode (general) | Decode (long-ctx) | TTFT (short) | TTFT (long-ctx) |
|---|---|---|---|---|---|---|---|
| Qwen3 0.6B | 586 MB | 1275 ms | 1817 MB | 4.4 tok/s | 4.4 tok/s | 4603 ms | 18534 ms |
| DeepSeek-R1 Distill 1.5B | 1749 MB | 2537 ms | 2166 MB | 6.4 tok/s | 6.4 tok/s | 1277 ms | 20499 ms |

Run-to-run variance is small (p90 ≈ median in the raw JSON), so these are stable medians, not
lucky single shots.

## What the numbers say

- **Decode is context-independent; TTFT is not.** Decode tok/s is nearly identical on the short,
  general, and long-context prompts (e.g. tablet DeepSeek: 6.4 across all three). TTFT, by
  contrast, jumps from ~1.3 s to **15–20 s** on the ~600-word long-context prompt — prefill cost
  grows with input length and is paid before the first token. This is the single most important
  thing to know about on-device long-document Q&A.
- **Smaller isn't always faster.** Qwen3 0.6B (the smallest model) is the *slowest* decoder on
  both devices (3–4.5 tok/s) — slower than the 4× larger Gemma 4 E2B and DeepSeek 1.5B. The
  Qwen3 0.6B LiteRT-LM community build appears unoptimised relative to the Gemma/DeepSeek bundles;
  raw parameter count is a poor predictor of on-device speed.
- **Memory is modest.** Peak process PSS stays ~1.7–2.2 GB even for the 2.6 GB Gemma bundle —
  comfortably inside an 8 GB device's budget alongside the OS.
- **SoC class scales as expected.** Gemma 4 E2B at ~6 tok/s on a Snapdragon 7 Gen 1 lines up with
  the ~24 tok/s measured on a Snapdragon 8 Elite flagship — roughly the ~3–4× CPU gap between those
  tiers.

## Methodology

- In-app harness loads each model into the real LiteRT-LM engine and times its actual token
  stream — TTFT = first token minus send; decode tok/s = `(tokens − 1) / (last − first token)`.
  Peak RAM is the process PSS sampled off the timing-critical path. The engine's own
  `getBenchmarkInfo()` is recorded too but currently returns null, so all figures here are
  app-measured wall-clock.
- Canonical prompt set (committed in code): **short** (one-word answer, a minimum-prefill probe),
  **general** (a ~20-word instruction), **long_context** (a ~600-word passage + summarise).
- 1 warm-up discarded + 3 measured runs → median + p90. Determinism: temperature 0, fixed seed.
- Each run records device, SoC, total/effective RAM, app version + git SHA, battery %, charging
  state, and thermal status.

> **Caveat for this run:** both devices were **plugged in (charging)** and at "none" thermal
> state at the start. Charging adds heat, and Gemma's decode drifted from 6.7 tok/s (cold) to
> 5.8 tok/s after the slower Qwen model ran first — mild throttling. For the cleanest published
> figures the harness recommends airplane mode + unplugged + cooled; a battery-only pass over
> wireless adb is a planned refinement.

## Reproduce

1. Install a release build of NativeLM and download at least one model (Settings → Models).
2. Settings → **Developer → Benchmark**, pick models + runs, tap **Run benchmark**.
3. **Export JSON / CSV** writes a file you can share. The committed files under `benchmarks/` are
   raw, unedited exports.

## Not yet measured (next phase)

- **Embedding throughput** + **end-to-end RAG `retrieve()` latency** (needs the embedder + an
  indexed project).
- **Energy / sustained thermal soak** — decode tok/s over a 5–10 min run; battery drain per 1000
  tokens.
- **Prefill tokens/sec** as a first-class number (today inferred from TTFT vs prompt length).
- **GPU/NPU backends** ([#31](https://github.com/sagar-develop/litertlm-kmp/issues/31)) — the
  harness is reusable to measure those gains against this CPU baseline.
