# Audio cache investigation and implementation

## Scope and evidence

Source-level investigation of the audio player, shared cache layers, event/profile repositories, playlist vault, storage controls, and duress wipe. This is not a device benchmark or an offline-playback certification. The change implements cache improvements and podcast discovery, not the separate UX recommendations in AUDIO-PLAYER-UX-REVIEW.md.

## Findings before this change

| Layer | Existing behavior | Audio consequence |
|---|---|---|
| `EventRepository` + `EventDao` | Parsed-event RAM LRU, batched Room writes, preset-driven account/shared retention | Discover saved notes but never hydrated from Room. Reopening could look empty until relays answered. Busy quote/search traffic could evict shared audio notes. |
| `ProfileRepository` | Room/RAM hydration, stale-while-revalidate, followed/own profile protection | Reuse this for poster credit; do not create a competing profile database. |
| `BookmarksRepository` / `BookmarkSyncRepository` | Encrypted local event snapshots; online NIP-51 sync | This is durable playlist ownership. A cache must not become the authoritative playlist store or expose encrypted playlist membership. |
| `AudioMetadataReader` | Bounded HTTP range extraction; two concurrent readers; Tor-aware client; no persistent result store | Visiting rows repeatedly could repeat ID3/container reads. ViewModel dedup remembered failures indefinitely until recreation. |
| `ImageLoaderRegistry` | Separate avatar/media Coil caches and preset budgets; protected avatar thumbnails | Remote artwork already has a cache. Embedded artwork is not a remote image URL, so it needed a small persistent home alongside extracted metadata. |
| `MediaCacheManager` | Shared Media3 SimpleCache for audio/video; preset cap; Ultra-Light bypass | Reuse for user-initiated playback bytes. Do not pre-download tracks merely because metadata is being cached. |
| `MediaPrefetcher` | Tier-aware lookahead; speculative work paused on Tor and media-off settings | No new whole-library speculative media downloader should be introduced. |
| `CacheDirectories`, `CacheStorageManager`, `CacheCleaner`, `DataWiper` | Central cache accounting, clear actions, managed-directory exclusions, recursive duress wipe | An unregistered audio cache would be misleading in storage accounting and risk inconsistent clearing. |

## Priority model

1. **Durable user-owned state:** playlist names, membership and saved note snapshots remain in the existing encrypted bookmark vault. Cache eviction never deletes a playlist.
2. **Already-local UI data:** restore public source notes from Room and cached music identity/artwork from disk before waiting on network. Reuse the existing poster profile repository.
3. **Current/visible content:** metadata extraction occurs for an explicit open-in-player request or composed library/discovery rows. Warm cached data is published before revalidation. No whole-library network scan is introduced.
4. **Recent discovery:** retain a bounded reserve of recent shared audio candidate notes, independent of generic newest shared notes.
5. **Audio bytes:** only normal playback participates in Media3 caching, under its existing budget. “Playlist available offline” is not a promise that its audio was downloaded.

This is bounded prioritization, not indefinite pinning of every song ever seen. Current-track work shares the reader's existing two extraction slots; a fully preemptive priority scheduler is deferred rather than claimed as implemented.

## Implemented design

### Extracted metadata and embedded cover art

`AudioMetadataCache` is an injectable singleton with a versioned, rebuildable disk store in `cacheDir/audio_details_cache`. It stores title, artist, album and small embedded artwork, not audio bodies or playlist membership. Files are keyed by SHA-256 of the **full URL**. Meaningful/signed query parameters are deliberately preserved; image tracking-parameter normalization is not automatically appropriate for authenticated audio URLs.

- Successful metadata freshness: 30 days.
- Failed extraction retry backoff: 5 minutes, rather than permanent session suppression.
- Stale successful details are usable offline and survive a failed refresh. A failed refresh retains the old value with the short backoff.
- Cache-only lookup is available even when automatic media loading is off. Rows do not initiate extraction in that mode.
- Read-through work is deduplicated through fixed-size striped coroutine locks; the range reader retains its two concurrent extraction slots.
- Writes use same-directory temporary files and rename, with a 384 KiB record ceiling. Corrupt/version-incompatible records are ignored and removed.
- Embedded covers retain the reader's 256 KiB compressed-image ceiling; oversized covers fall back to the existing artwork/placeholder behavior rather than growing the cache without bound.
- LRU-style eviction uses successful disk access times. The disk cap is `mediaDiskBytes / 32`, bounded to 1–16 MiB. This is a **separate small allowance**, not secretly charged against the Media3 audio/video budget.
- The audio ViewModel holds at most 24 resolved metadata records. Requests can hydrate again after eviction instead of being permanently blocked by a session dedup set.
- Metadata remains best effort: unsupported containers, absent tags, non-range servers, expired URLs and extraction limits still fall back to imeta/filename/player metadata. No title is inferred from the sharing note's prose.

