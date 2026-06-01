# Low-end support + cross-device model catalogue — plan

Branch: `feat/model-catalogue` (off `feat/rag-v0.4`).
Scope: keep the **current LiteRT-LM implementation**; give every device tier the
best model it can run, and make the UI look right on lower-RAM, 3-button-nav,
smaller-screen devices. No MediaPipe-LLM, no engine rewrite.

## Status (this PR)

- ✅ **A1 — cross-device model catalogue.** Qwen3-0.6B / Gemma 3 1B / DeepSeek-R1
  1.5B / Gemma 4 E2B / E4B / Phi-4-mini / Qwen3-4B, per-model `supportsVision`
  gating, graduated RAM tiers, input-type chips, gated-model license popup.
  Validated on device incl. a non-Gemma model (Qwen3-0.6B) end-to-end.
- ✅ **Reasoning `<think>` span** hidden in chat (Qwen3 / DeepSeek-R1). Validated.
- ✅ **B — window insets** (status/nav bar) — shipped as a **separate PR**
  (`fix/window-insets`).
- ⏳ **A4 — failed-session UX**, **A2/A3 — KV-cache / vision tuning for E2B-on-6 GB**,
  and the **RAM-expansion-cap refinement for the flagship tier** remain open
  (below). Per-tier on-device validation of the larger models is ongoing.

## What's verified so far (on real devices)

| Device | RAM (effective) | E2B (2.6 GB) | Notes |
|---|---|---|---|
| Realme CPH2723 | ~9 GB (8 GB + expansion, capped) | ✅ loads + chats | primary dev device |
| Samsung M55 (SM-M558B) | ~7 GB (8 GB + RAM Plus) | ✅ download → activate → chat ("Hello there!"), **no OOM** | this session |
| *(pending)* genuine 4–6 GB | 4–6 GB | ❌ expected OOM | the real repro target |

Key point: **E2B is fine ≥7 GB.** The "Chat session is not ready" failure bites at
**≤6 GB**, where the model + KV cache + (unused) vision encoder don't fit. So the
two low-end workstreams are: (1) give ≤6 GB devices a model that fits, and (2) fix
the system-bar UI so it looks right on these (usually 3-button-nav) phones.

---

## Workstream A — make a model actually fit on 4–6 GB

**Problem.** Catalog offers only E2B (min 6 GB) and E4B (min 10 GB). On a real
6 GB device E2B OOMs at `createConversation`; 4 GB gets nothing. The failure is
also *silent/cryptic* (see A4).

**Options (each prototyped earlier; pull from the parked branches as reference):**

- **A1 — Small INT4 model (recommended primary).** Add **Gemma 3 1B INT4**
  (`google` or `litert-community` `Gemma3-1B-IT/gemma3-1b-it-int4.litertlm`,
  ~557 MB, text-only, Gemma-licensed → needs HF token). Fits 4 GB with headroom.
  Requires **per-model vision gating**: text-only `.litertlm` bundles fail init if
  `EngineConfig.visionBackend` is set, so `ModelDescriptor` needs a `supportsVision`
  flag threaded into `initializeEngine(modelPath, supportsVision)`.
  *Prior art: branch `feat/multi-model-int4` already implements exactly this
  (ModelDescriptor.supportsVision, engine gating, graduated RAM tiers 4/7/10 GB,
  catalog entry, 16 unit tests). It was parked, not deleted — revive it here.*

- **A2 — Size the KV cache to RAM.** Wire the existing-but-dead
  `AndroidHardwareProvider.getAdaptiveMaxTokens()` (6 GB→2048, 8 GB→4096) into
  `EngineConfig.maxNumTokens` so the cache isn't allocated at the model's full
  ~8192. Shrinks E2B's footprint; may let E2B itself squeak onto 6 GB.
  *Prior art: was committed as `af7b982`, then discarded. Recoverable from reflog.*
  Trade-off: caps context window on all tiers; needs 6 GB validation.

