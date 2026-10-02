# Changelog

All notable changes to this fork are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

## [0.3.39] — 2026-10-02

### Fixed
- **Blossom uploads rebuilt from scratch — they now actually work.** Every
  upload used to fail on every server: the request was authorized with a
  NIP-98 `kind 27235` event, which Blossom servers do not accept. Blossom uses
  BUD-11, a `kind 24242` event describing the operation (`t`, `x`, `size`,
  `expiration`). Measured live, the old token was answered
  `400 Wrong event kind` (nostr.download), `400 Auth event must be kind 24242`
  (blossom.data.haus, blossom.ditto.pub),
  `401 Invalid Blossom Authorization event kind` (blossom.nostr.build,
  blossom.band) and `401 wrong kind in auth event` (blossom.primal.net).
  The new token is accepted by all of them. Screens are unchanged.
- **Correct MIME type sent.** Images are re-encoded to JPEG before upload, but
  the picker's original MIME (`image/png`, …) was sent with the JPEG bytes.
  Servers validate this and store it as the blob's `Content-Type`; the prepared
  file now reports its own type, which is what gets sent.
- **The server's own blob URL is used** (with the extension it chose) instead
  of a hand-built `<server>/<sha256>`; the hand-built form remains the fallback
  when a server answers without a descriptor.
- **Failover across the whole enabled pool.** Previously only one random
  fallback was tried after the preferred/default failed, so a single full or
  dead server killed the post. Now every enabled server is a candidate, and if
  all fail the note reports each server's own reason (`X-Reason`, e.g.
  "file too large"), not a bare status code.
- **Uploads can no longer hang forever.** They reused the WebSocket HTTP client
  whose read timeout is disabled (`readTimeout(0)`), so a stalled server left
  the compose screen stuck on "…uploading". Uploads now use a derived client
  with real connect/write/read timeouts that still honours the Tor setting.
- **Blob bodies are streamed from disk** with an exact `Content-Length`
  (previously the whole file was loaded into memory), so large videos no longer
  risk running the app out of memory.
- `X-SHA-256` (BUD-06) is now sent, and `402 Payment Required` is reported as
  "requires payment" rather than an opaque failure.
- Server URLs typed into Settings are normalized (scheme defaulted to `https`,
  trailing slashes removed, authority lower-cased) and re-normalized on load —
  a scheme-less entry previously made every upload fail before it left the
  device.

### Added
- BUD-01/02 primitives in `social.tbone.media.blossom`: `BlossomAuth`,
  `BlossomClient`, `BlossomUploader`, `BlossomServerPool`, `BlossomUploadResult`.
- Unit tests for the authorization token (kind, tags, base64 encoding), the
  upload request itself (method, path, headers, body), descriptor parsing,
  server selection and sha256 formatting; plus an opt-in live test that
  uploads to real servers and re-downloads the blob
  (`-DblossomLive=1`). See `docs/BLOSSOM.md`.
- The release workflow now runs the unit tests before building APKs, so a
  broken upload path cannot be released.


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
