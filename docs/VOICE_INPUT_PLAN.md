# NativeLM — on-device voice input (plan)

Let users **dictate** their question instead of typing — fully on-device, offline, no
cloud. Pairs with the multilingual feature (`docs/LANGUAGE_PLAN.md`): speak Hindi → get a
Hindi answer about your English documents.

## Why it matters (more than it looks)
- **Typing Indian-language scripts is painful** (Devanagari/Tamil keyboards are slow and
  error-prone). **Voice is the natural input** for non-English users — arguably the single
  biggest UX unlock for the India market.
- **Accessibility + speed** — hands-free, faster than typing on mobile, helps low-literacy
  and motor-impaired users.
- **Synergy:** voice-in + multilingual-out = a complete non-English experience. And for
  **Curio (kids), voice input is essential** — kids can't type — so building it in the
  engine benefits both products.

## Options (and why one wins)
| Option | Offline? | Multilingual | Brand fit | Cost |
|---|---|---|---|---|
| **Android `SpeechRecognizer`** (on-device, API 33+, `EXTRA_PREFER_OFFLINE`) | Patchy pre-13; Google component | Limited | 🔴 **Risk** — Google component may phone home (same class as the ML Kit telemetry leak); must prove truly offline | Low |
| **Whisper on-device** (whisper.cpp JNI **or** Whisper-via-LiteRT) | ✅ Fully | ✅ 90+ languages | ✅✅ On-device, no Google, matches the engine ethos | Native integration + model download |
| **Vosk** | ✅ | Per-language models | 🟢 OK | Lower quality; per-language downloads |

**Recommendation: Whisper on-device.** It's fully offline (no Google, protects the
zero-telemetry promise), multilingual (perfectly matches the language feature), and
consistent with "everything runs on your device." `SpeechRecognizer` is tempting as a fast
MVP but its phone-home risk is exactly the wound we just closed for v0.6.1 — not worth it.

Implementation note: prefer **Whisper-via-LiteRT (TFLite)** if a good converted model is
available, to reuse the engine's existing LiteRT runtime; otherwise **whisper.cpp via JNI**
(mature, well-trodden). Start with **Whisper-tiny (~40 MB)** or **base (~75 MB)**,
multilingual variant.

## Architecture
```
[mic button] → RECORD_AUDIO → AudioRecord (16 kHz mono PCM)
   → Whisper inference (on-device) → transcript
   → populate the chat input (user reviews, then sends — or auto-send)
```
- **Mic button** in the chat input bar; **push-to-talk** (hold to record) or tap-to-toggle;
  show a listening/waveform state and a "transcribing…" spinner.
- **Transcript → input field** (don't auto-send by default — let the user glance/edit,
  especially for non-English where ASR may slip). Optional "auto-send" setting.
- **Reuse the engine plumbing:** the Ktor **download manager** (resume + SHA-256) fetches
  the Whisper model on first use; the **hardware-tier provider** picks tiny vs base.

## Privacy
- Audio is captured and transcribed **entirely on-device**; **nothing leaves the phone**.
- `RECORD_AUDIO` used **only during active dictation** (no background listening).
- Extends the zero-telemetry promise to voice — and must be literally true (re-check
  logcat for any phone-home, the v0.6.1 lesson).

## Model catalogue: Whisper as an "Audio" category
The Whisper model must be a **first-class catalog entry**, not a hard-coded blob — so it
downloads on demand, tiers by device, and shows in the model-management UI under its own
category. Today the engine catalog (`lib/.../ModelCatalog.kt`) has:

```kotlin
enum class ModelRole { LLM_PRIMARY, EMBEDDING }
enum class ModelFormat { LITERTLM, MEDIAPIPE_TEXT_EMBEDDER }
```

**Change:**
- Add **`ModelRole.SPEECH_TO_TEXT`** (the "Audio / Voice" category).
- Add a **`ModelFormat`** for Whisper — `WHISPER_TFLITE` (if going via LiteRT) or
  `WHISPER_GGML` (if via whisper.cpp); pick when the runtime is chosen.
- Add Whisper **descriptors** (tiny ~40 MB / base ~75 MB, multilingual) with
  `role = SPEECH_TO_TEXT`, proper `minDeviceRamMb` tiering, and SHA-256.
- The **models screen groups by role** → an **"Audio"** section appears alongside the LLM
  and Embedding models. Voice input is gated on its model being present (download-on-first-
  use), exactly like the LLM's first-launch download — reuse `ModelManager.downloadModel`.

This keeps the catalog the single source of truth (per its own design comment) and makes
"Audio model" a real category users can see, download, and manage.

## Where it lives: engine (`:lib`) → Curio inherits
Build the speech-to-text capability as a shared engine module so **Curio reuses it** (kids
*need* voice — they can't type). Strong cross-product synergy; one integration, two apps.

## UI / UX
- Mic button left of (or replacing) the send button when the field is empty.
- First tap → RECORD_AUDIO permission rationale ("voice stays on your device").
- States: idle → listening (waveform) → transcribing → text in field.
- Honor the selected output language as a Whisper **language hint** (better accuracy) when
  the multilingual feature is on.

## Effort, risks, verification
- **Effort:** ~1–2 weeks — native Whisper integration is the cost; audio capture + UI are
  straightforward.
- **Risks:** Whisper latency on weak devices (mitigate: tiny model + short utterances +
  the "transcribing…" affordance); +40–75 MB download; native build complexity (whisper.cpp
  JNI **or** sourcing a maintained Whisper-LiteRT model).
- **Verify on-device:** dictate English + Hindi; check transcription accuracy and that the
  full loop (speak → transcribe → answer) works; confirm via logcat that **no audio/data
  leaves the device**.

## Sequencing recommendation
Ship the **language feature first** (`docs/LANGUAGE_PLAN.md`, ~0.5–1 day, huge value, tiny
cost), then voice input (~1–2 weeks) — together they make NativeLM a genuinely
non-English-first on-device assistant, and both land in the engine for Curio to inherit.