- **A3 — Skip the vision encoder on text-only / low-RAM.** The chat UI has no image
  input, yet the engine always loads the vision encoder (visible in logcat:
  `vision_encoder.xnnpack_cache` written on activate). Gating it off (falls out of
  A1's per-model flag) frees memory on exactly the devices that OOM.

- **A4 — Honest failure UX (do regardless).** Today a failed session leaves the
  input enabled and the user gets `[error: Chat session is not ready.]` inline.
  Track `SessionState.Failed` → block input → show "couldn't start, free memory,
  Retry". *Prior art: was in `af7b982` (discarded); re-add a clean version.*

**Recommended sequence:** A4 (safe, always-good) → A1 (the real fix; revive
`feat/multi-model-int4`) → validate on a genuine 6 GB device → only then consider
A2/A3 if E2B-on-6 GB is still wanted. Don't ship A1/A2 to a PR without 6 GB proof.

---

## Workstream B — system-bar / inset UI conflicts (confirmed this session)

**Problem.** `MainActivity` calls `enableEdgeToEdge()` but **no screen consumes
window insets** (grep: zero `statusBars`/`navigationBars`/`systemBars`/`imePadding`
in `src/main`). So content draws under the status bar (top) and the system
navigation bar (bottom). Worst on **3-button-nav** devices (taller nav bar) — which
many budget phones use.

**Confirmed on Samsung M55 (3-button nav):**
- ❌ **Models screen** — the `Scaffold { bottomBar = { Surface { Button("Continue
  to chat") } } }` has `.padding(16.dp)` but no nav-bar inset, so the button renders
  *under* the nav bar and is untappable (screenshot: `_session/demo/m55_chat.png`).
  M3's `bottomBar` slot does **not** auto-inset arbitrary content.
- ❌ **Onboarding** — root `Box(fillMaxSize)` with `.align(BottomCenter)` buttons and
  no insets → top text under status bar, Skip/Next under nav bar.
- ✅ **Chat screen** — its `Scaffold` content uses `.padding(padding)` which includes
  the bottom inset, so the input bar clears the nav bar here. (Still should add
  `imePadding()` so the keyboard doesn't cover the input on devices that don't
  auto-resize.)

**Fix (systematic insets pass):**
1. **Non-Scaffold roots** (Onboarding, Splash): wrap content in
   `Modifier.windowInsetsPadding(WindowInsets.safeDrawing)` (or
   `.systemBarsPadding()`), so top + bottom clear the bars.
2. **Custom `bottomBar` content** (Models "Continue to chat"): add
   `.navigationBarsPadding()` to the Surface/Button.
3. **Chat input**: add `.imePadding()` so the keyboard pushes the input up
   consistently.
4. **Audit the rest** (Settings, Documents/Sources) for the same patterns.
5. **Keep `TopAppBar` defaults** — M3 already insets the top bar for the status bar;
   don't double-pad.

This is low-risk and self-contained — a good first commit on this branch. Verify on
a 3-button-nav device + a gesture-nav device + a notch/punch-hole device.

---

## Workstream C — validation matrix (before any low-end PR)

| Axis | Cases |
|---|---|
| RAM | genuine 4 GB, 6 GB, 8 GB (+ RAM-expansion on/off) |
| Nav | 3-button, 2-button, gesture |
| Model | small INT4 (A1) loads+chats on 4–6 GB; E2B still loads on ≥7 GB |
| RAG | import + grounded Q&A on the small model (USE-Lite embed is RAM-cheap) |
| Insets | no content under status/nav bar; keyboard doesn't cover input |
| Tiering | Models screen offers only the models the device can actually run |

## Open questions for the author
- Do we *want* E2B to run on 6 GB (pursue A2/A3), or is "6 GB → small INT4 model"
  (A1 only) the policy? Earlier call leaned A1 ("we will provide int4 model").
- Bundle/host the 1B INT4 ourselves (Firebase, like LearnLM) to avoid the HF
  Gemma-license gate for low-end users, or keep the token flow?
- Minimum supported RAM floor — 4 GB, or 3 GB with the smallest model?
