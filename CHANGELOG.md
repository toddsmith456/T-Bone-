# Changelog

All notable changes to this fork are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

## [0.5.9] — 2026-11-14

### Added
- **Offline AI NSFW media scanner (optional add-on).** Filter settings now include an on-device LiteRT image scanner that runs entirely offline with zero telemetry and an HMAC-keyed local scan cache. Two optional downloadable model packs are available — a fast Stage 1 MobileNetV2 FP16 classifier and a high-accuracy Stage 2 Marqo ViT-Tiny-384 FP16 classifier — with selectable sensitivity, scan-mode controls, and Tor-aware model downloads.
- **NIP-18 generic reposts (kind 16)** are now supported across feeds, note cards, and notifications alongside standard kind-6 reposts.
- **NIP-19 relay hints and Fediverse bridge lookup support.** Quoted and reposted notes now read `nevent`/`naddr` TLV relay hints (screened through an SSRF- and Tor-safe relay policy) and can query scoped read-only Fediverse bridge relays (Ditto/Mostr `wss://relay.ditto.pub` and Momostr) in a bounded fallback stage. Relay Management includes per-bridge toggles and quick-add controls.
- **Tap-to-retry on unavailable note cards.** When a quoted or reposted note cannot be fetched immediately (for example while offline or on a slow bridge relay), its placeholder card now shows an in-place retry action and also auto-retries once a relay reconnects.
- **Pinned post badge** (`pinned` + pin icon) on pinned notes in profiles and note cards.

### Changed
- **Collapsed notification rows hide inline image thumbnails** so notification lists keep a steady height without jumping; expanding a notification still shows its attached media.
- **Expanded poll notifications and quoted poll cards** now render full NIP-88 poll options and live vote state.
- Removed the redundant "Zap notifications are on" startup popup while preserving the silent one-time zap filter migration.

### Fixed
- **Blossom upload and quote cursor position in the composer.** Adding a Blossom media upload (or opening a quote note) no longer jumps the typing cursor to a trailing space after the link. The trailing space after uploaded URLs has been removed, the cursor stays at the top prose area above the two-line gap (restoring editor focus and the keyboard automatically after returning from the system media picker), and transient Android IME focus-restoration jumps to the end of the reference block are blocked.
- **Cold-start infinite feed spinner.** Fixed the startup race between account loading, home-layout initialization, and relay subscriptions so feeds immediately subscribe to cached follows on launch, wait for relay subscription collectors before issuing `REQ`s, and reliably clear the loading state when feeds settle.
- **Missing quoted and reposted notes across feeds, threads, profiles, hashtags, and notifications.** Replaced ad-hoc quote refreshes with a shared `QuoteLookupTracker` / `QuoteLookupSession` that coalesces batched ID lookups (≤50 IDs per filter), skips metadata-only relays such as `purplepag.es` for note EOSE, updates legacy Mostr/Ditto endpoints to the live `wss://relay.ditto.pub` endpoint, waits for connecting relays on cold start, wakes disconnected relays out of backoff when needed, and plans multi-wave fallback queries so slow relays cannot starve others.
- **App stability and crash hardening.** Synchronized `FeedSlotEngine` and `SlotRuntime` snapshots to prevent concurrent tab-switch crashes; deduplicated account emissions, synchronized the paged buffer, and chunked SQLite deletes/lookups in `NotificationsRepository` and `EventRepository`; downsampled and recycled bitmaps during BlurHash generation to prevent out-of-memory crashes on high-resolution photos; clamped selection and cleared IME composition on URL-cleaning pastes; switched Coil to a dynamic Tor-safe `Call.Factory` with shared OkHttp pools; guarded corrupt cached event rows; and cleaned up `FrameMonitor` on activity teardown.


## [0.5.8] — 2026-11-14

0.5.7 and 0.5.8 are the same build of the app. 0.5.7's published release notes
carried an empty placeholder section left over from the changelog template, and
the notes are generated from this file at release time, so the fix was a new tag
rather than an edit. Nothing about installing, updating or using the app differs:
the version number is the only change, and the notes below are what 0.5.7 does.

### Added
- **Feed viewer tool**, first entry in the toolbox. Any feed can be read as a tab without being saved to the home screen: your follows, the clock feed at any window from one hour to a day, the suggested accounts, your global relays, a hashtag, another relay's own timeline, a hand-picked group of people, or polls. Each type keeps its own controls next to it — hour chips for the clock, a validating address field for a relay, a paste box for npubs or hex keys for a group — instead of sending you to settings. Tabs close individually, closing a tab drops its relay subscriptions and its notes, and nothing the viewer opens is written to your home layout or your note cache.
- The feeds tool's own tab strip and the home screen's share the same relay planning, mute list, hidden words and NSFW rules, so a note hidden in one is hidden in the other.
- Home feed layouts can be carried between devices by the encrypted backup: export and restore now include the home tab layout, and a backup from an older build restores without disturbing the tabs already on the device.

