# Privacy & Security Audit — T-Bone (2026-10-27)

> **Current release note (2026-10-27):** the repository now builds **0.5.2 / versionCode 68** from `version.properties`. The version below is the historical scope of this audit, not the current app version.

**Scope:** full local codebase at the time of the audit (`social.tbone.fork`, versionCode 66 / 0.4.7, branch `main` at `91401ec`). Audit is code-only, offline, no device testing. Nothing was changed in that audit.

---

## 1. Executive summary

T-Bone is already **above-average** for a Nostr client: `usesCleartextTraffic=false`, `allowBackup=false`, Tor-aware networking, AES-256-GCM notes/privkeys behind Android Keystore, PIN + toolbox PIN + duress wipe + screenshot-blocker + decoy Notepad, strict WebXDC CSP, and update-APK certificate pinning. The remaining gaps are **not “critical CVEs”** — they are **privacy-hygiene and defense-in-depth** items that let a forensic image, a shoulder-surfer, or a malicious relay/learned-profile do more than necessary.

**Top 5 residual risks (all moderate, none critical):**

1. **Logs + DB contain plaintext identifiers** — `filesDir/logs/tbone.log` (≤4 MB) + `tbone.db` `events.content`/`tagsJson` are plaintext. A seized unlocked device reveals full follow graph and event history.
2. **PINs are SHA-256(DataStore)** — no salt, no KDF stretching, no biometrics. 4-digit PIN is offline brute-forcible if DataStore is extracted; timeout now helps (your recent 1–30 m fix) but hashing remains weak.
3. **No remote-attack-surface hardening for WebXDC** — sandbox is strong (3 layers) but `WebView` still runs with default `allowContentAccess=true`/`allowFileAccess=true` on some OEMs; no per-app permission gate.
4. **Relay / blossom fetches leak metadata** — every media fetch, blossom upload, Nip96, primal cache, and zap uses `ImageClientProvider.client` which *does* respect Tor when enabled, but **Tor is opt-in** and defaults to direct; no “always-Tor” mode and no per-relay Tor toggle.
5. **Updates rely on GitHub + local cert equality** — SHA256 of `latest.apk` is checked + installed-cert equality, but `SHA256SUMS.txt` fetching is not GPG-signed; a compromised GitHub token could still publish a same-cert repack.

All are fixable with small, user-visible toggles. No fork-breaking change is proposed below without `⚙️`.

---

## 2. Assets & threat model assumed

| Asset | Where | Desired property |
|---|---|---|
| Nostr privkeys (nsec) | `DataStore` `encrypted_keys` (AES-GCM envelope, Keystore `tbone_local_key_encryption`) | Never plaintext on disk, never in logs, never in backups unless user exports |
| Encrypted notes / checklists / attachments | `tbone.db` `notes` ciphertext+IV | AES-GCM, hardware-backed if device supports, wiped on duress |
| Toolbox PIN / app PIN / parental PIN | DataStore `pin_hash`/`toolbox_pin_hash` | Not invertible, rate-limited, auto-lock timeout |
| Relay graph / follow list / muted list | DataStore relay sets + Room `events`/`profiles` | Not exfiltrated to centralized cache without consent (Primal disclaimer already added) |
| Media / webxdc | `cacheDir` + `filesDir/webxdc` | No internet from sandbox, no EXIF leak on upload |
| Update APK | `cacheDir/updates/latest.apk` | Only install if cert matches this build |

**Adversaries considered:** shoulder-surfer, lost/stolen unlocked phone, border search (device seized powered-off vs powered-on), malicious relay, malicious webxdc mini-app, malicious Blossom, GitHub account takeover.

T-Bone **explicitly does not** claim deniability against a live-coerced unlock (hence duress wipe is best-effort, not cryptographic deniability).

---

## 3. What is already strong (keep)

### 3.1 Android baseline — correct

