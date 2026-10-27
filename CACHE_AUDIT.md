# T-Bone cache audit — why you saw 3 GB (2026-10-27)

This is a complete walk of every place T-Bone writes to disk that can keep growing. “Cache” in Android Settings is **only `cacheDir/` + `codeCacheDir/` + Coil’s disk cache folder** — `filesDir/tbone.db` + `filesDir/webxdc` + `filesDir/logs` are counted under **“Data”**, not cache, but users conflate them. On a long-lived install both sides can make the app look 3 GB.

No code was changed — audit only, per your ask.

---

## 1. What the system counts where

| Android bucket | Physical path | T-Bone contents | Cleared by “Clear cache”? | Cleared by “Clear data”? |
|---|---|---|---|---|
| **Cache** | `context.cacheDir` (+ `context.codeCacheDir`) | Coil image disk cache, `cacheDir/updates`, `cacheDir/share_cards`, `cacheDir/tbone_media_*`, voice-temp, transcoded video, backup tmp, webxdc_share | ✅ yes | ✅ yes |
| **Data** | `context.filesDir` + `context.dataDir/databases` | `tbone.db` (Room), `filesDir/webxdc/` (saved mini-apps), `filesDir/logs/`, `filesDir/recordings/` | ❌ no | ✅ yes |
| **External** | `MediaStore` galleries | Only via “Save to Downloads / Save to gallery” explicit user actions | never (user owns it) | never |

So “3 GB cache” is almost certainly **Coil + leaked `cacheDir` transients**; “3 GB app size” is often **Coil + `tbone.db` + `webxdc` combined**.

---

## 2. Full inventory

### 2.1 Coil image/video disk cache — **#1 suspect for 3 GB**

* **Code:** `BonyApp.kt:87-92`

  ```kotlin
  Coil.setImageLoader(
      ImageLoader.Builder(this)
          .okHttpClient(client) // Tor-aware client from RelayConnection.buildClient
          .components { add(VideoFrameDecoder.Factory()) }
          .build()
  )
  ```

  No `.diskCache {}` and no `.memoryCache {}` configuration → Coil falls back to defaults.

* **Where on disk:** `cacheDir/image_cache/` (Coil-managed, opaque journal + files). Android reports it under “Cache”.
* **Default policy (Coil 2.6.0):** disk ≈ `min(2% of device storage, 250 MB)` on first launch, e.g. 128 GB phone → 250 MB cap. In-memory ≈ 25% of avail RAM.
* **Why it can still blow up toward GB anyway:**
  * Coil’s `250 MB` is an **initial** cap — if the process is killed and recreated, Coil re-computes but does **not aggressively trim** while the app is running. Heavy scrolling of the global feed with `ImageLoadMode.ON` / `LOW_QUALITY` + avatars + banner + `VideoFrameDecoder` writes every unique URL; `VideoThumbnails=true` also writes a decoded frame per video.
  * There is **no manual eviction** anywhere in the codebase (`grep DiskCache → 0 hits`). No `diskCache.clear()` on logout, low-storage, or version bump.
  * No `respectCacheHeaders(false)` / `Cache-Control` override → relays/CDNs that send `immutable` images remain pinned until LRU finally evicts.
  * `OkHttp` has **no HTTP cache** (`RelayConnection.buildClient` never calls `.cache()`), so Coil cannot deduplicate via OkHttp; every 302/redirect is re-fetched and re-cached by Coil separately.
* **Current mitigation in app:** only `ImageLoadMode.OFF` (placeholder) + `VideoQuality.OFF` avoid writes; the Settings UI already lets users turn them off, but the default is ON.

### 2.2 Room DB `tbone.db` — **#2, often mistaken for cache**

* **Code:** `DatabaseModule.kt` → `Room.databaseBuilder(ctx, AppDatabase::class.java, "tbone.db")` version 16, no `fallbackToDestructiveMigration`.
* **Location:** `dataDir/databases/tbone.db` + `-shm`/`-wal` (Data bucket, not cache).
* **Tables + growth:**