### Changed
- **The home tab strip shows four feeds at a time, always.** A fifth feed no longer squeezes five tabs into the width of four; instead a single narrow arrow appears on the side that still has a feed behind it, and the strip slides one page when tapped. The arrow is 16 dp wide and the tabs animate, so a layout with more than four feeds looks and behaves exactly like one with four until you page across.
- The strip never scrolls back past the first feed: the left arrow only exists once you have moved right.
- Adding a feed from the feeds tool no longer lists the feeds already on your home screen; that section repeated rows you can already reach from the home screen and cost vertical space on every visit.

### Fixed
- **The feed could spin forever with every relay showing connected.** Three of the four feed loaders had a safety-net timeout that stopped the loader waiting but never cleared the loading state, so any load that ended without producing notes left the spinner turning indefinitely; a second load starting before the first finished could also cancel the first one's timer and start a fresh 15-second wait. All four loaders, and the feed viewer, now share one watchdog: a load that finishes always clears the spinner, a load that times out always ends with a stated reason instead of a spinner, and a superseded load can no longer publish over a newer one.
- The Global feed no longer reloads when its relay set is re-emitted unchanged, which was one of the ways the spinner above could be retriggered.
- A feed waiting on relays now offers a retry after six seconds instead of leaving a bare spinner, and an empty feed shows the reason it is empty rather than a generic "no notes yet".
- The active tab's border no longer shrinks its column, which could leave four bordered tabs a fraction of a pixel short of the strip and read as misalignment.
- Release notes can no longer publish with a template placeholder in them: the script that feeds this file into a release now refuses to emit a section that still contains one.


## [0.5.7] — 2026-11-14

### Added
- **Feed viewer tool**, first entry in the toolbox. Any feed can be read as a tab without being saved to the home screen: your follows, the clock feed at any window from one hour to a day, the suggested accounts, your global relays, a hashtag, another relay's own timeline, a hand-picked group of people, or polls. Each type keeps its own controls next to it — hour chips for the clock, a validating address field for a relay, a paste box for npubs or hex keys for a group — instead of sending you to settings. Tabs close individually, closing a tab drops its relay subscriptions and its notes, and nothing the viewer opens is written to your home layout or your note cache.
- The feeds tool's own tab strip and the home screen's share the same relay planning, mute list, hidden words and NSFW rules, so a note hidden in one is hidden in the other.
- Home feed layouts can be carried between devices by the encrypted backup: export and restore now include the home tab layout, and a backup from an older build restores without disturbing the tabs already on the device.

### Changed
- **The home tab strip shows four feeds at a time, always.** A fifth feed no longer squeezes five tabs into the width of four; instead a single narrow arrow appears on the side that still has a feed behind it, and the strip slides one page when tapped. The arrow is 16 dp wide and the tabs animate, so a layout with more than four feeds looks and behaves exactly like one with four until you page across.
- The strip never scrolls back past the first feed: the left arrow only exists once you have moved right.
- Adding a feed from the feeds tool no longer lists the feeds already on your home screen; that section repeated rows you can already reach from the home screen and cost vertical space on every visit.

### Fixed
- **The feed could spin forever with every relay showing connected.** Three of the four feed loaders had a safety-net timeout that stopped the loader waiting but never cleared the loading state, so any load that ended without producing notes left the spinner turning indefinitely; a second load starting before the first finished could also cancel the first one's timer and start a fresh 15-second wait. All four loaders, and the new feed viewer, now share one watchdog: a load that finishes always clears the spinner, a load that times out always ends with a stated reason instead of a spinner, and a superseded load can no longer publish over a newer one.
- The Global feed no longer reloads when its relay set is re-emitted unchanged, which was one of the ways the spinner above could be retriggered.
- A feed waiting on relays now offers a retry after six seconds instead of leaving a bare spinner, and an empty feed shows the reason it is empty rather than a generic "no notes yet".
- The active tab's border no longer shrinks its column, which could leave four bordered tabs a fraction of a pixel short of the strip and read as misalignment.


## [0.5.6] — 2026-11-11

### Added
- Mutes tab in the follow tool, holding a fully offline mute list (the capability lost when mutes moved there): add by npub or hex, unmute, sync from relays on demand, and an on/off switch that never writes anything to the network.
- Online bookmark folders (NIP-51): creating a folder now asks whether it lives offline (encrypted on the device) or online (published to your relays as a kind 30003 set). A sync action loads every bookmark set and the plain bookmark list your account publishes, so folders made in other clients appear here, and the notes in them are cached for offline reading. Bookmarked notes that belong to no set appear in a "Miscellaneous" folder. House icon marks an offline folder, globe an online one, in the bookmarks tool and in a note's bookmark menu. Notes can be moved between folders on either side, and the destination is written and published before the source is cleared, so a failed publish cannot lose a bookmark.
- Zap notifications (NIP-57): kind 9735 receipts addressed to you now appear as notifications with the amount and the sender's comment, with their own filter, summary-bar stat and icon. Self-zaps are ignored.
- "Beaver Mode" in Blossom settings: one switch that ignores the local server list and re-reads the published Blossom list on every upload, so uploads always follow whatever your account publishes — including changes made in another client. If the published list cannot be read, the local list is used instead of failing the upload.
- Blossom sync now asks Merge or Replace. Merge adds the published servers and keeps yours; Replace makes your list exactly what you publish, removing servers that exist only on this device.
- Every Blossom server, including the built-in defaults, can now be disabled or removed.