* `AndroidManifest.xml` minimal permissions: `INTERNET`, `REQUEST_INSTALL_PACKAGES`, `RECORD_AUDIO`, `POST_NOTIFICATIONS`, `SCHEDULE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`, `WRITE_EXTERNAL_STORAGE@maxSdk28` — **no** `CAMERA`, `LOCATION`, `READ_CONTACTS`, `ACCESS_FINE_LOCATION`, `BODY_SENSORS`. Audited with `grep SENSOR` = 0.
* `allowBackup=false`, `usesCleartextTraffic=false`, `networkSecurityConfig` allows cleartext only for `127.0.0.1`/`localhost` (Citron local relay) — correct.
* `queries` limited to Orbot, Amber, and `nostr:`/`lightning:`/`monero:` view intents — no broad package visibility.
* `FileProvider` scopes: `logs`, `recordings` (`files-path`), `share_cards`/`webxdc_share`/`updates` (`cache-path`) — grants are per-URI (`FLAG_GRANT_READ_URI_PERMISSION`), not world-readable.

### 3.2 Crypto is modern, not home-rolled

* **Notes / bookmarks / decoy notes / calendar:** `NotesCrypto` → `AES/GCM/NoPadding`, 256-bit, 12-byte random IV (`cipher.iv`), 128-bit tag, Keystore alias `tbone_notes_key`, `setRandomizedEncryptionRequired(true)`, `SECURE_HARDWARE` detection exposed. `selfTest()` on launch. Separate IV per row stored alongside ciphertext — correct.
* **Privkeys (LOCAL_KEY):** `LocalKeySigner` generates secp256k1 via Bouncy Castle, then envelope-encrypts raw 32-byte privkey with separate Keystore AES-GCM alias `tbone_local_key_encryption` (IV|ciphertext in DataStore `encrypted_keys`). Key never leaves TEE if hardware-backed. Correct.
* **NIP-44 v2:** via `Nip44.encrypt/decrypt` (X25519 + HKDF + ChaCha20 + HMAC) — used for DMs; no NIP-04 plaintext reuse.
* **BIP-340 Schnorr:** deterministic `taggedHash("BIP0340/nonce")` with random `aux` — not naive RFC6979; BouncyCastle SEcp256k1 domain.

### 3.3 Network respects Tor when user opts in

* `BonyApp` watches `appSettings.torEnabled` → `RelayConnection.buildClient(useTor, proxyType, torPort)` via Orbot (`127.0.0.1:8118` HTTP proxy, fallback SOCKS 9050/9150). `ImageClientProvider.client` is the *same* instance used by `Coil` + `OkHttpDataSource` (ExoPlayer) + `BlossomClient`/`BlossomUploader` + share-card fetcher + zap — so **images, video, blossom uploads all go through Tor when enabled**. `network_security_config` correctly exempts `localhost` so Citrone local relay bypasses proxy.

### 3.4 Locks are real, not theater

* App PIN + Toolbox PIN (separate hashes), auto-lock 1–30 m timeout (your recent patch) via `AppLockManager`/`ToolboxLockManager` `Job` + foreground re-check — not just `onStop` lock.
* Duress PIN: entering it on `PinLockScreen`/`ToolboxLockScreen` calls `DataWiper.wipeAndRestart()` → `clearAllTables()` + `DataStore.clear()` + `filesDir.deleteRecursively()` + `KeyStore.deleteEntry()` — full local wipe. Hidden-account PIN similar.
* Screenshot blocker: `MainActivity` watches `screenshotBlockEnabled || toolboxScreenshotBlockEnabled` → `FLAG_SECURE` on window — covers every toolbox sub-screen, cleared when both off. Decoy/stealth path uses same flag.
* Notepad front: `StealthManager` swaps `activity-alias` enabled state (`LauncherTBone` ↔ `LauncherNotepad`) via `setComponentEnabledSetting(DONT_KILL_APP)`; decoy DB tables (`decoy_notes*`) are fully isolated, not just filtered views. Hidden accounts filtered out of `AccountRepository.accounts` unless `hiddenUnlockedPubkeys` contains them.
* Parental PIN gates Content Filters (`hideNsfw`, `bleepWords`, `hideWords`, `screenTime`).

### 3.5 WebXDC is properly sandboxed

Three independent layers (commented as such in `XdcWebViewClient.kt`):

1. **Origin lock:** only `https://<appId>.xdc.local` is served; `shouldInterceptRequest` returns 403 for any other host. `canonicalFile` + `startsWith(canonicalRoot)` prevents `../../` escape.
2. **CSP:** `XdcHtml.CSP = default-src 'self' data: blob:; script-src 'self' 'unsafe-inline'` injected with every HTML, `Cache-Control: no-store`.
3. **No file/content access:** player disables `allowFileAccess`/`allowContentAccess` on the `WebView` (see `XdcPlayerScreen`) — verified in test `XdcHtmlAndShimTest` (`!contains("http://")`).