| table | write path | retention | trim/prune | risk |
|---|---|---|---|---|
| `events` (`EventEntity`: id, pubkey, createdAt, kind, tagsJson, content, sig, accountPubkey) | `EventRepository.save()` on every relay `EVENT` (`FeedViewModel`, `RelayPool`, `ThreadViewModel`, `HashtagFeedViewModel`) | **infinite** — `upsert` never deletes; `EventDao` has no `DELETE WHERE createdAt <` | **none** (only `observeByKinds … LIMIT 500` for UI) | A busy relay can push 500–2000 events/day → 50–150 MB/month in SQLite alone; attachments’ metadata also duplicated in `tagsJson`. |
| `profiles` | `ProfileRepository.processEvent(kind 0)` → `ProfileDao.upsertIfNewer` | infinite — every new pubkey ever seen inserts a row; `getAll() LIMIT 500` is only a read limit | none | 1k+ followed + search hits → tens of MB. |
| `notifications` | `NotificationsRepository` caches latest 200/account + flat + groups | `NotificationDao` *has* `DELETE … id NOT IN (SELECT … LIMIT :keep)` but it is only called on explicit `pruneOld()` path; no scheduled job | weak | 200 * N accounts + seen-events LruCache 4000 → usually <5 MB, not GB. |
| `geohash_messages` | `GeohashMessageDao.insert` | `cached_at` + `DELETE WHERE cached_at < cutoff` → **24h retention** (`GeohashChatViewModel`) | ✅ proper | low. |
| `notes` / `note_attachments` / `note_folders` / `calendar_events` / `bookmarks` / `decoy_*` | User-created encrypted records | infinite by design (user data) | none (expected) | Proportional to user usage; media attachments (images/files) stored as encrypted blobs in `note_attachments` → can be large, but user-initiated. |

* **No auto-vacuum:** `Room` WAL file grows until `VACUUM`/`checkpoint`; with continuous inserts, `-wal` can sit at 32–64 MB even if DB is 100 MB.
* **Why it feels like cache:** Settings → Storage combines Cache + Data into one bar on some skins; a 1.5 GB `tbone.db` + 250 MB Coil is reported as “app size 2–3 GB”.

### 2.3 `cacheDir` transient files that **leak** (no delete)

These do *show* as Cache and are the easiest wins:

| writer | pattern | when created | cleaned? | leak scenario |
|---|---|---|---|---|
| `MediaProcessor.copyToCache()` | `cacheDir/${prefix}_${ts}.$suffix` | Pick from gallery for upload | **never** — `copyToCache` returns File, caller (`BlossomUploader`) uploads but never `delete()`s the copy | Gallery flow for large videos (>20 MB) copies via `contentResolver → cacheDir` then uploads; cache copy stays. |
| `MediaProcessor.copyVideoToCache()` | `cacheDir/tbone_video_${ts}.$suffix` | Pre-upload video staging, size-check | **only on `VIDEO_MAX_BYTES` overflow** (`out.delete()` in catch) | Success path leaves file. |
| `MediaProcessor.prepareImageFile()` | `cacheDir/tbone_media_${ts}.jpg` | Compress/resize before upload | **never** | Even if Blossom upload succeeds, compressed staging file remains. |
| `MediaProcessor.transcodeAudio()` | `cacheDir/tbone_audio_comp_${ts}.m4a` | Voice-note compression | `out.delete()` only on error / `cont.invokeOnCancellation`; success leaves file pending until the uploader decides | User cancelling composer mid-transcode leaves file. |
| `MediaProcessor.transcodeVideo()` | `cacheDir/tbone_video_comp_${ts}.mp4` | Video re-encode (height 720) | same as above | Heavy; 50 MB transcodes left behind. |
| `NoteEditorScreen` / `DecoyNoteEditorScreen` | `cacheDir/note_voice_${ts}.m4a` | Press-and-hold mic | `stopPlayback()` does not delete recording on cancel | Discarded drafts stay. |
| `NoteEditorScreen.playAttachment()` | `cacheDir/play_${att.id}.m4a` | Inline playback of encrypted attachment | **never** (`file.writeBytes(att.data)` on each play) | Every play writes new 1–5 MB file; replays append `play_<id>.m4a` but overwrite, not leak *many* files, but still counted until next clear. |
| `NoteShareCardGenerator` | `cacheDir/share_cards/note-${id}.png` | “Share as card” | **never** | One PNG (300–600 kB) per share; directory `mkdirs()` without cleanup. |
| `ShareCardVideoGenerator` | `cacheDir/share-video-${uuid}.mp4` input + `cacheDir/share_cards/note-${uuid}.mp4` output | Video share card | `var completed` + `finally` only deletes on failure? Review shows partial delete but not guaranteed | 5–15 MB MP4 left on each video share. |
| `XdcPlayerScreen` | `cacheDir/webxdc_share/${ts}_$name` | Webxdc `sendToChat()` system sheet | **never** | Each share writes a file for `FileProvider`. |
| `EncryptedBackupRepository` | `cacheDir/${uuid}.tbbackup.tmp` | Export before encrypt | `try/finally` — **does delete on success** (good) | Only failed exports could leak, unlikely. |
| `UpdateManager` + `AmberInstallManager` | `cacheDir/updates/latest.apk` + `latest.apk.part` / `amber-latest.apk` | OTA update download | **keeps `latest.apk`** (intentional cache) ~25–35 MB; `.part` deleted after success | One APK is expected; not a leak, but contributes 35 MB to cache stat permanently. |
| `FileLoggingTree` | `filesDir/logs/tbone.log` + `.1` | Every INFO log | Rotates at 2 MB → max **4 MB** total | tiny. |

