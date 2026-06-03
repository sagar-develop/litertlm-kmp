# NativeLM — multilingual output + cross-lingual RAG (plan)

Bring the **multilingual capability already proven in the Astro app** (LearnLM,
`pivot/astrology-v1`) into NativeLM: let users get answers, summaries, and Studio
artifacts in their own language — entirely on-device, no translation model.

## The approach (port from Astro): prompt-only, no NLLB
The Astro app shipped 10 languages with **zero translation model** — Gemma generates
natively in the target language, steered by a single prompt directive:

```kotlin
append("Output language: ").append(localeDescriptor(locale)).append('.')
```

The one gotcha it solved: for weak-coverage scripts (Kannada, Punjabi) Gemma silently
falls back to Devanagari/Hindi. Fix = an emphatic **strict-script directive**:

```kotlin
"kn" -> "Kannada. CRITICAL: output STRICTLY in Kannada script (ಕನ್ನಡ ಲಿಪಿ). NEVER use Devanagari / Hindi script — that would be wrong"
"pa" -> "Punjabi. CRITICAL: output STRICTLY in Gurmukhi script (ਗੁਰਮੁਖੀ ਲਿਪੀ). NEVER use Devanagari / Hindi script — that would be wrong"
```

Payoff (from the Astro commit `b88db15`): all 10 languages native via Gemma alone —
**no 600 MB NLLB download, no 2–5 s/response translation latency.**

## The killer feature for NativeLM: cross-lingual RAG
The documents stay **English**; the user gets the **answer in Hindi/Tamil/Bengali**.
Gemma reads the English context and responds in the target language. *"Ask in your
language, about your English documents"* — a genuinely differentiated India-market hook,
and it falls out of the same one-line directive.

## Where it lives: promote into the engine (`:lib`)
Put `Language` (enum) + `localeDescriptor()` (the strict-script table) in the **engine**
as a shared utility, so **NativeLM, Curio, and the Astro app all reuse one battle-tested
language layer.** This is a textbook `ENGINE_SYNC` item.

## Components (NativeLM side)
1. **`Language` enum** — port `AstroLanguage` (English + 9 Indian langs; BCP-47 codes +
   native names). Lives in `:lib`.
2. **`localeDescriptor(code)`** — the directive table incl. strict-script lines. In `:lib`.
3. **Language preference** — DataStore (mirror the `themeMode` / `appLockEnabled` pattern
   in `AppPreferences`).
4. **Prompt injection** — append `"Output language: <descriptor>."` via a `withLanguage()`
   helper to:
   - the chat turn prompt (grounded + ungrounded) ✅ v1,
   - the conversation-title generation ✅ v1,
   - **Studio generation prompts — deferred to a follow-up.** Only the *final* artifact
     prompt should carry the directive (not the map/reduce intermediates), and the
     structured artifacts (Mind Map outline, Timeline `### date`, Podcast `Alex:`/`Sam:`)
     have parsers that key off English markers — so Studio needs per-artifact care
     (apply to prose artifacts; careful directive for structured ones). Clean follow-up,
     out of the "½-day win" scope.
5. **Language selector — placement (decision):**
   - **Settings** = the source of truth (a "Language" row → a sheet of cards showing each
     language in its **native script**, like the Astro `LanguageSelect`), persisted in
     DataStore. Set-and-forget.
   - **Chat top bar chip** = a compact current-language chip (e.g. `हि ▾`) for quick
     switching mid-use, bound to the same pref. More discoverable for a setting that
     changes *every* answer.
   - **Recommendation: both** — Settings as the canonical home + the top-bar chip for
     reach/quick-switch (both read/write one DataStore value). *Pending confirmation —
     could ship Settings-only first and add the chip later.*

## Scope boundary: LLM-output language ≠ full UI localization
- **In scope (the win, ~0.5–1 day):** the *AI's output* language — answers, summaries,
  Studio artifacts. Pure prompt work, huge value, tiny effort.
- **Separate / later:** localizing NativeLM's own **UI strings** (`values-<code>/`
  resources) — a larger, lower-urgency effort. NativeLM's UI is simple and mostly English;
  the differentiator is the AI answering in your language, not the button labels. Defer.

## Caveats to carry over
- **Strict-script directive** is required for kn/pa (and worth testing te/gu/ml).
- **Canonical-term anchoring** — domain proper nouns can transliterate oddly. Lower
  priority for NativeLM's general/doc use than for astrology jargon; revisit only if
  specific terms come out wrong.

## Effort & verification
- **Effort:** ~0.5–1 day for LLM-output language across chat + RAG + Studio + Settings.
- **Verify on-device:** ask a question and request Hindi/Tamil/Kannada output; confirm
  fluent native-script answers (esp. the strict-script ones); confirm cross-lingual RAG
  (English source → Hindi answer); confirm Studio artifacts honor the language.

## Synergy
Pairs with **on-device voice input** (`docs/VOICE_INPUT_PLAN.md`): typing Devanagari/Tamil
is painful, so **speaking** the question is the natural input — speak Hindi → answer in
Hindi about English docs. Voice-in + multilingual-out is the India-market combo.