### Source notes and discovery

`EventDao.recentAudioCandidates` reads a bounded recent set from the existing Room events table. SQL performs a coarse tag-string candidate match; the application then verifies the exact hashtag, text-note kind, audio attachment, audience and mute exclusion. The SQL LIKE expression is **not** treated as authoritative hashtag matching.

Each selected category/audience restores matching cached notes before opening a relay subscription. Cached rows remain visible during network revalidation. Generic shared-event pruning protects up to 100 recent shared music/podcast candidates in addition to its normal newest-shared budget. No duplicate note database or unbounded audio pin table is created.

Limits: candidate hydration examines the newest 400 candidate records and displays at most 100. The 100-note retention reserve is shared by music and podcasts, not 100 per feed. Account-associated events retain the existing account-feed pruning behavior; encrypted playlist snapshots remain the reliable saved-copy path. Cache deletion, Android storage pressure or eviction can remove unsaved discovery history. Queries use the current schema and do not require a Room migration; a dedicated indexed audio classification table may be warranted after profiling very large databases.

### Privacy and clearing

Metadata HTTP reads still use `ImageClientProvider`/Tor guard and the existing bounded range reader. No new network stack, analytics, RSS fetcher or whole-audio prefetcher was added. Cache-only hydration itself performs no audio HTTP requests. Normal relay refresh and existing profile refresh are separate network operations.

The new directory is registered as managed, displayed separately in Settings storage usage, and included in total cache bytes. **Clear photos** clears audio details too (the storage label states this); **Clear all caches** clears them as well. Audio/video-byte clearing remains scoped to Media3 playback bytes. Cache generation changes invalidate ViewModel metadata, and an in-flight old-generation extraction cannot write its result back after clear. Duress wipe disables future writes on the singleton before recursive cache deletion and restart.

Public track metadata is rebuildable cache data, like cached public events, not encrypted vault data. The cache deliberately excludes playlist names/membership and private signing material. A hashed filename is not encryption: cached song titles and covers remain readable from the app's private filesystem. Android may reclaim cacheDir independently.

## Podcasts and four discovery combinations

- Categories: Music (`#TboneMusic`) and Podcasts (`#TbonePodcast`).
- Each category has Follows and Global selectors.
- Follows queries send author-filtered relay requests, chunked into groups of 200, and also enforce follow membership locally. An empty follows set produces an empty follows view without issuing a match-all query.
- The effective follow list respects the existing per-account offline-follow switch; when enabled, that local list is authoritative and is not silently replaced with relay follows. Active-account/follow-list changes rebuild the subscription. Generation checks keep old in-flight results from crossing into the new selection.
- Upload asks Music or Podcasts before opening the picker. The choice is captured on the queued upload and associated with the resulting URL, not read from whichever Discover tab happens to be selected later.
- Original audio remains byte-for-byte, without compression. The selected category is added to both visible note text and hashtag tags. A podcast upload is not silently cross-tagged as music.
- These are tagged Nostr audio posts, not an RSS subscription/import feature. Both categories use the existing player and playlist system.

## Validation and remaining device checks

Added unit tests for cache budget bounds, URL-key isolation, freshness/backoff, exact category/audience behavior, empty-follow exclusion, attachment requirements, podcast tagging and hashtag deduplication. Existing CI runs the beta and production builds/tests, privacy gate, and APK signature/checksum verification; the final delivery message records the actual outcome.

Device acceptance checks still needed (not claimed as executed here):
1. Open a track, force-stop, enable airplane mode, reopen and verify cached details/source note without network.
2. Visit Music/Podcasts × Follows/Global; switch accounts/follows while refresh is in flight; verify no stale rows cross views.
3. Upload each category and inspect the signed note and downloaded original file hash.
4. Test a failed/non-range metadata server twice within five minutes and after backoff; test stale details with no connectivity.
5. Clear photos/all caches during extraction, then restart; verify old metadata is not recreated by the interrupted request.
6. Exercise preset downgrade, Android low-storage eviction, corrupt record recovery and duress wipe on a test installation.
7. Measure cold/warm metadata requests, p95 row-ready latency, disk cap, heap and Room query time on low-memory hardware. Do not infer performance numbers from this source review.