### Changed
- "Fit media to its real shape" is on by default. An existing explicit off setting is still respected.
- Followers list offers Unfollow for people you already follow, and Follow only for the rest.
- "Block" is now "mute" everywhere in the interface — the tab, row buttons (MUTE/UNMUTE), note menu, profile button, settings and snackbars — so one word means one thing. What it does is unchanged, and it still edits your relay mute list or your offline mute list depending on which is switched on.

### Fixed
- **Launch crash after upgrading from 0.5.5.** The online-bookmark database migration created an index the entity does not declare, and Room rejects a migrated schema that disagrees with it — as an exception on first database open, before any screen appears. The migration now adds exactly the two columns the entity declares, with the same defaults.
- A Blossom server chosen on the compose screen is now honoured even when it is not in the local enabled list. Tapping an added server used to upload to a different server instead.
- Notes hidden in the follow feed even though the author was followed and not blocked: a block on someone *else* could hide a followed author's note through quote or reply inheritance. Authors you follow are now exempt, and the hidden-note reason distinguishes "you blocked this author" from "references an author you blocked".
- The end-of-thread marker was indented to the rail instead of the note column, clipping its first character.
- Typing and scrolling lag in the Notes tool under Youniversal: text without links is no longer laid out as a row of segments, and the list's sort, grouping and per-folder counts are computed when the notes change rather than on every pass.
- The "N posts hidden by your filters" banner at the end of the follow feed has been removed.
- Bookmarks: the add-folder and sync buttons were drawn on top of each other; they are now separate buttons.
- Bookmarks: syncing from relays now re-downloads the bookmarked notes before rebuilding the lists, so folders load completely on the first sync instead of on the second.
- Bookmarks: deleting a note from an online folder now also removes it from the published list, and notes removed in another client no longer linger.
- Blossom: removing every server now sticks instead of the defaults returning after a restart.


## [0.5.5] — 2026-11-10

### Added
- Youniversal one-tap looks (Paper, Midnight, Porcelain, Rosewood, Signal): each is a coordinated bundle of background, contrast, accent, font, corner radius and text size with a live palette preview. Editing any bundled control by hand switches the active look to Custom; wallpaper colours remain a separate option. Classic mode keeps its historical styles.
- Optional "fit media to its real shape" setting (default off; the fixed box is unchanged). Frames are sized from the dimensions a post states (NIP-92 `imeta` dim) before download, then refined once loaded, with tall items capped.
- Blurred previews: BlurHash codes from posts are decoded tiny and off-thread as the loading stand-in in fitted mode.
- Video posters: when the existing video-thumbnails setting is on, videos show the poster still the post supplies. Nothing is extracted from videos on-device.
- Your own posts now carry `imeta` hints: image and video dimensions are measured at upload and a BlurHash is computed for images, so they render pre-sized in other clients.
- Soft-card feed under Youniversal: posts float on a one-step-deeper page, with card gaps and no between-post hairlines (kept at High contrast, and unchanged in classic).
- Engagement motion: reply/repost/like/zap buttons get a spring press-scale, and a fresh like gets a small pop that only fires on the not-liked → liked edge.
- Reading typography under Youniversal: larger semi-bold author names, a larger and airier body ladder, and scannable engagement counts.

### Changed
- Buttons, toggles and snackbars swap to the kit at the definition, so every settings row, dialog and screen that uses them gets filled/outlined buttons and a modern switch under Youniversal. Classic is unchanged and call sites did not need to change.
- Navigation under Youniversal uses a 12% slide + fade; classic reproduces the library defaults byte-for-byte.
- Notifications and Notes empty screens use the kit's designed empty state under Youniversal, with the write-note action kept. Decoy screens are deliberately untouched.
- Profile action buttons (follow, block, share) are more compact, and the feed and list tab strips are inset so the active tab's rounded border is no longer clipped at the screen edge or overdrawn by the divider.

