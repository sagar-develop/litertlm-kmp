# Publishing NativeLM to Google Play — readiness checklist

This is the end-to-end checklist for shipping the **NativeLM** sample app
(`com.nativelm.app`) to the Google Play Store. Items are grouped by whether they
are **done in the repo**, **done at build time**, or **done in the Play
Console**. Check each before your first production submission.

Legend: ✅ done in this repo · 🛠️ you do at build/release time · 🌐 you do in
the Play Console · ⏭️ recommended follow-up (not blocking).

---

## 1. Legal & compliance

- ✅ **Privacy policy authored** — see [`PRIVACY.md`](PRIVACY.md).
  - 🌐 **Host it** at a public URL (e.g. GitHub Pages from this repo) and paste
    that URL into **Play Console → Policy → App content → Privacy policy**.
- ✅ **In-app terms/source disclosure** — the final onboarding slide links the
  AGPL source and Google's Gemma Terms, and the gated-model download flow
  reminds users to accept the model license on Hugging Face.
- 🌐 **AGPL-3.0 distribution.** As the sole copyright holder you may distribute
  your own app on Play under Play's terms regardless of the AGPL (the AGPL binds
  third parties, not you). Keep the source public (it is) so "Corresponding
  Source" is available. If you ever accept outside AGPL contributions, require a
  CLA to preserve this freedom and the dual-licensing model.
- 🌐 **Gemma / model licenses.** Gemma is under the
  [Gemma Terms of Use](https://ai.google.dev/gemma/terms) and Prohibited Use
  Policy, not an OSI license. The app downloads Gemma only when the user supplies
  their own HF token and accepts the license on Hugging Face, so the user is the
  licensee — but keep the in-app Gemma Terms link (done) and don't strip model
  notices.

## 2. Data safety form (Play Console → App content → Data safety)

NativeLM collects/transmits **no** user data to us. Suggested answers:

- **Does your app collect or share any of the required user data types?** → **No.**
  (No analytics, ads, crash SDKs, or backend. Conversations/documents stay
  on-device; the only network is user-initiated model downloads sent directly to
  the model host.)
- **Is all user data encrypted in transit?** → Not applicable to data *we*
  collect (none). Model downloads use HTTPS; local P2P sync payloads are
  AES-256-GCM encrypted.
- **Do you provide a way to request data deletion?** → Data never leaves the
  device; users delete everything via **Settings → Clear all data** or uninstall.
- Declare the **microphone** usage truthfully: audio is processed on-device for
  voice input and not sent off device.

> Re-check this form whenever you add a dependency — a new SDK can silently
> introduce data collection.

## 3. Permissions justification

The app declares only:

- `INTERNET`, `ACCESS_NETWORK_STATE` — model downloads.
- `RECORD_AUDIO` — on-device voice input (Whisper). Provide a prominent in-app
  rationale before first mic use; declare on the Data Safety form.
- `CHANGE_WIFI_MULTICAST_STATE` — local peer-to-peer sync discovery (mDNS).

No `QUERY_ALL_PACKAGES`, no location, no broad storage. If Play flags any
permission, the justification above is the answer.

## 4. Build & signing

- 🛠️ **Build an Android App Bundle (AAB), not an APK** — Play requires AAB:
  ```
  ./gradlew :sample-app:bundleRelease
  # output: sample-app/build/outputs/bundle/release/sample-app-release.aab
  ```
- 🛠️ **Release signing** is wired via `sample-app/keystore.properties`
  (gitignored). Create it before a real release:
  ```properties
  storeFile=/absolute/path/to/release.keystore
  storePassword=…
  keyAlias=…
  keyPassword=…
  ```
  Without it the release build falls back to debug signing (fine for local, **not**
  for Play). Enroll in **Play App Signing**.
- ✅ **R8 minify + resource shrink** enabled on release; engine + native libs ship
  consumer ProGuard rules.
- ✅ **64-bit only** (`arm64-v8a`) — meets Play's 64-bit requirement. (x86/emulator
  not shipped; acceptable, but the Play pre-launch report's x86 devices will skip.)
- 🛠️ **Versioning** — bump `versionCode` for every upload (currently `6`) and set a
  user-facing `versionName`.
- 🛠️ **`targetSdk`** must stay within Play's current window (one year of the latest
  API). Verify before each submission.

## 5. Network / security

- ✅ **Cleartext disabled** — `usesCleartextTraffic="false"` +
  `network_security_config.xml` (`cleartextTrafficPermitted=false`). Model
  downloads are HTTPS; local P2P sync uses raw sockets carrying AES-GCM
  ciphertext, unaffected by this policy.
- ✅ **`allowBackup="false"`** — no auto cloud backup of on-device data.

## 6. Store listing (Play Console)

- 🌐 App name, short & full description (lead with: private, on-device, no
  account, no telemetry).
- 🌐 **Screenshots** — phone (and 7"/10" tablet if you target tablets); assets
  exist under [`docs/screenshots/`](docs/screenshots/).
- 🌐 **App icon** (512×512) and **feature graphic** (1024×500). Adaptive launcher
  icon is in the app.
- 🌐 **Content rating** questionnaire (IARC).
- 🌐 **Target audience & content** (not directed at children).
- 🌐 **Category**: Productivity / Tools.

## 7. Device experience & first run

- ✅ **No-account first run** — the catalog leads with **ungated** models
  (Qwen3 0.6B, DeepSeek-R1 1.5B, Phi-4 mini, Qwen3 4B) that download with no HF
  token; the app recommends the best one that fits the device's RAM. The gated
  Gemma tier is behind the "Advanced" section. A reviewer or new user can reach a
  working model with no Hugging Face account.
- ✅ **RAM-tier gating** — under-spec devices see "Not enough RAM" rather than an
  OOM crash; downloads are SHA-validated and resumable.
- 🌐 Consider setting **device catalog / minimum RAM** expectations in the
  listing so very low-RAM devices don't install and 1-star the app.
- 🌐 Run the **pre-launch report** and triage crashes on the low-end matrix.

## 8. Pre-submission testing

- 🛠️ Install and smoke-test the **release** AAB via a Play **internal testing**
  track (not just `assembleDebug`) — R8 can change behavior.
- 🛠️ Verify: first-run downloads an ungated model with no token; gated download
  shows the license reminder; voice input works; P2P sync works; "Clear all
  data" wipes everything.

## 9. Recommended follow-ups (not blocking this release)

- ⏭️ **Foreground-service / WorkManager downloads.** Multi-GB model downloads
  can be killed if the app is backgrounded mid-download. Wrap downloads in a
  foreground service (with a notification) or WorkManager so they survive
  backgrounding. Tracked separately from this PR.
- ⏭️ **Pin model revisions + SHA-256.** Catalog LLM URLs use Hugging Face
  `resolve/main`, which can move; pin a revision and add `sha256` (as the Whisper
  entry already does) for reproducible, tamper-evident downloads.
- ⏭️ **Wi-Fi-only download default** + clear data-usage messaging for large
  models on metered connections.
