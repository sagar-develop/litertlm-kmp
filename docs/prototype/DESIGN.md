# NativeLM — Adaptive UI Redesign

A premium‑minimal refresh of the existing identity, plus a full multi‑device
(phone / foldable / tablet) adaptive layout. Nothing about the brand changes —
this **elevates** what's already locked in `Color.kt` / `Type.kt`.

> **Visualize it:** open [`index.html`](./index.html) in any browser. Use the
> top bar to switch **Device** (Phone · Foldable · Tablet) and **Theme**
> (Light · Dark), and the left list to jump between screens. The same screen
> reflows across breakpoints — that *is* the adaptive system.

This is a design artifact for sign‑off. Once approved it gets implemented in
Compose (Material3 + `androidx.compose.material3.adaptive`).

---

## 1. Design language (elevated, not replaced)

| Token | Light | Dark | Notes |
|---|---|---|---|
| Brand / primary | `#7FA980` | `#7FA980` | the one sage accent, used sparingly |
| Canvas (`background`) | `#FAF9F6` | `#1C1B1A` | warm |
| `surface` | `#FFFFFF` | `#232220` | cards |
| `surfaceVariant` | `#EFEDE8` | `#2C2A27` | fills, chips |
| `onBackground` | `#1C1B1A` | `#ECEAE4` | |
| `onSurfaceVariant` | `#6B6862` | `#A8A49C` | secondary text |
| `outline` | `#DAD8D2` | `#3A3833` | hairlines |
| `primaryContainer` | `#E9F0E9` | `#2E3B2E` | user bubble, selected, active badge |
| `error` | `#BA4A42` | `#E5938B` | restrained |

What "elevated" means concretely:

- **Hierarchy** — tighter, more deliberate type scale; titles get weight, not size.
- **Spacing** — strict **8pt system** (4/8/12/16/20/24/32/40/48).
- **Surfaces** — soft warm elevation (3 levels), `1px` hairline borders on cards
  instead of heavy shadows. Radii: `sm 10 · md 14 · lg 20 · xl 28 · pill`.
- **Mono discipline** — JetBrains Mono stays reserved for technical metadata
  (file names, sizes, `tok/s`, RAM, page numbers, version, license, codes).
- **Motion** — `cubic-bezier(.2,.7,.2,1)`; 150–180ms for state, ~320–420ms for
  layout/drawer transitions. Subtle, never bouncy.
- **Empty/loading states** — every list has a considered empty state and an
  inline progress affordance (see Sources, Studio, Models).

---

## 2. Breakpoints & navigation

Following Material 3 adaptive window size classes.

| Class | Width | Nav | Layout |
|---|---|---|---|
| **Compact** (phone) | `< 600dp` | Top bar **hamburger** → modal drawer; single pane | one screen at a time |
| **Medium** (foldable / small tablet) | `600–839dp` | **Navigation rail** (left, icon+label) | single detail pane, wider content with max‑width |
| **Expanded** (tablet / unfolded) | `≥ 840dp` | Navigation rail **+** persistent **list pane** | **list‑detail two‑pane** |

Destinations are identical across all sizes: **Chat · Models · Sources ·
Studio · Settings**. Only their *presentation* changes (drawer item → rail
item → rail item). Content panes cap at ~720–760dp and center, so text never
runs full‑width on a tablet.

Compose mapping:
- `currentWindowAdaptiveInfo()` → `WindowWidthSizeClass`.
- `NavigationSuiteScaffold` for the Chat/Models/Sources/Studio/Settings shell
  (auto drawer ↔ rail ↔ rail).
- `ListDetailPaneScaffold` for the two‑pane screens below.

---

## 3. Per‑screen adaptive behavior

### Chat (the hub)
- **Compact:** top bar (`☰` · title + `model · on‑device` · `EN` · Studio ·
  New chat) → message thread → composer. Conversations/projects live in the
  **modal drawer**.
- **Medium:** rail replaces the hamburger; thread + composer fill the pane.
- **Expanded:** **two‑pane** — left *Conversations* list (search, chats,
  Projects, New chat) │ right *thread*. Selecting a chat updates the detail
  pane in place; no navigation. Composer pinned to the detail pane.
- Message design: role label (`YOU` / `NATIVELM`), user bubble in
  `primaryContainer`, assistant rendered as markdown (code in mono), streaming
  = 3‑dot pulse, **Sources (N)** expander with citation chips
  (`title · p.N`) that open the PDF viewer.

### Models
- Single scroll; on Medium/Expanded the content column caps + centers (cards
  don't stretch). Sections: **Recommended → Document/RAG → Audio → Advanced
  (Hugging Face)** with the collapsible token card. Sticky **Continue to chat**
  bottom bar when a model is active. Card = name + `Recommended`/`Active` badge,
  mono metadata line, modality chips (`Text`/`Image`), inline download progress.

### Sources (Documents)
- Compact: list with full‑width **Import** button and inline import‑status card.
- Expanded: this becomes the **list pane** companion to a document preview
  (Sources list │ PDF/page preview), so adding/reading sources is one view.

### Studio
- Responsive grid: **2 columns** (compact) → **3 columns** (medium/expanded)
  for the Create cards. Audio hero cards full‑width. Outputs list below.
- Expanded: artifact **list │ artifact viewer** two‑pane (pick an output on the
  left, read it on the right) — FAQ / Key Topics / Study Guide / Timeline /
  Mind Map / Audio / Podcast viewers as specified today.

### Settings
- Grouped cards (Appearance / Models / Language / Security / Data & backup /
  About). Content column caps + centers on wide screens. Segmented Theme
  control, switches, value rows, mono for Version/License. Footer:
  `No telemetry · No account · No upload`.

### PDF viewer
- Cited‑passage callout (`primaryContainer`) → zoom/pan page → page bar
  (`Page N of M`, mono). On Expanded it can dock beside the Sources list.

### Flows (full‑bleed, no nav shell)
- **Onboarding** (4 slides, dots, Skip/Next → Get started + terms),
  **Splash** (mark + tagline + indeterminate progress + privacy line),
  **Lock** (lock glyph, "NativeLM is locked", Unlock). These ignore the
  adaptive shell and center their content with comfortable max‑width on tablets.

---

## 4. Implementation plan (after sign‑off)

1. **Adaptive shell** — introduce `NavigationSuiteScaffold` around the five
   primary destinations; keep existing `NavController` routes for flows
   (splash/onboarding/lock/pdf).
2. **Two‑pane** — `ListDetailPaneScaffold` for Chat, Sources, Studio on
   Expanded; collapse to single pane on Compact/Medium.
3. **Theme polish** — formalize spacing/elevation/radius tokens; audit each
   screen against the prototype.
4. **Screen passes** — Chat → Models → Settings → Sources → Studio → PDF →
   flows, staged commits.

Dependencies: `androidx.compose.material3:material3-adaptive*` (navigation‑suite
+ adaptive‑layout). No new fonts, colors, or network — the zero‑network /
on‑device promise is untouched.