### Fixed
- Notifications no longer fill with strangers' posts: one eligibility gate now guards every notification entry point, so an event must prove it involves the account (p-tag, or a reply to / quote of / reaction to / vote on your own event) before it can become a notification. Previously the load-older path merged whatever a relay returned, so relays ignoring the `#p` filter injected unrelated posts as mentions and replies.
- A poisoned notification cache now self-heals: saved rows are re-validated through the same gate on launch and invalid rows are deleted, so recovery no longer requires clearing app data. Reaction/repost/vote rows are only dropped when their referenced note is known to be someone else's.
- Tor no longer starts from a stale transport choice on slower devices: relay transport is only rebuilt once settings have loaded, instead of racing the settings read at startup.

### Security
- Removed a certificate-validation bypass before it ever shipped. From an earlier revision of this release, API 26–28 relay connections installed a `TrustManager` whose `checkServerTrusted()` and `checkClientTrusted()` were empty, which accepts any certificate — expired, self-signed or issued for a different host — while still appearing to use TLS. It was described in-code as a "modern CA bundle" patch, but that bundle was never part of the repo. The platform trust store now performs verification, with a connection spec permitting TLS 1.2 and 1.3. No custom `TrustManager` remains in the codebase. This release is the first build to contain the corrected code.

### Verification limits
- The build, signing identity (`social.tbone.fork.beta`), permission set and unit-test suite are verified in CI, including a signed production variant.
- No Android 8 (API 26) device or emulator run was performed. The API 26 changes in this release — core-library desugaring, the TLS 1.2+ spec, fail-closed Tor ordering, dropped BouncyCastle provider registration and the `MediaRecorder` guards — are compile- and review-level only. Live relay, physical-device, TalkBack, process-death and end-to-end Tor isolation checks remain outstanding.


## [0.5.4] — 2026-11-09

### Removed
- Removed the Webxdc mini-app feature and its toolbox, player, bridge, import/export and discovery code. On upgrade, app-owned mini-app files and tool settings are removed. Legacy browser data cleanup is retried if the installed WebView does not support it; the app warns when cleanup remains incomplete. Exported and shared copies cannot be recalled.

### Changed
- Clarified media metadata and Tor upload preflight limitations; follower views disclose incomplete coverage.
- Improved notes, hidden accounts, list replacement safety, links, notifications, accessibility, and player lifecycle. Duress deletion and Tor protections have scope limitations and are not forensic or universal network guarantees.

### Fixed in the release-readiness candidate
- Audio uploads stage their own copy instead of risking deletion of a source file; audio and video staging enforce a stream-size ceiling.
- Note editors stop saves when attachment data cannot be read instead of silently treating failed decryptions as missing data.

### Added in the final release
- Note action menu: "Delete request" publishes a signed NIP-09 (kind 5) deletion request for your own notes, with a confirmation step. Relays are asked to delete the note; they may or may not honour it, and copies may already exist elsewhere.
- Greentext: lines starting with `>` render entirely in green — bright matrix green on dark backgrounds, a deeper green on light and cream — in the write tab and in published notes.

### Fixed in the final release
- Video compression works again: with compression on, every video is downscaled to the upload profile with a capped encoder bitrate and the re-encoded file is always the upload. A successful re-encode also strips embedded metadata (location, device, timestamps). Audio is re-encoded and stripped the same way when either compression toggle is on; the write-tab metadata warning is removed and the Blossom settings wording corrected.
- Replies no longer silently quote a note quoted by the note being replied to; quoting only happens when chosen explicitly.
- Content filters: per-word matching, the NSFW warning veil, grouped hidden-content markers, and quoted notes surviving filters.

### Verification limits
- Device upgrade, process-death, live relay, physical-device TalkBack and end-to-end Tor isolation checks remain necessary.

## [0.5.3] — 2026-11-01

### Added
- Profile pin lists using NIP-51 kind 10001, compatible with other clients; pin or unpin from the note action menu.
- Inline animated GIF playback and a privacy setting to confirm external links before opening them.
- External-wallet Lightning zaps and wallet controls, including invoice verification.

### Changed
- Quote and repost actions share one menu; quote notifications are grouped with reposts.
- Quote rendering and relay lookups now cover more references, including addressable and mention-marked events.
- PIN auto-lock measures time spent in the background rather than active use. Link text and media links support long-press copying.
- The notepad disguise uses the correct launcher alias, and leaving the decoy requires the app PIN when enabled.
- Feed videos load on demand to reduce resource use while scrolling; video controls and relay ownership have also been improved.

### Fixed
- Hardened feed rendering and geohash live delivery/cache handling, plus update checks and downloads.
- Fixed composer account switching and improved signer, wallet, and relay handling.

## [0.5.2] — 2026-10-27