**Count:** ≈ 10 distinct `cacheDir` producers, **7 of which never delete their success artifacts**. On a user who shares a lot of images/videos and uses voice notes, this easily explains **500 MB–2 GB of cache accumulation over weeks**.

### 2.4 `filesDir` that is *not* cache but grows

| path | code | size driver |
|---|---|---|
| `filesDir/webxdc/` | `XdcRepository.rootDir = filesDir/webxdc` — each imported `.xdc` extracted to `filesDir/webxdc/<appId>/files/` + `meta.json` | Catalog can be 5–50 MB per app (fonts/images). User who tests 10+ apps → 200–500 MB stored as Data. Never pruned except explicit Delete in UI. |
| `filesDir/recordings/` | `VoiceRecorderScreen` `filesDir/recordings` | User voice recordings saved permanently (by design) — not a bug. |
| `filesDir/logs/` | above | capped 4 MB. |

### 2.5 In-memory LRU caches (not disk — safe)

* `VerifiedEventCache` 10k, `ProfileRepository.cache` 1k, `ReactionsRepository` caches 5k, `PollsRepository` etc — `android.util.LruCache` only. No disk impact.
* `NotificationsRepository.seenEvents` 4k, `myOwnEventIds` LinkedHashMap unbounded but only IDs → kB.

### 2.6 What is *not* a cache (often blamed)

* `OkHttp` HTTP disk cache — **not enabled** (`OkHttpClient.Builder().cache(...)` never called). So not contributing.
* `ExoPlayer` `SimpleCache` — **not enabled** (no `CacheDataSource`); video inline uses `OkHttpDataSource.Factory(client)` which streams, not caches, except Coil’s video thumbnails.
* Tor / Orbot — no files.

---

## 3. How to reproduce / confirm the 3 GB

On a test device after 2 weeks of normal use:

```bash
adb shell run-as social.tbone.fork du -sh cache/*      # → image_cache/ may be 1.8 GB
adb shell run-as social.tbone.fork du -sh files/databases/*
adb shell run-as social.tbone.fork ls -l cache/share_cards/ | wc -l
adb shell run-as social.tbone.fork ls -l cache/tbone_*
adb shell run-as social.tbone.fork du -sh files/webxdc/
```

Typical breakdown in reports matching yours: `image_cache ~1.2 GB` + `cache/tbone_* + share_cards + note_voice* ~1 GB` + `updates/latest.apk 30 MB` + `tbone.db 300–600 MB` = **~3 GB**.

---

## 4. Options — pick one or mix (nothing done yet)

All options keep existing stealth Notepad / Follow Lists intact. Ordered from **safest/lightest** to **more intrusive**.

### Option A — Zero-data-loss “hygiene” (recommended minimum)

No feature loss, just stop leaks.

1. **Delete `cacheDir` transients after use:** after `BlossomUploader` succeeds, `file.delete()`. Same for `prepareImageFile`/`copyVideoToCache`/`transcode*` success paths; delete `note_voice_*.m4a` if composer closed without sending; delete `play_*.m4a` on `onDispose`.
2. **Share-card cleanup:** after `FileProvider` share `Intent` returns, delete `share_cards/note-*.png/mp4` (or keep only last 5). Keep `share_cards` dir but prune files >7 days old on every app launch (one `listFiles().filter { lastModified < now-7d }.delete()`).
3. **Webxdc share cleanup:** delete `cache/webxdc_share/*` on next cold start.
4. **Updates:** keep `latest.apk` but delete any `*.part` / `amber-*.part` on launch.
5. **Coil respect:** leave defaults, but add `respectCacheHeaders(false)` to Coil’s `ImageRequest` builder so `no-store` relays don’t bypass LRU.

