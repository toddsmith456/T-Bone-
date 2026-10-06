# Changelog

All notable changes to this fork are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

## [0.3.45] — 2026-10-05

### Added
- Nostr Wallet Connect toolbox with Android Keystore-protected connection
  material, NIP-47 NIP-04/NIP-44 negotiation, capability validation, and
  explicit wallet-confirmed payment states.
- NIP-57 Lightning zaps on every shared reaction bar, including repost and poll
  targets, with accent-colored pressed states and duplicate/unknown-payment
  protection.
- Wallet-tool activity filtered to Nostr zap/payment notifications only.

### Changed
- Release version bumped to 0.3.45 (version code 53).

## [0.3.44] — 2026-10-04

### Added
- **In-app video playback.** Settings → MEDIA now offers Off, Low, and Regular
  video quality, three plain-language load/play choices, and optional video
  thumbnails for tap-to-load videos. Low quality asks adaptive sources for a
  smaller track where available; Regular keeps the posted source quality.
- Videos play in a themed, full-screen T-Bone player with close and download
  controls, Tor-aware networking, and repeat mode permanently disabled.

### Fixed
- NIP-17 private messages and their wrapper events are rejected at feed
  dispatch, cache, and render boundaries.
- Quote references are rendered once, including nested quoted notes.
- Quote-replies keep a separate NIP-18 `q` reference and correct NIP-10 root/
  reply target tags when both relationships are present.

### Changed
- Release version bumped to 0.3.44 (version code 52).

## [0.3.43] — 2026-10-03

Follow-up to 0.3.42 so that every note that is supposed to load actually does,
and so nothing can sit on "loading…" forever.

### Fixed
- **Quoted and reposted notes now load in hashtag feeds.** That screen never
  looked them up at all, so a result that quoted or reposted a note sat on
  "loading quoted note…" / "loading reposted note…" permanently. It now
  resolves them (cache first, then relays, kind-agnostic) and renders the
  embedded note, with profiles fetched for the authors it reveals.
- **Nested quotes render inside notification cards** — the resolved map is
  handed to the quoted card, so a quote of a quote shows there too.
- **Emoji parsing handles real keyboard emojis.** Splitting input into
  grapheme clusters mangled multi-code-point emojis: flags, skin-toned
  thumbs-up, keycaps and ZWJ family emojis could be saved as broken pieces.
  The scanner now keeps each emoji intact.

### Changed
- **"Loading…" is no longer forever.** Referenced notes (quotes, reposts,
  parent notes, notification targets) that were asked for and never arrived
  now render a terminal "this note isn't available on any relay" row you can
  tap to open, instead of spinning — the feed, profiles, threads, hashtag
  feeds and notifications all share this state, and it also clears when an
  embedded repost resolves its inner note.
- **Thread fold code removed for good.** The fold item types and the
  expand/collapse plumbing are gone from the thread screen and view model, so
  a "show more replies" row cannot appear: every thread renders fully
  expanded, at a glance.

### Added
- Unit tests for emoji parsing (ZWJ sequences, flags, skin tones, keycaps,
  rejecting text) and a regression test that a deep, wide thread renders
  completely expanded.

## [0.3.42] — 2026-10-02

Thread loading and notification references are now kind-complete, and the
release hardens the settings and reaction flows from the previous feature drop.

### Fixed
- **Every thread item is retained.** Missing declared roots and missing direct
  parents are promoted to visible top-level notes instead of disappearing with
  their nested replies. NIP-22 comments are counted and loaded alongside
  regular NIP-10 replies.
- **Notification previews resolve every supported event kind.** Parent notes,
  polls, reposts, and comments are fetched with ids-only lookups; authored
  replies and polls are restored after restart so ownership checks still work.
- **Latest reaction content wins.** When relays deliver several reactions from
  one account out of order, the active user's newest emoji stays on the note.
- **Notification history has no extra bottom buffer.** The shared bottom tab
  bar supplies the inset; the list ends at its actual last row/load-more row.

### Changed
- Thread rendering suppresses legacy fold rows entirely: the screen requests
  and presents the complete known tree in one view.
- Release version bumped to 0.3.42 (version code 50).

## [0.3.41] — 2026-10-02

Threads that fully load, multi emoji reactions, and a pin-locked Content
Filters folder.

### Added
- **Multi emoji reactions.** Settings → REACTIONS → *multi emoji reactions*.
  Save up to 10 emojis straight from your keyboard's emoji panel (tap a saved
  emoji to remove it). With two or more saved, tapping like pops up a compact
  box of your emojis right above the button; pick one to react with it. With
  exactly one saved, like reacts with it directly. Once you've reacted, the
  emoji you used replaces the heart on that note (feed, profiles, threads,
  reposts). Emoji reactions from others now count toward the like total too.

