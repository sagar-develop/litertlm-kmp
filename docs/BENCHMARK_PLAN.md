# Benchmark harness — build plan (issue #38)

Tracks GitHub issue [#38](https://github.com/sagar-develop/litertlm-kmp/issues/38): a reproducible
in-app benchmark suite that produces public, citable on-device numbers.

## Why in-app (not a laptop script)
The model loads into the LiteRT-LM engine **inside the app process**; timing must come from the
engine's own token stream and memory from the app's own PSS. A laptop/adb script can only trigger
the run and pull the result files. So the measurement logic ships inside NativeLM as a hidden
**Benchmark screen** reachable from Settings → Developer.

## Measurement method (v1)
Per my engine notes, `ChatSession.lastTurnMetrics()` returns null in normal chat (LiteRT-LM
benchmark mode not enabled), so the harness measures **wall-clock stream timing** — the proven
method — and *additionally* records `lastTurnMetrics()` whenever the engine does populate it.

For each already-downloaded LLM (`ModelRole.LLM_PRIMARY`, fully on disk):
1. `engineHolder.release()` then time `initializeEngine(path, supportsVision)` → **cold load ms**.
2. Sample PSS after load → **loadedPssMb**; track **peakPssMb** across the whole run.
3. `openChatSession(emptyList(), systemInstruction, temperature)`; await `SessionState.Ready`.
4. For each canonical prompt (short / general / long-context):
   - 1 warm-up (discarded) + N measured runs (default 3, selectable 5).
   - Per run, collect `sendTurn(...)`:
     - `ttftMs` = first `TokenGenerated` − send start (prefill latency).
     - `decodeTps` = (tokenCount − 1) · 1000 / (last − first token) ms (steady-state decode).
     - read `lastTurnMetrics()` after `Idle` and record if non-null.
   - Aggregate **median + p90** of TTFT and decode tok/s.
5. `session.close()`. After all models, restore the user's active model so chat keeps working.

Determinism: fixed `seed`, `temperature = 0`, fixed `maxTokens` cap (default 128) so decode runs
are bounded and steady-state is reached. TTFT/decode are compute-bound, so sampling choice barely
moves them.

Run metadata recorded (methodology, §reproducibility): app version+code, device (model, SoC,
Android, total vs effective RAM, cores, abi), battery %, charging, thermal status, config.

## Deliverables (this build)
- `sample-app/.../benchmark/BenchmarkModels.kt` — `@Serializable` report DTOs + canonical prompts + CSV.
- `sample-app/.../benchmark/BenchmarkRunner.kt` — the runner + progress/UI-state types.
- `sample-app/.../ui/benchmark/BenchmarkScreen.kt` — device card, model checklist, Run/Cancel,
  live progress, results table, Export JSON/CSV (SAF) + Share.
- Wiring: `ROUTE_BENCHMARK`, `NativeLmApp` route, `AdaptiveShell` `onOpenBenchmark`,
  Settings → "DEVELOPER" → Benchmark row (visible in release — release perf is the point).
- VM: `benchmarkState`, `runBenchmark/cancelBenchmark/exportBenchmark*`, a `BenchmarkRunner`.

## Device matrix (the two connected devices)
| Device | Type | SoC | RAM (MemTotal) | Android |
|---|---|---|---|---|
| Xiaomi Pad 6 (`23043RP34I`) | tablet | SM8250 = Snapdragon 870 | 7.43 GiB | 14 (SDK 34) |
| Samsung SM-M558B (Galaxy M55s) | phone | SM7450 = Snapdragon 7 Gen 1 | 6.90 GiB | 16 (SDK 36) |

For measured runs: wireless adb (`adb tcpip`) so devices run on battery (unplugged) per methodology.

## Next phase (documented, not silently dropped)
- **Embedding throughput** + **end-to-end RAG retrieve() latency** (needs the embedder + an indexed
  project; `RagHolder.retrieve` is the entry point).
- **Energy / sustained thermal soak** (decode tok/s over 5–10 min; battery drain per 1k tokens).
- `BENCHMARKS.md` generated from the JSON + README "Performance" section + urjalabs results page.