### Fixed
- **Write tab no longer blanks or crashes after posting:** v0.5.1 called two separate `popBackStack()` callbacks (`onClose()` + `onPublished()`) after every successful note/reply. The composer now has exactly one exit action, delivered as a buffered one-shot `Published` effect and protected by a once-only gate. `BonyNavHost` performs a single pop and falls back to the start destination if the composer is ever the only entry, so the graph can never be left with no visible screen.
- **Publishing is failure-safe and cannot fire twice:** `ComposeViewModel.publish()` now rejects rapid double SEND taps, emits navigation on the UI's main-thread collector rather than invoking a captured callback from a ViewModel coroutine, and catches signer, relay, encoding, and database exceptions into the visible error state instead of letting them become uncaught crashes. Draft text is saved across activity recreation.
- **Mention editing crash fixed:** the Write tab's `@npub1… → @name` visual transformation changed text length while using `OffsetMapping.Identity`, which allowed out-of-range cursor/selection offsets. It now builds a monotonic, bounds-safe two-way offset mapping for every replacement; `@npub` mentions also produce NIP-08 `p` tags again.
- **Signer overlay no longer sticks or disappears early:** concurrent Amber/nsecBunker requests are reference-counted, so a background AUTH request completing cannot clear the overlay for an in-flight post (or leave it stuck when completions interleave).
- **Version identity and update checks:** `version.properties` is now the single source of truth for `0.5.2` / `versionCode 68`; `app/build.gradle.kts` feeds it to `BuildConfig`, which Settings, Update Center, and APK verification already use. A release gate checks the tag, Gradle wiring, changelog, and absence of hardcoded runtime versions on PRs and tagged builds. The update version parser's double-escaped digit regex was fixed, so `v0.5.2` is correctly recognized as newer than both mislabeled `0.5.0` and `0.5.1` installs.

### Changed
- `scripts/make-release.sh` bumps `versionCode` and `versionName` together, stages an annotated `v<version>` tag, and verifies the release identity before commit. Tagged CI unit tests are blocking again; the temporary non-blocking workaround from 0.5.0 has been removed.

## [0.5.1] — 2026-10-27

> **Known release-identity issue:** the `v0.5.1` tag shipped an APK whose `versionName` remained `0.5.0` / `versionCode 67`. Release `0.5.2` corrects the identity and adds automated tag/version gates.

### Fixed
- **Write tab buffers + Post pop (0.5.1):** restores v0.4.7 `statusBarsPadding()` + `imePadding()` + `navigationBarsPadding()` outer `Box`/`Column` with bottom 12dp spacer so all Write buttons are reachable (top close/SEND and bottom toolbar/media/poll/preview/server chooser); `BonyNavHost` now passes `onPublished = popBackStack` and `ComposeScreen.SEND` uses `onSuccess = { onClose(); onPublished() }` to return to feed — previous 0.5.0 stayed on tab.
- **Tier-one lightweight/speed audit (no UX break):** `UserAvatar` + `VideoThumbnail` now use `diskCacheKey/memoryCacheKey = url.substringBefore('?').substringBefore('#')` (matches `InlineImage`) to avoid signed Blossom token duplication in Coil `image_cache` — keeps app lightweight and prevents journal bloat.

## [0.5.0] — 2026-10-27

### Added
- **Stealth Notepad front** — launcher alias swaps to `@mipmap/ic_notepad` (`LauncherNotepad`) via `StealthManager.setNotepadFront()`; decoy DB tables `decoy_notes*` fully isolated; `BonyNavHost` shows `DecoyNavHost` until 10× key-storage taps unlock; hidden-account PIN (`AccountRepository` `hiddenPinHash`) keeps accounts out of the list until `unlockHiddenByPin` — verified `e9567b5`.
- **Follow Lists tool** — `toolbox → follow lists` (replaces Settings entry): browse following/followers/blocked for any pubkey, offline lists, Primal centralized cache with disclaimer (`FollowerListRepository`) — verified `6e7be55`.
- **Update Center hardening** — dot moved `CONF→TOOLS` + `ToolboxScreen` badge (`LocalUpdateAvailable`), download verified by `SHA256SUMS.txt` + Tor fallback + renameCopy fallback, `Save to Downloads` via `MediaStore`, collapsed hash-certificate + full changelog (`UpdateCenterScreen`) — pushed `ae5f58e`.
- **Lightweight cache toggle (Option B)** — `Settings → Media → lightweight image cache` (64 MB vs 250 MB) via `AppSettings.imageCacheLightweight` + `BonyApp` `DiskCache.Builder(maxSizeBytes)` + `combine(torEnabled, lightweight)`; keeps app lightweight.
- **Privacy hardening** — `PinHasher` PBKDF2WithHmacSHA256 120k + 16 B salt (`v1$iter$salt$hash`) replaces plain `sha256(pin)` for app/toolbox/hidden/parental PINs with auto-upgrade; `DataWiper` now wipes `cacheDir`/`codeCacheDir`; `NotificationHelper` `VISIBILITY_SECRET`; `AmberSigner`/`RelayConnection` log redaction; `XdcWebView` `safeBrowsingEnabled` + `textZoom=100`; `XdcUpdateStore` quota 5k/5 MB; Tor kill-switch toggle (`TOR_KILL_SWITCH`) in Privacy.
- **Cache hygiene (Option A)** — `CacheCleaner` on cold start prunes `share_cards`/`webxdc_share` >7 d and `tbone_*`/`note_voice_*`/`play_*` >6 h and `updates/*.part` >24 h; staging files deleted right after Blossom upload (`ComposeViewModel`, `ProfileEditViewModel`) and voice `play_*.m4a` on completion — fixes 3 GB accumulation — `91401ec`.