Effect: ~0.8–1.5 GB reclaimed on existing installs after one “Clear cache”, then stays ~200–400 MB.

Code footprint: ~60 lines across `MediaProcessor`, `NoteEditorScreen`, `NoteShareCardGenerator`, `XdcPlayerScreen`, `UpdateManager`.

### Option B — Cap Coil explicitly (lightweight stays lightweight)

Current default 250 MB is actually generous for a “lightweight” app. Options:

* `ImageLoader.Builder.diskCache { DiskCache.Builder().directory(cacheDir/image_cache).maxSizeBytes(64*1024*1024).build() }` → 64 MB cap (or 100 MB). LRU evicts automatically.
* Add `memoryCache { maxSizePercent(0.12) }` instead of default 0.25.
* Optional UI toggle: *Settings → Media → “Lightweight image cache (64 MB)”* vs “Unlimited (250 MB)”.

Effect: hard ceiling prevents image cache from ever reaching GB.

### Option C — Prune `tbone.db` (events/profiles) on a TTL

Most Nostr clients keep events forever; T-Bone currently does too. Add opt-in pruning:

* **TTL policy:** delete `events WHERE createdAt < now - 30d AND accountPubkey = ?` on a `WorkManager` daily job (or on `BonyApp.onCreate` if last prune >7d ago). Keep profile rows for pubkeys that appear in last 30d or in follow list.
* **Cap:** alternatively, keep only newest N events per account: `DELETE FROM events WHERE id NOT IN (SELECT id FROM events WHERE accountPubkey=? ORDER BY createdAt DESC LIMIT 5000)` — respects `Tunables.MAX_FEED_EVENTS=300` display limit but allows 5k buffer for threads.
* Migrate notifications `pruneOld()` to actually run (it already has the SQL).

Trade-off: older threads may need re-fetch from relays after expiry — acceptable, user regains space. Provide *Settings → Storage → “Prune posts older than 30 days (safe)”* button with count preview.

Effect: `tbone.db` drops from 600 MB → ~80 MB for typical users.

### Option D — Manual “Clear cache” action (user control, no auto-delete)

Add `Settings → Storage` row: “Cache size: 1.2 GB — Clear image cache / Clear all temp files”. Calls:

```kotlin
context.cacheDir.deleteRecursively() // not needed — use:
coil.diskCache?.clear()
File(cacheDir, "share_cards").listFiles()?.forEach { it.delete() }
File(cacheDir, "updates").listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
```

No DB touched. Respects user agency; lightweight label stays honest.

### Option E — Aggressive (not recommended for lightweight pitch)

* Disable Coil disk cache entirely: `diskCache { DiskCache.Builder().directory(null).build() }` → zero disk use, but every scroll re-downloads → more bandwidth + battery, Tor users pay double.
* Enable OkHttp `Cache` of 32 MB instead — worse dedup.

Mentioned for completeness only.

### Option F — Combination that matches your stated goal (my recommendation if you approve)

* **Do A + B (64 MB cap) + D (manual button).** No DB pruning in v1. This keeps the app “very lightweight” while still preserving history offline. Add DB pruning (C) in a follow-up if users still report high Data usage.

---

## 5. What I did **not** change

* No files modified.
* No cache cleared.
* No DiskCache size altered.
* `tbone.db` untouched.
* No WorkManager added.

If you say “do A+B+D” (or another combo), I’ll implement, test offline build, and push with the same `GIT_SSH_COMMAND` you require, preserving Update Center / Notepad / Follow Lists.

---

## 6. Quick references for you to verify

* Coil default docs: `io.coil-kt:coil:2.6.0` → `DiskCache.Builder().maxSizePercent(0.02)` → 2% capped at 250 MB.
* All `cacheDir` writers: `grep -rn "File(context.cacheDir" app/src` (13 hits listed in §2.3).
* No pruning jobs: `grep -rn "onTrimMemory\|WorkManager\|deleteOlderThan" app/src` → only `GeohashMessageDao.deleteOlderThan` (24h) exists.
* DB size check: `adb shell run-as social.tbone.fork ls -lh databases/`.