### Fixed
- **Reposted replies now load their whole thread, upstream included.** Opening
  a repost unwraps it to the reposted note, and the ancestor walk now actually
  receives the notes it asks relays for — previously every by-id lookup was
  dropped by the thread-membership filter, so parents only ever showed when
  they happened to be cached. Missing parents are retried on the note's relay
  hints plus fallback relays, and if a direct parent is truly gone the thread
  root is still shown.
- **Notes that weren't cached open again** (from notifications, links, etc.)
  instead of reporting "not found" — same lookup bug.
- **Deep reply branches load:** replies to replies are requested as they
  arrive, so clients that don't tag the root on nested replies no longer leave
  holes in the thread.
- **Quoted / reposted notes no longer get stuck on "loading…"** in the feed and
  on profiles: the lookup stayed open only until the *fastest* relay answered,
  dropping the note when a slower relay had it. Quotes inside reposted notes,
  comments and polls are resolved too.

### Changed
- **Threads show everything at once.** No more "＋ N replies" / "show more
  replies" folds — the full tree renders expanded so it can be read at a glance.
- **Content Filters is now a single pin-locked folder** containing the filters
  *and* parental controls (screen time). Once a parental pin is set, the whole
  folder — including the screen-time limit — needs the pin to open, so it
  can't be switched off without it. It relocks when you leave the folder.
- Notifications: removed the blank buffer at the bottom of the list — the
  bottom navigation bar already provides the spacing.

## [0.3.40] — 2026-10-02

Threads, feeds and profiles.

### Added
- **Reply mini-threads.** The thread screen is no longer a flat chronological
  list. Replies are rebuilt into a parent→children tree and flattened
  depth-first, so every reply is followed by its own replies; siblings stay
  chronological. Deep branches and very wide fan-outs fold behind
  `＋ N replies` rows that expand and collapse in place, and each reply is
  connected by an indent rail that is dashed where it starts in mid-air.
- **Profile tabs now work.** `ALL` / `REPLIES` / `MEDIA` are real tabs. Replies
  shows only the profile's replies (and NIP-22 comments); Media is a gallery of
  every image in their notes, five pictures wide, with crossfaded thumbnails
  and a swipeable full-screen viewer.
- **Reposts appear in a profile's `all` feed** (kind 6), rendered as the note
  that was boosted, with its own reactions.

### Fixed
- **The parent note now loads.** Thread loading resolves the whole ancestor
  chain instead of the root plus one parent: each ancestor is fetched from
  relays (cache first) until the chain ends, so a thread opened mid-conversation
  shows the notes above it. Notes that are missing from one relay are no longer
  lost — a REQ waits for **every** connected relay's `EOSE` (with a hard
  timeout) instead of treating the first reply as "nothing else is coming",
  which is what left a parent permanently blank when the fastest relay did not
  carry it.
- **"replies in between" is gone** — the in-between notes are fetched and
  rendered automatically. If a relay never returns the root at all, the
  top-most notes that *were* returned are rendered so the thread still opens.
- **Images inside quoted notes are visible.** The quoted card parsed its media
  list and then never drew it, so a quote of an image post showed text only.
  Quoted/reposted notes now render their images and videos, and quotes nest one
  level deep.
- **`nostr:note1…` / `nevent1…` references render as notes**, not as underlined
  links: a reference is lifted out of the text flow and the referenced note is
  embedded as a card once resolved. Unresolved references show a compact
  `↗ open note` affordance instead of a raw URI. Quote targets are now fetched
  without a kind filter, so a quoted poll or repost resolves too (it never did).
- **NIP-22 comments (kind 1111) are part of threads.** Replies are requested by
  lowercase `#e` *and* uppercase `#E`, so comments from NIP-22 clients appear in
  the thread and in a profile's replies tab.
- **Notes that only *mention* another note are no longer treated as replies to
  it.** Both the parent lookup and the thread builder now skip `mention`-marked
  `e` tags; a mention-only note used to be glued to the wrong thread (and could
  resolve the wrong parent).
- **Bottom navigation stays on the Toolbox and Notifications screens**, exactly
  as on the home tab. Tab switches keep one back-stack entry per tab and restore
  each tab's state.

### Changed
- The bottom bar is now a single shared component (`BonyBottomBar`) used by the
  feed, toolbox and notifications destinations, so the three cannot drift apart.


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
