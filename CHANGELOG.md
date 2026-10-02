# Changelog

All notable changes to this fork are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

## [0.3.38] — 2026-10-02

### Added
- **Offline follow list** (Settings → OFFLINE LISTS). A fully local follow list
  with an on/off switch. First switch-on pulls your follows from the relays
  once; after that it NEVER syncs on its own. While ON the app uses the local
  list instead of the relay kind-3 list for the Following feed and profiles,
  never reads or publishes your relay follow list, and the profile
  FOLLOW/UNFOLLOW buttons only change the local list. Browse it in a compact,
  collapsed-by-default list (no profile pictures are fetched or cached), add or
  remove people by npub/nprofile/hex, and press SYNC to copy relay → offline
  (one way only, behind a warning that it can overwrite the local list).
- **Offline block list** (keeps the name BLOCK). Same rules and screens. Online
  mode now uses your NIP-51 mute list (kind 10000, public `p` tags; existing
  private entries are preserved untouched); offline mode keeps a local,
  browsable list and never touches kind 10000. Blocks are honoured in the
  feed, threads, notifications and hashtag feeds. Old device-wide blocks are
  merged in the first time the offline block list is synced.
- **Export / import** of either list as a single `.json` file shaped like a
  standard Nostr list event (kind 3 / kind 10000), also accepted by other
  clients; import accepts that, signed events, tag arrays, or plain
  npub/hex lists, with MERGE or REPLACE.
- Each local list belongs to one account. It only moves to another account via
  export, then import while logged into the other account (a warning is shown
  if the file belongs to a different account). Removing an account deletes its
  local lists.
- Unit tests for the list file format and the offline list store.

### Privacy
- Offline lists are stored only in the app's DataStore (no network, no cloud)
  and are erased by the duress wipe. The only new network traffic is a
  read-only relay REQ when you sync.

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
