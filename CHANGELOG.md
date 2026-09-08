# Changelog

All notable changes to this fork are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
- (next changes go here)

## [0.3.36] — 2026-09-07 — Fork inception

Starting point: upstream T-Bone v0.3.36 (versionCode 44), extracted verbatim
from `T-Bone-Source (4).zip` (release tag `Nostr`).

### Added
- Fork identity: `applicationId social.tbone.fork` — installs alongside the
  original `social.tbone` without replacing it.
- Unique calendar alarm action (`social.tbone.fork.CALENDAR_ALARM`).
- Privacy enforcement: `scripts/privacy-check.sh`, `docs/PRIVACY.md`, CI gates
  on PRs and releases. No Google services, no tracking — ever.
- Automatic releases: push a `v*` tag → GitHub Actions builds debug + release
  APKs and publishes them to the GitHub Release (`docs/RELEASE.md`).
- CI-friendly signing: env/`local.properties` keystore with debug-key fallback.
- `FORK.md`, `CONTRIBUTING.md`, `CHANGELOG.md`, release helper scripts.

### Changed
- Nothing in app behavior — byte-for-byte same features as upstream 0.3.36.

### Privacy
- Audited: no Play Services, no Firebase, no analytics/crash SDKs, no tracking
  endpoints. Only Google-origin artifacts are offline build libraries (Hilt
  DI, ZXing QR, KSP) — see `docs/PRIVACY.md` §2.
