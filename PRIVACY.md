# NativeLM — Privacy Policy

_Last updated: 2026-06-04_

NativeLM is a fully on-device AI app. It is built to do as much as possible
**without a network, without an account, and without sending your data anywhere.**
This policy explains exactly what that means.

> Plain-language summary: **We don't collect anything.** Your conversations,
> documents, and settings stay on your device. The app has no analytics, no
> ads, no account, and no backend server operated by us. The only network the
> app makes is downloading AI models you explicitly choose, directly from the
> model host (e.g. Hugging Face / Google).

## Who this applies to

This policy covers the **NativeLM** Android app (`com.nativelm.app`), published
as the reference application for the open-source
[litertlm-kmp](https://github.com/sagar-develop/litertlm-kmp) engine.

## What we collect

**Nothing.** NativeLM has no analytics SDK, no crash-reporting SDK, no
advertising SDK, and no telemetry. We, the developers, do not receive your
prompts, your model responses, your documents, your usage, your device
identifiers, or any diagnostics.

To make the zero-telemetry stance concrete, the app actively **removes** the
Google `datatransport` upload pipeline that the on-device OCR library (ML Kit)
would otherwise bundle, so it cannot initialize or upload anything. See the
`tools:node="remove"` entries in the app manifest.

## Data stored on your device

All of the following is stored **only on your device** and is never uploaded by us:

| Data | Where it lives | Notes |
|---|---|---|
| Conversations & messages | On-device database (ObjectBox) | Deletable per-conversation or via "Clear all data". |
| Projects, documents & vector index | On-device database + app file storage | Imported PDFs/text are processed locally for retrieval. |
| Studio artifacts | On-device database | Generated locally from your sources. |
| App settings (theme, language, app-lock, onboarding) | On-device preferences (DataStore) | — |
| Hugging Face token (optional) | Encrypted on-device (`EncryptedSharedPreferences`) | Only used to authenticate downloads of gated models. Never sent to us. |
| Downloaded model files | App file storage | Deletable from the Models screen. |

You can erase all of the above at any time from **Settings → Clear all data**,
or by uninstalling the app.

## Network access

The app makes network connections in only these cases, all initiated by you:

1. **Model downloads.** When you tap to download a model, the app connects
   **directly to the model host** (e.g. `huggingface.co` / Google storage CDNs)
   over HTTPS to fetch the file. For license-gated models (e.g. Gemma), your
   Hugging Face token is sent **to Hugging Face only**, as the standard
   `Authorization` header, to authorize that download. The app does not route
   these downloads through any server we operate.
2. **Local peer-to-peer sync (optional).** If you use device-to-device sync,
   your data is transferred **directly between your own devices over your local
   Wi-Fi network** (no internet, no server). The transferred bundle is
   end-to-end encrypted (AES-256-GCM) with a one-time code shown on the sending
   device.

The app uses no cleartext HTTP for these connections, and declares
`cleartextTrafficPermitted="false"`.

When third parties (such as Hugging Face or Google) serve a model download to
you, their own privacy policies govern that interaction. We have no visibility
into it.

## Permissions and why they're used

- **Internet / network state** — to download models you choose.
- **Microphone (`RECORD_AUDIO`)** — only when you tap the mic for voice input.
  Audio is transcribed **on-device** (Whisper) and is never uploaded; recorded
  audio is used transiently for transcription and not retained as a file by us.
- **Wi-Fi multicast (`CHANGE_WIFI_MULTICAST_STATE`)** — only for discovering
  your other device during local peer-to-peer sync.

The app requests no location, contacts, camera, or storage-wide permissions.

## Children's privacy

NativeLM is not directed at children and collects no personal information from
anyone, including children.

## Third-party / open-source components

NativeLM is open source under AGPL-3.0; the full source is at
<https://github.com/sagar-develop/litertlm-kmp>. AI models you download are
provided by third parties under their own licenses and terms (for example,
Google's [Gemma Terms of Use](https://ai.google.dev/gemma/terms)). You are
responsible for complying with the license of any model you download.

## Changes to this policy

If this policy changes, the updated version will be published at this URL with a
new "Last updated" date.

> Hosted copy: this policy is published via GitHub Pages at
> <https://sagar-develop.github.io/litertlm-kmp/privacy/> (the source of the
> hosted page is [`docs/privacy/index.html`](docs/privacy/index.html); this
> Markdown file is the canonical text).

## Contact

Questions about this policy: **sgupta8874@gmail.com**