### Changed
- **Lightning presets removed** — mirrors Monero NIP-A3 `payto` handoff only; external wallet `lightning:`/`monero:` intents, copy/QR fallback — `3897dc5`.
- **Media cache keys** — `NoteContent.InlineImage` `diskCacheKey/memoryCacheKey = url.substringBefore('?')` to stop signed Blossom tokens persisting; gallery save re-encodes JPEG 92 to strip EXIF (`ImageViewer`).
- **PIN timeout 1–30 m** — `AppSettings` `PIN_AUTO_LOCK_MINUTES`/`TOOLBOX_PIN_AUTO_LOCK_MINUTES` + `AppLockManager`/`ToolboxLockManager` timed `Job` + `MainActivity.onStart/onForegrounded` re-check; compact popup `AutoLockMinutesRow` in `SettingsScreen`/`ToolboxSettingsScreen` — `560426a`.
- **WebXDC toolbox removal** — `ToolboxViewModel.removeXdcTool()` + `ToolboxScreen` `remove` pill + confirm dialog for `xdc_*` tools (besides `XdcScreen` overflow).

### Fixed
- **6-fix batch (560426a):** WebXDC tools removable in Toolbox; PIN auto-lock timeout; sensors/proximity audit (only `INTERNET`/`REQUEST_INSTALL_PACKAGES`/`RECORD_AUDIO`/`POST_NOTIFICATIONS`/`SCHEDULE_EXACT_ALARM`/`RECEIVE_BOOT_COMPLETED`/`WRITE_EXTERNAL_STORAGE` remain); TAP_TO_LOAD inline-first-tap (`revealedImages/Videos` → second tap opens viewer); `VideoViewer`/`InlineVideo` `FLAG_KEEP_SCREEN_ON` + `keepScreenOn`; `ThreadScreen.ThreadDeadEnd` no longer draws rail below terminator.

## [0.4.7] — 2026-10-24

### Added
- **Amber onboarding install**: show a Get Amber action only when the NIP-55
  signer is absent; resolve the current publisher-signed Zapstore asset, verify
  its event, APK hash, package/version, and signing certificate, then hand it to
  Android's installer with the required user approval.
- **External-wallet payments**: Lightning zaps now request and verify a NIP-57
  BOLT-11 invoice before handing it to a compatible wallet app; if no app handles
  it, the invoice remains available to copy or scan as a QR code. Monero xaps
  read author-published NIP-A3 kind-10133 `payto` targets and hand off via
  `monero:` with the same copy/QR fallback.
- **Wallet toolbox tabs**: Lightning zap visibility, default sats and editable
  presets are independent of any wallet connection; a Monero tab toggles xap
  buttons and publishes/removes the user's address while preserving unrelated
  and unknown payment-target tags.
- **webxdc tool**: a single-player Webxdc player in the toolbox.
  - Four sources: a local `.xdc` file, the official webxdc.org store
    (`apps.testrun.org`), a T-Bone feed filtered to `npub1zx2…ftjq`, and a
    Nostr-wide feed of kind-1063 `#m application/x-webxdc` events (NIP-DC).
  - Saved apps run offline in a sandboxed WebView served from a private
    per-app origin; every other origin is denied and HTML carries a strict CSP.
  - The full `window.webxdc` API is implemented on-device (updates journal,
    file import, share sheet, no-op realtime channel) so mini apps behave
    normally without peers.
  - Any saved app can be added as a toolbox tool with a custom name and one of
    20 generic icons; tools are reorderable like the built-in ones.
  - Adds `#m` filter support to `Filter`, NIP-DC kind constants, and
    `XdcArchive`/`XdcUpdateStore`/NIP-DC parsing with unit tests, including an
    opt-in check against ten real `.xdc` packages.

### Removed
- **Nostr Wallet Connect (NIP-47)**: removed the in-app NWC connection, invoice
  payment, pending/paid/uncertain zap tracking, and NIP-04 fallback. Existing
  zap amounts are migrated to the external-wallet Lightning settings; saved
  NWC credentials and payment-outcome state are cleared on upgrade.

### Changed
- **Profile search** now compatibility-normalizes Unicode names (including
  mathematical/fancy alphabets, fullwidth text, and accents) and queries common
  styled forms on the search relay.
- **Feed tab icon**: `FOLLOWS` is a single person — head and one open shoulder
  arc — with no plus. The plus merged into the shoulder line at 17 dp and read
  as "add someone" rather than "the people you follow".