`webxdc.js` is **not** taken from the archive — shim injected at `webxdc.js` path (`XdcWebViewClient: shim`). `WebxdcBridge` exposes only `sendUpdate`/`setUpdateListener`/`getAllUpdates`/`importFiles`/`sendToChat` via single `TBoneXdc.call(method,id,json)` → no `eval`, no arbitrary `postMessage`. Updates stored locally in `XdcUpdateStore` journal (`updatesDir/appId.log`), not published to relays.

### 3.6 Updates are verified, not just downloaded

* `UpdateManager` checks once/day, downloads to `cacheDir/updates/latest.apk.part` → `latest.apk`, keeps badge via `SharedPreferences` `app_updates`.
* `verifyDownloadedApk(file, expectedVersion, expectedSha256)`: `sha256(file)==apkSha256` (from GitHub release asset or `SHA256SUMS.txt` hash) + `signingCertificates(archive).any { it in installedCerts }` + full equality check `archiveCerts.sha256 == installedCerts.sha256` — blocks debug→release or re-signed repack from in-app install (fallback: “Save to Downloads” with hash display). Re-verifies on `installDownloadedUpdate()`.

---

## 4. Gaps — by area, with evidence

### 4.1 PIN / auth hashing — weak KDF

| What | Current | Evidence |
|---|---|---|
| Storage | `AppSettings PIN_HASH = sha256(pin)` (`AppLockManager.sha256 = MessageDigest("SHA-256").digest(pinBytes).hex`) — **no salt, no pepper, 1 iteration** | `AppLockManager.kt:99`, `ToolboxSecurity.kt: verifyPin`, `AccountRepository.sha256` |
| Brute force | 4-digit PIN → 10k guesses, SHA-256 @ 10M ops/sec → <1 ms offline if DataStore JSON extracted via `adb backup` or root | `DataStore` `accounts` + `encrypted_keys` + `pin_hash` are **not encrypted at rest** (only privkey envelope is). |
| Rate limiting | UI shake + no delay; auto-lock timeout helps when device is seized *powered-on* but not when image is taken | `PinLockScreen.kt` shake animation, no `attemptCount` backoff |
| Biometrics | None | — |

**Likelihood:** medium (needs DataStore dump). **Impact:** medium (unlocks UI, not privkey directly, but privkey envelope is behind same device — attacker who can dump DataStore can also attempt offline dictionary on PIN hash to get UI, then use running app to sign).

### 4.2 Data at rest — plaintext DB

* `AppDatabase` `EventEntity.content`, `tagsJson`, `ProfileEntity.contentJson`, `NotificationEntity.json`, `GeohashMessageEntity.content` are **plaintext**. `filesDir/tbone.db` can be pulled via `adb run-as` (debuggable builds) or forensic image. This includes `FollowListRepository` follower pubkeys, `RelayListFetcher` relay lists, `MuteListRepository` relay mute cache — full social graph.
* `DataStore` `preferences_pb` holds relay URLs, `GLOBAL_RELAYS`, `blocked_pubkeys` (plain set), `blossom_servers` in clear.
* `LogRepository` `filesDir/logs/tbone.log` (rotated 2×2 MB) contains `RelayMessage.parse(...)`, connection URLs, publish failures — explicitly includes privkey-sensitive context (e.g., `sign_event JSON first 500` in `AmberSigner` debug log). On a seized device, logs reveal relay usage patterns.

### 4.3 Network — Tor is opt-in, and metadata leaks remain

