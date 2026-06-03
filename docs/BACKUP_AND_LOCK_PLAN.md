# NativeLM — Local Backup + App-Lock plan (v0.7) + Sync direction (v0.8)

Two features, planned together because they share the privacy thesis raised by a
real prospect comment (a clinic/law-firm CEO): *"How are you handling phone loss
and backup without weakening privacy?"*

- **Feature A — Local encrypted backup** (export/import). Answers phone-loss. ✅ SHIPPED (PR #16)
- **Feature B — Biometric app-lock.** Answers the stolen-unlocked-phone case. ✅ SHIPPED (PR #15)
- **Multi-device sync (v0.8 direction).** Captured at the end of this doc — NOT built.

Guiding invariant for both: **no server, no account, no key we hold.** The user
holds the data and the key; the app holds neither. If a feature can't meet that,
it doesn't ship.

---

## Feature A — Local encrypted backup (export / import)

### Goal
A user can export everything to a single encrypted file *they* control (their
drive, their firm's storage, a USB), and restore it onto a new device with a
passphrase. The app never sees a server; the passphrase never leaves the device.

### What's in the backup
All ObjectBox data + the original source files (so citations still open after
restore). The model is **excluded** (it re-downloads; bundling ~1–3 GB is absurd).

| Payload | Source | Notes |
|---|---|---|
| Projects | `ProjectEntity` | |
| Conversations | `ConversationEntity` | |
| Messages | `MessageEntity` | includes `citationsJson` |
| Documents (metadata) | `DocumentEntity` | `localPath` rewritten on import |
| Document chunks | `DocumentChunkEntity` | text + page + **100-dim embedding** |
| Studio artifacts | `StudioArtifactEntity` | |
| Source files | `filesDir/docs/*` | the actual PDFs/images, by `localPath` |
| Preferences (optional) | `AppPreferences` | theme/selected model — nice-to-have |

**Embeddings: include them** (serialized, not re-computed on import). Re-embedding
on restore would need the embedder loaded and would be slow; shipping the float
arrays keeps restore instant and fully offline. They're the bulk of the size, so
store them as a binary blob, not JSON numbers.

### File format — `.nlmbak` (a zip)
```
backup.nlmbak  (zip container)
├─ manifest.json         # schema version, app version, createdAt, counts, KDF params, IVs
├─ data.json.enc         # AES-256-GCM ciphertext of the entity graph (projects→…→artifacts)
├─ embeddings.bin.enc    # AES-256-GCM ciphertext of packed float32 arrays (chunkId → 100 floats)
└─ files/                # original source docs, each entry AES-256-GCM encrypted
   ├─ <docId>.enc
   └─ …
```
- `schemaVersion` in the manifest gates forward-compat (reject newer, migrate older).
- `manifest.json` itself is plaintext (no secrets in it) so we can show
  "Backup from <date>, N projects" *before* asking for the passphrase.

### Crypto design (the important part)
- **Passphrase-derived key, device-independent.** User sets a passphrase at export.
  Derive a 256-bit key with **Argon2id** (preferred; or PBKDF2-HMAC-SHA256 ≥210k
  iters if we want zero native deps) using a random 16-byte salt stored in the
  manifest.
- **AES-256-GCM** per payload (random 12-byte IV each, stored alongside). GCM tag
  authenticates — a wrong passphrase or tampering fails cleanly, not silently.
- **NOT Android Keystore.** Keystore keys are device-bound and non-exportable — they
  would make the backup un-restorable on a new phone, defeating the whole point.
  Reuse `androidx.security.crypto` only as a *reference* for AES-GCM usage; the key
  here comes from the passphrase, not the Keystore.
- We hold nothing: no passphrase escrow, no recovery. Lose the passphrase = the
  backup is unreadable. State this plainly in the UI (it's a feature, not a bug).

### Export flow
1. Settings → "Back up my data" → passphrase dialog (enter twice + strength hint +
   the "no recovery" warning).
2. `ACTION_CREATE_DOCUMENT` (`application/octet-stream`, suggested name
   `nativelm-backup-YYYY-MM-DD.nlmbak`) — user picks the destination.
3. `BackupManager.export()`: read all boxes → build entity graph → JSON → encrypt;
   pack embeddings → encrypt; stream each source file → encrypt into `files/`; write
   manifest; zip to the chosen `OutputStream`. Run on `Dispatchers.IO` with progress.

### Import flow
1. Settings → "Restore from backup" → `ACTION_OPEN_DOCUMENT` (pick `.nlmbak`).
2. Read manifest (plaintext) → show summary ("3 projects, 41 sources, dated …").
3. Passphrase dialog → derive key → GCM-decrypt (wrong passphrase = clean error).
4. **Import as new** (additive, default): insert with fresh ObjectBox IDs and a
   remap table (old→new) so all FKs (`projectId`, `conversationId`, `documentId`,
   `sourceId`) are rewritten consistently. Re-materialize source files into
   `filesDir/docs/` and rewrite each `DocumentEntity.localPath`.
   - Avoids clobbering existing data. (A "replace everything" option can come later;
     additive is the safe default.)
5. Embeddings written straight back into `DocumentChunkEntity.embedding` (no
   re-embedding). HNSW index rebuilds as rows are put.

### Code touchpoints
- **New:** `data/backup/BackupManager.kt` (export/import orchestration),
  `BackupManifest.kt` (serializable), `Crypto.kt` (KDF + AES-GCM helpers),
  `BackupModels.kt` (the serializable entity-graph DTOs).
- **Repos:** add bulk read (`box.all`) + bulk insert-with-remap helpers to
  `ConversationRepository`, `ProjectRepository`, `DocumentRepository`,
  `StudioRepository`. Most already expose `boxFor(...)`.
- **UI:** two rows in `SettingsScreen.kt` ("Back up my data" / "Restore from
  backup") + a small passphrase dialog composable + progress state in the VM.
- **Deps:** none strictly required (kotlinx-serialization + javax crypto already
  available). Optional: an Argon2 lib (`signal-argon2` or `lambdapioneer/argon2kt`)
  — otherwise PBKDF2 via `javax.crypto` with no new dep.

### Edge cases / risks
- **ID remapping** is the only real complexity — get the old→new map right or
  citations/sources break. Unit-test the remap.
- **Embedding dim**: pinned at 100 (USE-Lite). Put `embeddingDim` in the manifest so
  a future 256-dim (EmbeddingGemma) backup is detectable and rejected/migrated.
- **Large backups** (many PDFs): stream, never load whole files into memory.
- **Partial write**: write to a temp then move; never leave a half-zip at the URI.

### Effort: ~2–3 focused days. (Crypto + zip + remap is the meat; UI is small.)

---

## Feature B — Biometric app-lock

### Goal
Require fingerprint/face (or device PIN fallback) to open the app, so a *stolen
but unlocked* phone doesn't expose client notes.

### Honest scope — read this
This is a **UI gate**, not at-rest encryption. ObjectBox data is **not** encrypted
on disk today (only the HF token is, via `SecureStore`). A determined attacker with
a rooted/forensic image could still read the DB file. So:
- **v0.7 ships:** biometric gate (blocks the casual/opportunistic access — the 95%
  case). Marketed honestly as "App lock," not "encrypted database."
- **Future hardening (separate, bigger):** at-rest DB encryption — ObjectBox's
  commercial encrypted store, or migrate sensitive text to an encrypted layer. Note
  it on the roadmap; do **not** imply it's done.

### Design
- **`androidx.biometric:biometric`** `BiometricPrompt` with
  `setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)` so it falls back
  to PIN/pattern/password when no biometric is enrolled — never locks the user out.
- **Lock triggers:**
  - Cold start (if lock enabled).
  - Return to foreground after a timeout (e.g. > 60s in background) — track via a
    `ProcessLifecycleOwner` observer + a `lastBackgrounded` timestamp.
- **Gate UI:** a full-screen `LockScreen` composable shown over the app content
  until auth succeeds (content not composed/hidden behind it). Re-prompt on failure;
  no bypass.

### Code touchpoints
- **New:** `ui/lock/LockScreen.kt` + `AppLockController.kt` (prompt + state),
  a `ProcessLifecycleOwner` observer (in `SampleApplication` or `MainActivity`).
- **`AppPreferences.kt`:** add `appLockEnabled: Flow<Boolean>` +
  `setAppLockEnabled(...)` (mirror the existing `KEY_*` pattern).
- **`MainActivity.kt`:** wrap content in the lock gate; check `appLockEnabled` +
  timeout before showing UI.
- **`SettingsScreen.kt`:** a "Require unlock to open" toggle (+ optional timeout
  picker). Disable the toggle with an explainer if the device has no secure lock set.
- **Deps:** add `androidx.biometric:biometric` (~`1.2.0-alpha05`) to
  `libs.versions.toml` + `sample-app/build.gradle.kts`.

### Edge cases
- No enrolled biometric **and** no device PIN → can't enable; gray out with a
  "set a screen lock first" hint.
- Don't gate the *download/onboarding* first-run (nothing to protect yet) — only
  gate once there's data / after onboarding.
- Config-change/rotation must not re-prompt (hold "unlocked this session" state).

### Effort: ~1 day. (`BiometricPrompt` is straightforward; the lifecycle/timeout
glue is the only fiddly bit.)

---

## Sequencing & content value
- **Build order:** App-lock first (1 day, self-contained, immediate trust win),
  then Backup (2–3 days, the headline). Ship both as **v0.7 — "Your data, your
  control"**.
- **Reply to the prospect now** (don't wait for the build): on-device, no account,
  so no server backup by design; the answer is a *local encrypted export you
  control* + an app lock — both coming, both privacy-preserving.
- **Content:** "Local-first backup without a server: passphrase-derived AES-GCM over
  an on-device vector DB" is a strong, specific blog post (r/androiddev,
  r/LocalLLaMA) — and a credible portfolio artifact (crypto + data-modeling depth).

## Locked decisions (confirmed 2026-06-03)
1. **KDF:** ✅ **Argon2id** (memory-hard; adds one small native dep — argon2kt /
   signal-argon2). Store salt + Argon2 params (mem/iters/parallelism) in the manifest.
2. **Import semantics:** ✅ **Additive only** for v0.7 — import always adds the backup
   as new projects with remapped IDs; never clobbers. "Replace everything" deferred.
3. **Backup scope:** ✅ **Data + preferences** — include theme + selected-model prefs
   so a restored device feels identical.
4. **App-lock framing:** ✅ **Ship biometric as "App lock" (UI gate)**; at-rest DB
   encryption explicitly deferred to a later, separate feature. Market honestly — do
   not imply the database is encrypted at rest.

Plan is implementation-ready. Suggested build order on resume: App-lock (1 day) →
Backup (2–3 days) → ship as **v0.7 "Your data, your control."**

Both shipped & verified on-device (2026-06-03): App-lock = PR #15, Backup = PR #16.

---

# Multi-device sync — v0.8 direction (NOT built; captured for later)

User asked: should NativeLM sync across a user's own devices (phone ↔ tablet)?
Answer: yes, it's the natural successor to backup — **but only if it keeps the
privacy invariant.** This section records the design constraints so we don't build
the wrong (cloud-account) version.

## The hard constraint
The headline promise is "No account. No upload. No telemetry. Your data never
leaves your phone." **Cloud-account sync (our server holds your encrypted data,
you log in) is off the table** — even E2E-encrypted, it requires accounts + our
infra + metadata, and it guts the trust that is the moat. Don't build it.

## Two on-brand approaches (pick one or both)
1. **Local peer-to-peer ("AirDrop for your knowledge base") — preferred.**
   Phone + tablet on the same Wi-Fi discover each other (mDNS / Android NSD) and
   transfer the encrypted bundle **directly, device-to-device**. No server, no
   account, nothing leaves the local network. Deepens the privacy story instead of
   diluting it; strong portfolio/content piece (discovery + E2E crypto + transport).
2. **Bring-your-own-cloud (folder sync).** The encrypted `.nlmbak` lands in a folder
   *the user* controls (their Drive / Nextcloud / NAS); the other device picks it up.
   We never run a server or hold a key. Cheapest — it's the shipped backup feature
   automated (auto-export to a watched folder + auto-import on the other side).

## The architecture jump (why this is v0.8, not a quick add)
Today's backup is a **snapshot** and import is **additive** — re-importing
duplicates everything (verified live: 5 projects → 10). That's correct for backup,
wrong for sync. True sync needs three things the current model lacks:
1. **Stable global IDs (UUIDs per entity)** so the same chat on two devices is
   recognized as the same and *merged*, not duplicated. (ObjectBox auto-increment
   ids collide across devices — exactly why import remaps them today.) **This ID-model
   migration must land first** and is the bulk of the work.
2. **Deltas** — send only what changed, not the whole bundle each time.
3. **Conflict resolution** — same entity edited on both devices → last-write-wins
   per field (simple) or CRDTs (ambitious).

## Reuse from v0.7
Not throwaway: the encrypted-container format, the Argon2/AES crypto, and the
`@Serializable` entity DTOs all carry over. **Sync = backup + (stable IDs + delta +
transport + merge).**

## Recommendation
Make **local P2P sync the v0.8 flagship.** Don't start it during the engineering
pause. Ship v0.7 (backup + lock) first as "your data, your control," then sync turns
that into "...and moves between *your* devices without ever touching a cloud."
Stepping-stone available today: manual sync already works (export on phone → import
on tablet); v0.8 automates the transport and adds stable IDs so it merges, not
duplicates.

## Transport decision (LOCKED 2026-06-03): NSD/mDNS + TCP socket — GMS-free
User chose the GMS-free path over Google's **Nearby Connections API**. Rationale:
Nearby Connections is part of **Google Play Services** — a black-box dependency that
(a) re-opens the exact telemetry surface stripped for v0.6.1 (can't *prove* nothing
leaves the device once GMS is in the graph), (b) breaks on de-Googled devices
(GrapheneOS/Huawei/F-Droid — the most privacy-conscious users), and (c) is
Android-only, so it can't carry to the iOS roadmap. NSD/mDNS is the standard Bonjour
protocol iOS also speaks, so this path is the one that reaches cross-platform sync.

Sketch (build in v0.8, after the ID migration):
- **Discovery:** `NsdManager` — sender registers a service (e.g. `_nativelm-sync._tcp`),
  receiver discovers + resolves to host/port. Hold a `WifiManager.MulticastLock`
  during discovery. No location permission needed (modern Android); needs the local
  network + `ACCESS_NETWORK_STATE` (INTERNET already granted).
- **Transport:** plain TCP socket; stream the bundle. **The payload is the
  already-encrypted `.nlmbak`** (ciphertext only) — so even an unprotected socket
  never carries plaintext; the receiver prompts for the passphrase to decrypt. (Can
  add TLS/a session key later; not required for confidentiality since the blob is
  already E2E-encrypted with the user's passphrase.) This reuses the entire backup
  pipeline as the transfer format — transport is genuinely the last mile.
- **Peer verification:** show a short code (derived from device names + a nonce) or a
  QR on the sender that the receiver confirms, so you don't transfer to the wrong
  device. (Confidentiality is already covered by the passphrase; this is just
  "right device" UX.)
- **Still required first:** the stable-UUID ID migration so a re-received bundle
  MERGES (dedupes by UUID) instead of duplicating — without it, sync is just
  repeated additive imports.