### Fixed
- **Profile search results** no longer flash away while the relay is queried;
  relay results merge into cached matches, search subscriptions are scoped and
  closed at EOSE/timeout, and empty results stay in a searching state until the
  relay responds.
- **Profile metadata race**: older relay events or delayed database writes can
  no longer replace a newer profile in memory or Room.
- **Video uploads work again.** v0.4.1 capped clips at 10 MB and rejected
  anything larger *before* transcoding, so nearly every phone video failed with
  "video is too big". The cap is now a 500 MB safety ceiling; clips at or below
  1280p are uploaded untouched, taller ones are downscaled to 1280p, and a
  finished transcode is never discarded for exceeding a client-side limit.
- **Share cards with video** now contain a properly sized, playable video
  window. Media3's compositor already scales an overlay by
  `overlaySize / backgroundSize`, and the video input was additionally scaled
  by `slot / output`, double-scaling it into a squashed postage stamp. The
  export is also validated (duration and video track) before it is shared, so a
  dud MP4 falls back to the still card instead of being sent.
- **Share cards of image-only notes** no longer print "(empty note)". The text
  slot is left empty and the media section moves up into the space.
- **Share cards of quoted notes** draw a real quote block — quoted author, the
  first lines of their note and an attachment count — instead of the raw
  `nostr:nevent1…` string, mirroring the in-feed quote card.
- **Profile**: removed the dead ⋯ overflow button in the top bar, and the
  identity QR view now has breathing room beneath the code.
- **Feed timeline restored.** The previous feed-strip refactor dropped the
  `LazyColumn` branch of `FeedScreen`, so any feed with notes rendered an empty
  screen. The timeline is back; `FOLLOWS` and `CLOCK` share one footer
  (`─── pull to refresh ───`) and `CLOCK` has no older-paging of any kind.

## [0.4.1] — 2026-10-19

### Added
- A Settings updater that checks GitHub releases daily, keeps an update badge
  visible, verifies APK package/version/signing compatibility, and hands a
  compatible update to Android's installer.
- A 1–20 minute Global auto-load interval selector and quick-add support for
  Citrine's local relay at `ws://localhost:4869` (also works with Tor enabled).
- Videos in the profile media gallery, opened with the regular in-app player;
  notification rows now preview attached pictures.

### Changed
- Global live events are buffered to keep the bottom of the feed stable, and
  manual/automatic newer-note loading pages through available results. Older
  Global and notification history now use cursor-based paging.
- Share cards give attachments a larger, dedicated section. Notifications keep
  the latest 200 rows in the local cache and calculate rolling 24-hour totals.
- Added top insets for Bookmarks and usable top/bottom insets in the image viewer.

### Fixed
- Video uploads are capped at 10 MB and report “video is too big”; video-card
  export failures fall back to the still image, and player errors no longer
  crash the viewer.
- Blocked authors' replies/reposts and notes matching hidden words are filtered
  across feeds, profiles, threads, and notifications.
- Reply markers no longer clear on ordinary account updates; quoted and
  reposted notes retain clickable reply context.

## [0.4.0] — 2026-10-18

### Added
- Encrypted, device-local bookmarks with folders, note-menu saving, card views,
  nevent links, moving, drag-reordering, and folder-wide quoted notes.
- Global-feed paging for the 100 newest notes, older pages, and newer results;
  Search now resolves pasted `note` and `nevent` references into events.
- Blossom-aware share cards with author pictures, image galleries, and playable
  video-card exports; media tiles scale with note length and the old share-card
  tagline is removed.

### Changed
- Bounded poll option scrolling throughout Compose and the feed, with a maximum
  of 30 poll options; tagged-user handles use the accent color.
- Blossom upload progress is visible, and uploaded/quoted references sit below
  the writing line in Write; the Write tab’s bottom buffer is halved.

## [0.3.55] — 2026-10-17

### Added
- Sync Blossom defaults from the account’s published server list and preferred
  relays from its NIP-65 relay list; new installs start with an updated relay
  set without changing existing accounts’ relay settings.
- Multi-select image, video, and audio uploads in Write, plus persisted poll
  votes, immediate full results after voting, and support for up to 20 options.

### Changed
- Moved encrypted note/calendar backup controls to Toolbox. Long checklist
  items now expand individually with “See more,” restoring the prior overall
  checklist preview behavior.
- Refined Youniversal Cream and light-theme accents, constrained feed tabs,
  centered the emoji selector, and added lower-screen content spacing.

### Fixed
- Folder pins now stay scoped to their folder; opening a reposted reply no
  longer scrolls the feed back to it after the reader scrolls away.
- Thumbnail-backed videos have a centered play affordance, and thread focus
  waits for the target context before scrolling.

## [0.3.54] — 2026-10-16

### Added
- Opt-in audio compression for Blossom uploads; compression is off by default,
  and the original file is retained when compression is disabled or does not
  produce a smaller AAC/M4A file.