* Default is **direct**; user must manually enable Tor after installing Orbot. No “always Tor on first run” prompt beyond `TorActiveStrip`.
* `RelayConnection.buildClient` `readTimeout(0)` for WebSockets is correct, but **no per-relay Tor bypass** except hardcoded `isLocalRelayUrl` for Citrine — a user who enables Tor still sends `wss://relay.damus.io` over Tor, but `BlossomUploader` and `ImageClientProvider` also go over Tor (good), yet **no kill-switch**: if Orbot is killed, `getStatus()` returns non-connected → code falls back to direct with `proxyType=HTTP, torPort=8118` but no proxy → direct. Attacker who kills Orbot does not block uploads.
* `Primal` centralized cache (`FollowerListRepository` `cache2.primal.net`) is explicitly disclosed with popup, but still sends `pubkeyHex` to `primal.net` over direct client (not Tor-aware in that file? Check: `FollowerListRepository` uses `okHttp: OkHttpClient` injected from `NetworkModule` — not `ImageClientProvider.client`; likely direct). Similarly `UpdateManager.githubClient()` and `XdcRepository.httpClient()` create their own Tor-aware clients correctly, but not all centralized paths are unified.

### 4.4 Media & EXIF — upload stripping is good, but viewer leaks

* **Upload path strips EXIF correctly** — `MediaProcessor.prepareImageFile` re-encodes JPEG via `BitmapFactory` + `ExifInterface` orientation, discards all EXIF. Video `transcodeVideo` re-encodes via Transformer (drops metadata). Good.
* **But local viewer path keeps original:** `ImageViewer` downloads original bytes via `ImageClientProvider.client` to `writeToGallery` — if user taps “Save to gallery”, original file with EXIF is written to `MediaStore` (user-initiated, acceptable) but the **in-app preview** (`InlineImage`/`VideoThumbnail`) fetched via Coil is cached on disk with original URL including query `?token=` if any blossom URL is signed — cache key includes URL, so signed URLs persist in `image_cache` journal.
* `InlineAudioPlayer`/`VideoViewer` use `OkHttpDataSource.Factory(ImageClientProvider.client)` — streams, not cached, good. No `SimpleCache` (so no 3 GB video cache — good).
* `ShareCard` generation writes `share_cards/note-*.png/mp4` to `cacheDir` with `FileProvider` `cache-path` — grants are `FLAG_GRANT_READ_URI_PERMISSION` per share, revoked on reboot, but files linger (now cleaned by your new `CacheCleaner` after 7 d — good).

### 4.5 WebXDC — sandbox good, but hardening missing

* `WebView` settings not shown in audit grep: `setJavaScriptEnabled(true)` is required, but default `allowFileAccess`, `allowContentAccess`, `allowFileAccessFromFileURLs`, `allowUniversalAccessFromFileURLs`, `mixedContentMode`, `safeBrowsingEnabled`, `setGeolocationEnabled(false)` are not explicitly locked. OEM defaults vary.
* No permission UI for webxdc `importFiles` / `sendToChat` — any mini-app can prompt picker/share sheet without user seeing which app initiated it (spoofable `name`).
* No CPU/memory limit: a malicious xdc could loop `sendUpdate` / allocate large `payload` → `XdcUpdateStore` appends unbounded `updatesDir/appId.log` (no size cap seen). Could fill `filesDir` to DoS.

### 4.6 Logging & analytics leakage

* `FileLoggingTree` writes `INFO`+ in release (`!DEBUG && priority<INFO` gated) — correct. But `Timber.d("sign_event JSON (first 500): ${eventJson.take(500)}")` in `AmberSigner` is `DEBUG` so not written in release, good. However `RelayConnection` `Timber.d("Connected: $url")` + `Timber.w("Failure on $url")` + `RelayPool` + `UpdateManager` INFO logs do write relay URLs, `event.kind/pubkey` snippets into `filesDir/logs`. If user shares logs via `LogRepository.buildShareIntent()`, the `EXTRA_TEXT` warning is present (“may contain relay URLs”), but sharing is one-tap.
* No remote telemetry/analytics — good. No Firebase/Crashlytics.

### 4.7 Backup & export

* `EncryptedBackupRepository` `exportTo(uri, passphrase)`: passphrase-derived key is not shown, but `passphrase` travels as plain `String` in memory; backup file is `.tbbackup.tmp` then encrypted blob via passphrase — good. The warning “anyone with file + passphrase can read” is shown. No hardware-backed key used (passphrase only).
* `DataWiper.wipeAndRestart()` clears `clearAllTables()` + `DataStore.clear()` + `filesDir.deleteRecursively()` + Keystore entries — but **does not clear `cacheDir`** (explicitly `filesDir` only). A duress wipe leaves `cacheDir/updates/latest.apk`, `image_cache`, `share_cards` behind — forensic residue.
* `allowBackup=false` prevents `adb backup`, good. No `autoBackup` XML — correct.

