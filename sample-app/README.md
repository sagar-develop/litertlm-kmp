# NativeLM

**Local NotebookLM with general chat — on-device, private, fast.**

Your documents and conversations never leave your phone.

NativeLM is the reference product built on top of [litertlm-kmp](../README.md).
It runs Gemma-family models fully on-device: no account, no API key, no cloud
bill, and no upload. The same repo ships the engine (`lib/`) and the product
(`sample-app/`) — the product showcases the engine; the engine powers the
product.

> Open source · AGPL-3.0 · No telemetry · No account required · No upload.

---

## What it does today

- **General chat** — a capable local LLM with a clean, Gemini-style chat
  surface ("How can I help you today?").
- **Multi-turn memory** — the assistant remembers earlier turns within a
  conversation (app-side context, capped to a token budget).
- **Rich Markdown rendering** — headings, lists, code blocks, links, block
  quotes, and tables render as formatted text in chat bubbles (dependency-free
  renderer, JetBrains Mono for code).
- **Streaming with live metrics** — token-by-token output via
  `LocalAiEngine.generateStream(...)`, with quiet TTFT / tokens-per-second
  telemetry while generating.
- **Stop / New chat / Copy** — interrupt generation mid-stream, start a fresh
  thread, long-press any bubble to copy.
- **Conversation persistence** — the current conversation survives app restart.
- **Model management** — download models on demand and switch the active model
  from inside the app. No model is bundled in the APK.
- **Bring your own token** — gated models download using **your** Hugging Face
  token, pasted into the app and stored encrypted on-device. Never Firebase,
  never a backend of ours.

Document chat (local RAG) and image input are on the roadmap — the embedding
model is already downloadable in preparation.

## First run vs. later launches

- **First run:** Splash → Onboarding → Model Management (download an LLM with
  your HF token, set it active) → Chat.
- **Every later launch:** the previously-selected model **auto-loads from disk
  into memory** — no download, no network. Works fully offline.

## Models

NativeLM ships a small catalog ([`NativeLmModelCatalog.kt`](src/main/java/com/sagar/litertlmsample/llm/NativeLmModelCatalog.kt))
pointing at verified Hugging Face / Google-hosted artifacts:

| Model | Role | Size | Min RAM | Auth |
|---|---|---|---|---|
| Gemma 4 E2B (`.litertlm`) | Language model | ~2.6 GB | 6 GB | HF token |
| Gemma 4 E4B (`.litertlm`) | Language model | ~3.7 GB | 10 GB | HF token |
| Universal Sentence Encoder | Embedding (for upcoming doc chat) | ~6 MB | — | none |

Downloads are resumable with SHA-256 validation, handled by the engine's
`KtorModelManager`. A `401/403` from Hugging Face surfaces as a clear
"token missing/invalid" error rather than a silent failure.

### Getting a Hugging Face token

1. Create a free account at [huggingface.co](https://huggingface.co).
2. Settings → Access Tokens → create a **read** token.
3. Paste it into NativeLM's Model Management screen (eye icon to reveal). It is
   stored with `EncryptedSharedPreferences` and never leaves the device.

## Privacy

- **No telemetry.** Enforced in code, not just promised.
- **No account.** Nothing to sign up for.
- **No upload.** Prompts, responses, and documents stay on-device.
- The only network calls are model downloads you explicitly trigger.

## Build & run

```powershell
# JDK ships with Android Studio
$env:JAVA_HOME = "C:\Users\shefa\AppData\Local\Programs\Android Studio\jbr"
.\gradlew :sample-app:assembleDebug
```

- **Min SDK 24**, target/compile SDK 36.
- Requires a device with **6 GB+ RAM** for the E2B model (10 GB+ for E4B).
- Verified on Realme CPH2723 (Android 16, 8 GB).

## Design

NativeLM does not share the engine README's dry/technical tone — it talks to end
users. The identity is restrained, Linear/Bear-like:

- **Accent:** sage-green `#7FA980`
- **Canvas:** off-white `#FAF9F6` (light) / warm-dark `#1C1B1A` (dark)
- **Type:** Inter (body/UI) + JetBrains Mono (code/citations/metrics)
- No emojis, no glowing borders, no "AI sparkles".

## License

AGPL-3.0-or-later, same as the engine. A commercial license for the engine is
available — see [`COMMERCIAL.md`](../COMMERCIAL.md).
