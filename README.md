<p align="center">
  <img src="assets/icon.png" alt="T-Bone icon" width="96" height="96" />
</p>

# T-Bone for Android

**T-Bone** (`social.tbone.fork`) is a fast, privacy-first Nostr client and encrypted local toolbox for Android — built with **zero Google Play Services, zero Firebase, zero analytics, and zero tracking**, enforced automatically in CI on every PR and release. Licensed under MIT.

[Install on Zapstore](https://zapstore.dev/apps/social.tbone.fork) · [GitHub Releases](https://github.com/toddsmith456/T-Bone-/releases/latest) · [Changelog](CHANGELOG.md) · [Privacy Policy](docs/PRIVACY.md) · [NIP Compatibility](docs/nip-compatibility.md)

---

## What T-Bone Is Today

T-Bone began as a minimalist fork and has evolved into a full-featured, self-sovereign Android Nostr client combining customizable feeds, Blossom media publishing, non-custodial Bitcoin Lightning & Monero hand-offs, optional on-device AI content filtering, and a hardware-backed encrypted local toolbox — while keeping every byte of telemetry and Google SDK code permanently banned.

## Key Features

### Customizable Feeds & Nostr Social
- **Four built-in home feeds:**
  - **Follows** — live timeline from your follow list (or your local-only offline follow list).
  - **Clock** — a time-machine window frozen 1–24 hours in the past through the people you follow.
  - **Suggested** — curated roster of 1,300+ Nostr accounts for instant discovery.
  - **Global** — live relay firehose with buffered loading and past paging.
- **Custom home tabs:** Add, reorder, or hide home tabs backed by **People lists** (manual or synced NIP-51 sets), **specific relays**, **hashtags**, or **NIP-88 polls**.
- **Complete Nostr interactions:** Replies and threaded conversations, reposts and embedded quote notes, NIP-88 polls, customizable emoji reactions, bookmarks & NIP-51 bookmark folders, profile pins, NIP-09 deletions, NIP-56 reports, profile & hashtag search, and branded image/video share cards.
- **Built for speed:** Native `libsecp256k1` Schnorr signature verification, batched viewport engagement subscriptions, per-card UI updates, non-blocking media loading, and an Android Baseline Profile.

### Multi-Account & Self-Sovereign Identity
- **Multiple accounts** with fast account switching.
- **Flexible signing:** Delegate signing to **Amber (NIP-55)** or **nsecBunker / NIP-46 remote signers**, or use an **Android Keystore–backed device-local key** so private keys stay protected.
- **Human-readable identity:** Short **BIP-39 word fingerprints**, QR **Verify Identity** checks, and configurable avatar display modes make accounts easy to recognize without decoding hex strings.

### Media, Blossom Uploads & Optional Offline AI NSFW Filter
- **Inline media & playback controls:** Full-screen image and video viewers, inline audio playback, media downloads, configurable image loading, and **video playback mode** controls (**On tap**, **Preload**, or **Autoplay**).
- **Blossom (BUD-11 / `kind 24242`) uploads:** Multi-server failover with a single signing prompt, **Beaver Mode** (reads your published `kind 10063` Blossom server list automatically), and **per-post server override chips** right on the Write tab.
- **Optional on-device AI NSFW filter:** 100% offline **LiteRT** neural image scanning with zero cloud requests. Install SHA-256-verified models (**High-Accuracy Marqo ViT-384**, **Fast MobileNetV2**, or both in an automatic 2-stage cascade) from GitHub Releases or local files, with Keystore-HMAC local caching and tap-to-reveal veils.

### Private Encrypted Toolbox
- **Encrypted Notes Vault:** Notes, checklists, nested folders, and media/audio attachments encrypted on-device with **AES-256-GCM + Android Keystore** — plus an optional **Decoy Notes** vault.
- **Private Local Calendar:** On-device encrypted events, recurring schedules, and local reminders.
- **Portable Encrypted Backups:** Passphrase-encrypted snapshots of your notes vault, folders, attachments, and calendar that re-encrypt under the target device's Keystore on import.
- **Voice Recorder & Voice Effects:** Record voice notes and apply on-device speed, pitch, and drive effects before previewing or sharing.
- **Geohash Channels:** Anonymous location-code channels with pinned rooms and optional home-screen unread indicators.
- **Non-Custodial Wallet Hand-offs:**
  - **Lightning zaps (NIP-57):** Requests and verifies the BOLT-11 invoice amount before handing off to your external Lightning wallet.
  - **Monero xaps (NIP-A3 / `kind 10133`):** Reads and publishes Monero payment targets and hands off directly to your external Monero wallet.
  - T-Bone never claims custody or assumes a wallet hand-off succeeded as a confirmed payment.
- **Update Center:** Checks GitHub Releases (respecting Tor), verifies APK SHA-256 checksums, and helps install T-Bone and Amber updates.

### Privacy, OpSec & Anti-Coercion Controls
- **Zero Google services or telemetry:** Enforced automatically by `scripts/privacy-check.sh` on every PR and release build (see [docs/PRIVACY.md](docs/PRIVACY.md)).
- **Network privacy:** Optional **Tor / Orbot** routing for relays, media, and updates, plus automatic tracking-parameter removal (`LinkCleaner`) on pasted and shared URLs.
- **Offline Follow & Block Lists:** Keep private follow and mute/block lists strictly on-device without ever publishing them to relays.
- **Locking, Stealth & Duress Protection:** Whole-app **PIN lock**, separate **Toolbox PIN**, whole-app or Toolbox-only **screenshot blocking**, **Notepad stealth disguise** (switches launcher icon and name to *Notepad*), a **Duress PIN** that silently wipes sensitive local data (vault, offline lists, AI models/cache, and session state), content filters, and **Screen Time** limits.
- **In-App Notifications & Local Diagnostics:** Polling notification inbox with filters, note previews, and inline replies (zero push servers), plus a configurable **Share Logs** dialog with time-range and crash-only filters.

### Youniversal Appearance & Custom Icons
- Light, Dark, Cream, and Automatic themes, Material You and custom accent colors, adjustable corner roundness, app-wide text scaling, Standard Sans-Serif or Nunito Sans typography, and **18 selectable launcher icons** (classic T-Bone outline, full-color steak icon, and 16 color variants).

---

## Install

Requires **Android 8.0 (API 26)** or newer. Package ID is `social.tbone.fork`, so it installs **side-by-side** with the original T-Bone (`social.tbone`).

### Option 1 — Zapstore (Recommended)

Install and receive cryptographically verified updates through **[Zapstore](https://zapstore.dev/apps/social.tbone.fork)** (`social.tbone.fork`), claimed under the developer's verified Nostr identity and signing certificate.

### Option 2 — GitHub Releases

Download `T-Bone-fork-<version>-release.apk` and `SHA256SUMS.txt` from [GitHub Releases](https://github.com/toddsmith456/T-Bone-/releases/latest) and verify before installing:

```bash
sha256sum -c SHA256SUMS.txt
```

Official release APK signing certificate SHA-256:

```text
0e37601f5716b1476b26a0aed023df97ca8c2ab0c1500cb2d3a7237337f65678
```

---

## Build from Source

```bash
git clone https://github.com/toddsmith456/T-Bone-.git
cd T-Bone-
./gradlew :app:assembleDebug
```

Debug builds are for local development and are never published to GitHub Releases or Zapstore.

## Versioning

`version.properties` is the single source of truth for `versionName` and `versionCode`. `app/build.gradle.kts` reads it directly into `BuildConfig` for Settings, Update Center, and APK verification. Bump versions with `./scripts/make-release.sh <version>`; release CI rejects any `v<version>` tag that does not match.

## Project Links

- [Zapstore Listing](https://zapstore.dev/apps/social.tbone.fork)
- [Privacy Rules & CI Enforcement](docs/PRIVACY.md)
- [NIP Compatibility](docs/nip-compatibility.md)
- [Four Feeds Architecture](docs/FEEDS.md)
- [Blossom Uploads](docs/BLOSSOM.md)
- [Release Process](docs/RELEASE.md)
- [Contributing](CONTRIBUTING.md)
- [Full Changelog](CHANGELOG.md)

## License

MIT — see [LICENSE](LICENSE).