- Optional block-poster and NIP-56 report actions in note menus, plus the
  default-enabled T-Bone NIP-89 client tag with a setting to disable it.
- Polished shareable PNG cards for full notes and passphrase-encrypted backups
  for notes, checklists, attachments, folders, and calendar events.
- Offline calendar reminders scheduled with Android alarms, including reboot
  and time-change rescheduling without Google services or a persistent app
  connection.

### Changed
- Long checklist previews now expand and collapse; prose inputs request
  sentence capitalization, and Youniversal feed tabs and bottom navigation
  adapt to screen width and Android navigation insets.
- Enlarged the note overflow control and removed the redundant Copy JSON menu
  action; copying remains available in the raw-event dialog.

### Fixed
- MPEG audio MIME aliases such as `audio/mpeg` and `audio/mpga` are handled by
  the inline player.
- Backup export now captures notes, folders, attachments, and calendar events
  from one consistent database snapshot before encryption.

## [0.3.53] — 2026-10-15

### Added
- Replaced the note/reply share button with a compact three-dot action menu:
  inspect or copy raw event JSON, copy note text, share a NIP-19 `nevent`,
  open the event on Njump, or hand it to another installed Nostr client.
- Added Blossom-backed audio uploads from the voice recorder and Write composer,
  plus a compact inline player for common audio formats that never displays the
  source URL.
- Voice anonymization can now be re-rendered repeatedly from the same original
  recording after adjusting the distortion controls.

### Fixed
- The recorder's kind-1 upload action now opens Write and uploads the actual
  audio file instead of posting a local-file placeholder.
- Video uploads now fall back to the original file and detected MIME/extension
  when Media3 cannot transcode a less-common container or codec.
- Added extra lower-edge spacing to the voice recorder actions.

## [0.3.52] — 2026-10-13

### Changed
- Replaced the outdated README with a concise overview of T-Bone's current features, installation, and source build.
- Release automation now builds and publishes only the release APK and its checksum; it no longer builds or publishes a debug APK.

## [0.3.51] — 2026-10-13

### Added
- A softer Nunito Sans typeface option in Youniversal, alongside the default
  standard system sans-serif.

### Changed
- Youniversal's adjustable rounded shapes now carry through legacy T-Bone
  screens, with rounded surfaces as the default while that theme is active.
- Image, video, and thumbnail preview frames in Youniversal now fit their
  media's aspect ratio within the existing display bounds.

### Fixed
- Youniversal text-size changes now scale SP-based typography across the app,
  including the legacy screen styles.
- NIP-57 invoice callback requests now include the matching bech32 `lnurl`
  parameter as well as the signed zap request, fixing compatible NWC-backed
  Lightning zaps that were rejected by LNURL services.

## [0.3.50] — 2026-10-09

### Fixed
- NWC zaps no longer get stuck as "status unknown" when the wallet is slow.
  A payment whose wait ran out keeps its request open, and when the wallet
  answers later the note is unblocked with the real outcome (confirmed or
  not paid) instead of staying locked until the app is reinstalled.
- Lightning payments are no longer sent with a 3-minute NIP-40 expiration, so
  a slow or briefly offline wallet can still answer them.

### Changed
- Home tab: the brightness (◐) and search (⌕) icons are larger with a bigger tap target.
- Home tab: tapping the "t-bone" wordmark opens Settings.
- Home tab: tapping the avatar and name opens your profile. The account
  switch symbol (↕) is now a small button on the right.

## [0.3.49] — 2026-10-06

### Fixed
- Reworked the NIP-57/NWC zap path against Amethyst's current implementation:
  LNURL endpoints are canonicalized into the required bech32 `lnurl` tag,
  callback and invoice responses are validated, and the invoice amount must
  exactly match the requested sats before a wallet can be asked to pay.
- Corrected NIP-44 v2 message-key derivation to the Amethyst/spec HKDF-expand
  sequence and added a cross-implementation vector test; this was preventing
  NIP-44-capable wallets from decrypting T-Bone's payment requests.
- NWC requests now negotiate NIP-06 metadata when a wallet advertises it,
  preserving structured zap metadata for wallet notifications without sending
  unsupported fields to other NWC wallets.
- Relay-backed wallet notifications are live and session-only, de-duplicated,
  and limited to payments carrying structured Nostr zap metadata.
- Successful payments provide one-shot haptic feedback and leave the lightning
  zap control in the configured accent color. Completed notes may be zapped
  again with another amount; uncertain payments remain blocked to prevent
  duplicate settlement.
- LNURL and NWC cancellation/error paths now fail safely without swallowing
  coroutine cancellation; malformed or unauthenticated post-dispatch payment
  responses are treated as financially unknown, never as retryable failures.

### Changed
- Added strict BOLT11 amount/checksum parsing and HTTPS-only LNURL callbacks.
- Documented Minibits as the test wallet while retaining support for proper
  NWC-compatible Lightning wallets.

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
