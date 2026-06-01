# NativeLM

**Local AI — private, on-device chat for Android.**

Your documents and conversations never leave your phone. Ask questions grounded
in your own PDFs and notes — an on-device "NotebookLM" — or just chat.

NativeLM is the reference product built on top of [litertlm-kmp](../README.md).
It runs Gemma-family models fully on-device: no account, no API key, no cloud
bill, and no upload. The same repo ships the engine (`lib/`) and the product
(`sample-app/`) — the product showcases the engine; the engine powers the
product.

> Open source · AGPL-3.0 · No telemetry · No account required · No upload.

<table>
  <tr>
    <td width="25%" align="center"><b>On-device chat</b></td>
    <td width="25%" align="center"><b>Conversation history</b></td>
    <td width="25%" align="center"><b>Private by design</b></td>
    <td width="25%" align="center"><b>Bring your own model</b></td>
  </tr>
  <tr>
    <td><img src="../docs/screenshots/shot_chat.png" alt="On-device streaming chat"></td>
    <td><img src="../docs/screenshots/shot_drawer.png" alt="Conversation history drawer"></td>
    <td><img src="../docs/screenshots/shot_onboarding.png" alt="Private by design onboarding"></td>
    <td><img src="../docs/screenshots/shot_models.png" alt="Model management"></td>
  </tr>
</table>

---

## What it does today

- **General chat** — a capable local LLM with a clean, Gemini-style chat
  surface ("How can I help you today?").
- **Projects (on-device NotebookLM)** — create a project, add sources (PDF or
  plain text, or save any answer into it), and the project's chat answers *only*
  from those sources. Documents are extracted, chunked, embedded (USE-Lite), and
  stored in an on-device vector index (ObjectBox HNSW) — nothing is uploaded.
- **Grounded answers with citations** — project retrieval is scoped to that
  project's sources and relevance-gated, so each answer cites where it came from,
  and off-topic questions fall back to ordinary chat instead of citing unrelated
  text. The default (project-less) chat stays pure general chat.
- **Multi-turn memory (KV-cache sessions)** — the assistant remembers earlier
  turns via a persistent engine session (`LocalAiEngine.openChatSession`), so
  history is never re-sent: prefill is paid per new message, not re-paid for the
  whole conversation. Time-to-first-token stays flat as the chat grows.
- **Conversation history** — a Gemini-style drawer of past conversations
  (ObjectBox), each with its own context. Switching a conversation re-prefills
  its history once ("Building understanding…"), then reuses the cache.
  Conversations are auto-titled by the model, renamable, and deletable.
- **Rich Markdown rendering** — headings, lists, code blocks, links, block
  quotes, and tables render as formatted text in chat bubbles (dependency-free
  renderer, JetBrains Mono for code).
- **Streaming with live metrics** — token-by-token output, with quiet TTFT /
  tokens-per-second telemetry while generating.
- **Stop / New chat / Copy** — Stop truly interrupts the native decode loop
  (not just the UI); start a fresh thread; long-press any bubble to copy.
- **Persistence** — all conversations survive app restart.
- **Model management** — download models on demand and switch the active model
  from inside the app. No model is bundled in the APK.
- **Bring your own token** — gated models download using **your** Hugging Face
  token, pasted into the app and stored encrypted on-device. Never Firebase,
  never a backend of ours.

Image input (multimodal chat) is on the roadmap; the engine already supports it
(`descriptor.supportsVision`), it's just not surfaced in the chat UI yet.

## First run vs. later launches

- **First run:** Splash → Onboarding → Model Management (download an LLM with
  your HF token, set it active) → Chat.
- **Every later launch:** the previously-selected model **auto-loads from disk
  into memory** — no download, no network. Works fully offline.

## Models

NativeLM ships a small catalog ([`NativeLmModelCatalog.kt`](src/main/java/com/nativelm/app/llm/NativeLmModelCatalog.kt))
pointing at verified Hugging Face / Google-hosted artifacts:

| Model | Role | Size | Min RAM | Auth |
|---|---|---|---|---|
| Gemma 4 E2B (`.litertlm`) | Language model | ~2.6 GB | 6 GB | HF token |
| Gemma 4 E4B (`.litertlm`) | Language model | ~3.7 GB | 10 GB | HF token |
| Universal Sentence Encoder | Embedding (for Projects / document RAG) | ~6 MB | — | none |

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
