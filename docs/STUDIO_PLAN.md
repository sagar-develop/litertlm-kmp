# NativeLM Studio — on-device "NotebookLM Studio" plan

Studio = generate artifacts **from** a project's sources (not Q&A): briefings,
FAQs, study guides, timelines, mind maps, audio. Fully on-device. This is what
earns the **"Local NotebookLM"** positioning.

Build order is **easiest → hardest**. Each step reuses the machinery the
previous ones established, so complexity rises gradually.

---

## Design decisions (locked for this plan)

- **Artifacts are persisted into the project** (a new `StudioArtifactEntity`:
  type, title, content/markdown, createdAt, scope), shown in a **Studio panel**
  on the project, and viewable / re-generatable / shareable. Not one-off.
- **Scope control:** every artifact can target **the whole project** or **one
  selected source** (default: whole project).
- **Generation is multi-pass and slow** → always show progress + allow cancel.
- **Tolerant parsing** for structured artifacts (reuse the lenient parser
  approach from the engine) — never hard-fail on imperfect model output; render
  what parsed, degrade gracefully.

---

## Phase 0 — Foundation (built together with Step 1, then reused by all)

The thing that makes Studio different from chat: chat retrieves **top-k** chunks;
a Studio artifact must reflect the **whole** source set, which exceeds the
on-device LLM's context window. So the core primitive is **map-reduce over a
project's sources**:

```
each source (or section) → summarize        [N LLM calls, chunked to fit context]
        → reduce: combine summaries into a context-budget "digest"
        → generate the artifact from the digest [1 LLM call, artifact-specific prompt]
```

Foundation work (one-time):
- `StudioRepository` + `StudioArtifactEntity` (ObjectBox) — persist artifacts per project.
- `SourceDigest` builder — pulls a project's `chunk.text` (already stored), runs the
  map-reduce summarizer with a token budget, with progress + cancellation.
- `StudioGenerator` — takes a digest + an artifact prompt → text/markdown, streamed.
- **Studio UI surface** — a "Studio" tab/section on the project screen: list of
  generated artifacts, a "+ Generate" menu (the artifact types), an artifact
  viewer (markdown render + share + regenerate + delete), scope selector.

**Done when:** Step 1 produces a saved, viewable Briefing for a real project.

---

## The sequence (easiest → hardest)

### 1. Briefing / Summary  ·  *easiest — establishes the foundation*
One text artifact: an executive summary of the project's sources.
- New work: Phase-0 foundation + the simplest possible prompt. Markdown render.
- Risk: low. Proves map-reduce + the Studio surface end-to-end.

### 2. FAQ  ·  *trivial increment*
"Generate likely questions + grounded answers from these sources."
- New work: a different prompt + a Q/A list rendering (expandable items).
- Reuses everything from Step 1.

### 3. Key Topics / Table of Contents  ·  *trivial increment*
Cluster the sources into themes with one-line descriptions; tap a topic → seed a
grounded chat question about it (nice tie-in to existing chat).
- New work: prompt + a chip/list render + "ask about this" hook.

### 4. Study Guide  ·  *adds light structure*
Key terms + definitions, review questions, short-answer prompts.
- New work: a sectioned artifact (terms / questions), tolerant section parsing,
  sectioned rendering. Optionally reveal-answer interactions.
- Risk: structured output reliability on a small model → tolerant parse.

### 5. Timeline  ·  *adds extraction + custom render*
Extract dated events from the sources → chronological vertical timeline.
- New work: date/event extraction prompt, date normalization, a timeline Compose
  component. **Degrade gracefully** when sources have few/no dates (offer to fall
  back to a Briefing).
- Risk: date parsing noise; many docs aren't time-ordered.

### 6. Mind Map  ·  *the visual showpiece — hardest rendering*
LLM emits a nested outline (root → branches → leaves) → interactive node graph.
- New work: structured nested output (JSON-ish) with **tolerant** parse, a
  pan/zoom node-graph Compose canvas (reuse the pinch-zoom work from the PDF
  viewer), tap-node → expand / ask.
- Risk: strict-structure reliability on-device (mitigate: outline format, lenient
  parser, cap depth); graph layout effort.

### 7. Audio Overview  ·  *headline feature — hardest, split in two*
- **7a. Read-aloud (lite):** single-voice TTS of any existing artifact (Briefing,
  etc.) via Android's **on-device** `TextToSpeech`. Easy once artifacts exist;
  good intermediate "wow" with play/pause.
- **7b. Two-host podcast (full):** LLM writes a two-speaker conversational script
  from the digest → render with two distinct on-device voices → stitched audio +
  transcript. **Hardest:** script quality on a small model + dual-voice on-device
  TTS quality gap vs NotebookLM + audio assembly/export.
- Risk: on-device TTS quality; verify it's acceptable before promising "podcast."
  7a is the safe deliverable; 7b is the stretch/bet.

---

## Cross-cutting (applies throughout)
- **Performance/UX:** multi-pass generation is seconds→minutes on big projects →
  progress, cancellation, and a scope control are mandatory, not optional.
- **Persistence:** artifacts saved per project; regenerate on demand (sources change).
- **Verify on-device** (CPH2723, release) each step — the project bar.
- **Content:** each shipped artifact type is a post + reinforces "Local NotebookLM."

## Not doing
- **Video Overview** — slide+narration video export isn't worth it on-device.