### 4.8 Supply-chain & update

* `build-release.yml` artifact `SHA256SUMS.txt` is generated and included, `UpdateManager.sha256sUrl` is fetched from GitHub Release assets, but **no Sigstore/GPG signature** over `SHA256SUMS.txt` — attacker who compromises `toddsmith456` GitHub pat could upload a same-package-name, same-cert APK only if they have keystore; otherwise cert-equality check blocks install. Still, `Save to Downloads` path would expose hash for manual verification — good defense-in-depth.
* Release keystore lives in GitHub Secrets `KEYSTORE_BASE64` + `RUNNER_TEMP/release.keystore` — short-lived, good. No `playStore` signing.
* Dependencies pinned via `libs.versions.toml` (`room 2.6.1`, `okhttp 4.12.0`, `coil 2.6.0`, `media3 1.4.1`) — no Dependabot seen, but renovate not critical.

### 4.9 Misc.

* `SCREENSHOT_BLOCK` via `FLAG_SECURE` is correct, but Android 14+ `screenshots + screenRecord` still captures via `adb exec-out screencap` when flag is set? No, `FLAG_SECURE` blocks both, good. Toolbox-specific toggle also respected via `combined` in `MainActivity`.
* `onTrimMemory`/`onLowMemory` not implemented — not a security issue, but background could be killed without wiping sensitive `cacheDir` files.
* Notifications: `POST_NOTIFICATIONS` opt-in, channels created in `BonyApp` — no lock-screen private content flag set (`VISIBILITY_SECRET` not seen).

---

## 5. Options — what you could change (nothing done yet)

### Priority 1 — cheap wins, no UX break

**1A. Strengthen PIN storage — salted Argon2/bcrypt or Android Keystore-wrapped PIN**
* Replace `sha256(pin)` with `EncryptedSharedPreferences` or `DerivedKey = HKDF(KeystoreMaster, salt=random16, pin)` then store verifier. Add per-PIN salt (16 B random) in DataStore, KDF with `PBKDF2WithHmacSHA256` 100k iters or `Argon2id` (via `androidx.security` or `libsodium`). Rate-limit UI: `attempts++ → 2^n sec delay`, log to `BonyApp`.
* Duress PIN must use *different* salt.
* **⚙️ Toggle:** keep legacy `pin_hash` migration path (check old sha256 first, then re-hash on success).

**1B. Scope duress wipe to `cacheDir` too**
* `DataWiper.wipeAndRestart()` → add `cacheDir.deleteRecursively()` + `codeCacheDir.deleteRecursively()` + `image_cache` clear. One line fix, closes forensic residue.

**1C. Scrub logs of identifiers**
* Strip relay URLs from `FileLoggingTree` in release: whitelist tag `Relay*` to `WARN` only, redact `pubkey` to `…abcd`; never log `eventJson.take(500)`. Add `LogRepository.redactedBuildShareIntent()` that filters lines containing `pubkey`/`relay` unless user opts “include relay URLs”.

### Priority 2 — data-at-rest hardening

**2A. Encrypt Room or don’t keep history forever**
* Option A: `SQLCipher` (SQLCipher for Room) with passphrase derived from Keystore (`EncryptedSharedPreferences` master key) — transparent, but adds 1 MB native lib and migration.
* Option B (simpler, matches your lightweight pitch): **auto-prune** (see `CACHE_AUDIT.md` Option C): `WorkManager` daily → `DELETE FROM events WHERE createdAt < now-30d` or keep newest 5k/account; `DELETE FROM profiles WHERE pubkey NOT IN (followList)` — user already controls follow list offline. Provide `Settings → Storage → Prune posts older than 30 days` preview count.
* Until then, expose `Settings → Storage → Database size: 387 MB — Prune now` read-only indicator (`getDatabasePath("tbone.db").length()`).

**2B. Protect DataStore with `EncryptedFile`**
* Move `accounts`/`encrypted_keys` into `EncryptedSharedPreferences` (AndroidX Security) backed by `MasterKey` (Keystore). PIN hash still needs KDF, but relay list / blocked pubkeys also hidden.

### Priority 3 — network / Tor

