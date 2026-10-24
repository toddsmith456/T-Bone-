# T-Bone for Android

A privacy-focused Nostr client for Android. No Google Play Services, analytics, or tracking. Licensed under MIT.

[Download the latest release](https://github.com/toddsmith456/T-Bone-/releases/latest) · [Changelog](CHANGELOG.md) · [Privacy policy](docs/PRIVACY.md)

## Features

- **Nostr social:** Following and global feeds; profile and hashtag search; relay management; profiles; replies, full threads, quote-notes, reposts, polls, emoji reactions, and sharing.
- **Accounts and identity:** Multiple accounts; Amber, nsecBunker, or a device-local key protected by Android Keystore. Short BIP-39 word fingerprints and configurable avatars make identities easier to recognize.
- **Notifications:** An in-app inbox with filters, recent history, note previews, and inline replies. No push-notification service.
- **Photos and video:** Inline media, image and video viewers, downloads, playback controls, and uploads to configurable Blossom servers.
- **Wallet hand-offs:** Lightning zaps (NIP-57) request and validate invoices before opening an external Lightning wallet; Monero xaps use NIP-A3 kind-10133 payment targets and hand off to an external Monero wallet. T-Bone never marks a wallet hand-off as a confirmed payment.
- **Privacy and safety:** Optional Tor routing through Orbot; local-only follow and block lists; content filters and screen-time limits; app lock, screenshot blocking, and a duress PIN.
- **Private toolbox:** Notes, checklists, folders, attachments, and calendar events encrypted on-device with AES-256-GCM and Android Keystore. Also includes geohash chat and a voice recorder with voice anonymization.
- **webxdc mini apps:** A single-player [webxdc](https://webxdc.org/) player in the toolbox. Import `.xdc` files, browse the official webxdc.org store, or discover apps over Nostr (kind 1063, `#m application/x-webxdc`) from the T-Bone npub or every author. Saved apps run offline in a sandboxed WebView with no network access, and any saved app can be promoted to a toolbox tool with one of 20 icons. See [docs/WEBXDC.md](docs/WEBXDC.md).
- **Appearance:** Youniversal offers light, dark, cream, and automatic themes, accent and Material You colors, rounded-by-default adjustable corners, app-wide text scaling, and two fonts (standard sans-serif by default or Nunito Sans).

See the [NIP compatibility guide](docs/nip-compatibility.md) for protocol details.

## Install

Download `T-Bone-fork-<version>-release.apk` from [Releases](https://github.com/toddsmith456/T-Bone-/releases/latest) and install it on Android 8.0 or newer. The app ID is `social.tbone.fork`, so it can be installed alongside the original T-Bone app.

Releases include the release APK and `SHA256SUMS.txt`—not a debug APK. To verify the download, place both files in one folder and run:

```bash
sha256sum -c SHA256SUMS.txt
```

## Build from source

```bash
git clone https://github.com/toddsmith456/T-Bone-.git
cd T-Bone-
./gradlew :app:assembleDebug
```

The debug variant is for local development; it is not published with GitHub releases. Minimum supported Android version: 8.0 (API 26).

## Project links

- [Privacy rules and enforcement](docs/PRIVACY.md)
- [NIP compatibility](docs/nip-compatibility.md)
- [Release process](docs/RELEASE.md)
- [Contributing](CONTRIBUTING.md)
- [Full changelog](CHANGELOG.md)

## License

MIT — see [LICENSE](LICENSE).