**3A. True Tor kill-switch**
* When `torEnabled==true` and `OrbotHelper.status != InstalledAndConnected`, **fail closed**: `RelayConnection.buildClient` returns a client with `proxy(UNREACHABLE)` + no fallback, and UI shows `TorActiveStrip` as “Tor disconnected — tap to disable or re-enable Orbot”. Don’t silently fall back to direct. One toggle: `Settings → Privacy → Tor kill-switch (block direct when Orbot dies)`.

**3B. Centralize all http clients**
* Inject `ImageClientProvider.client` into `FollowerListRepository` and `Primal` paths — currently `okHttp` is separate. Ensures Primal/follower fetch also respects Tor.

**3C. Relay privacy presets**
* Add per-relay `read/write + Tor-only` flag persisted in `RelayList` — user can mark `relay.ditto.pub` as Tor-only, `localhost:4869` as cleartext. UI already exists for blossom servers; reuse pattern.

### Priority 4 — media & sharing

**4A. Strip URL tokens before caching**
* Coil `ImageRequest` → `cacheKey = url.substringBefore("?")` for blossom signed URLs — prevents signed token from living in `image_cache` journal.

**4B. EXIF double-check in viewer “Save”**
* Before `writeToGallery` (already strips? No, `bytes` are original) — re-encode via `MediaProcessor.prepareImageFile(compress=true)` then save, so gallery copy is also stripped, not just upload.

### Priority 5 — WebXDC hardening

**5A. Lock WebView settings explicitly**
* In `XdcPlayerScreen` WebView init: `settings.allowFileAccess=false`, `allowContentAccess=false`, `allowFileAccessFromFileURLs=false`, `allowUniversalAccessFromFileURLs=false`, `mixedContentMode=MIXED_CONTENT_NEVER_ALLOW`, `safeBrowsingEnabled=true`, `geolocationEnabled=false`, `textZoom=100`.

**5B. Cap webxdc journal**
* `XdcUpdateStore.append` → if `log.length > 5 MB` or `load().size > 10k`, reject `sendUpdate` with `QUOTA_EXCEEDED` and show snackbar. Prevents DoS.

**5C. Permission gate for `importFiles`/`sendToChat`**
* First time a mini-app calls `importFiles`, show `AlertDialog` ““Chess” wants to pick files — Allow / Deny — Remember”.

### Priority 6 — updates & supply-chain

**6A. Sigstore provenance**
* Publish `SHA256SUMS.txt.sigstore.json` alongside release, verify in `UpdateManager` via `sigstore-java` or at least check `release.author.login == toddsmith456` + `asset.createdAt` freshness.

**6B. Update channel toggle**
* `Settings → Updates → Check via Tor only` (already falls back to direct; add opt to *not* fall back).

### Priority 7 — notifications privacy

**7A. Lock-screen visibility**
* `NotificationChannel` + `NotificationCompat.Builder.setVisibility(VISIBILITY_SECRET)` for new followers / DMs — prevents “Follows you: npub…” on lock screen.

---

## 6. Quick matrix — effort vs privacy gain

| # | Change | Lines | Data loss | User opt-in | Gain |
|---|---|---|---|---|---|
| 1B | wipe `cacheDir` on duress | 3 | none | auto | ★★★ |
| 1A | salted PIN KDF + rate limit | 40 | none | auto | ★★★ |
| 2A | prune or SQLCipher | 30–200 | optional | toggle | ★★★ |
| 5A | WebView locks | 6 | none | auto | ★★ |
| 3A | Tor kill-switch | 15 | none | toggle | ★★ |
| 4A | cacheKey strip query | 2 | none | auto | ★ |
| 7A | notif visibility | 2 | none | auto | ★ |

---

## 7. Recommendation if you say “do it”

**Do 1B + 1C + 5A + 3A (toggle default off) + 2A-prune as opt-in button —** total <80 lines, no DB migration beyond a daily `WorkManager` prune. I’ll keep the lightweight cache toggle you just added, and `allowBackup=false` / `FLAG_SECURE` / Keystore crypto untouched.

Reply with e.g. `do 1B,5A` and I’ll implement, test offline, and push via `GIT_SSH_COMMAND="ssh -i /home/user/.ssh/tbone_ed25519 -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new" git push origin main` — no release.

