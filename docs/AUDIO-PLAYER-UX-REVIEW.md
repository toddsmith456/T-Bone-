# Audio player usability and polish investigation

## Boundary

**Recommendations only. No changes in this build implement this report's polish proposals.** The separately authorized cache work and podcast/category selectors are functional changes, not a broad visual redesign. Findings come from reading `AudioPlayerScreen`, `AudioPlayerViewModel`, `InlineAudioPlayer`, `PlaybackController`, `PlaybackService`, `ComposeViewModel`, note actions and navigation. No claim is made that a device session, TalkBack audit, frame trace or user study was performed.

## Executive diagnosis

The player has recognizable controls, but state ownership and handoff are more important than extra decoration. The biggest sources of a sloppy feeling are inconsistent playback entry points, limited queue context, metadata arriving after playback starts, ambiguous actions/credits, and operations without a stable progress/retry story. Podcasts make these gaps more visible because people expect position resume, long-duration seeking and episode identity.

## Findings and proposals

### P0 — One playback state across every entry point

**Evidence:** `InlineAudioPlayer` can create its own player, while full Audio connects to the `PlaybackService` MediaController. Opening Audio from inline passes a URL and poster, not a complete queue/position/source-note context.

**Why it feels wrong:** moving from a feed card to the full player can behave like starting a second session rather than expanding the same one. Notification, full-screen, and inline progress can have different owners.

**Proposed:** route inline audio through the same session; make “expand player” a navigation action, not a restart. Carry media ID, note ID, poster, selected URL and queue origin in one typed model. Preserve position and paused/playing state through navigation and controller reconnect.

**Acceptance:** start inline, seek, expand, rotate, background, return. Exactly one session plays; position remains continuous; pause works consistently from every surface. Test background playback both enabled and disabled.

### P0 — Explain loading, failure and retry without losing context

**Evidence:** playback UI derives mainly from title/isPlaying/buffering; the prior source has limited player-error presentation. Metadata and upload failures use different pathways (status strip, toast, composer error). Initial open can wait for metadata before preparing playback.

**Proposed:** model idle/connecting/resolving/preparing/playing/paused/ended/error explicitly. Start playback preparation independently of optional metadata enrichment. Show a compact persistent error with Retry and, where appropriate, Open original post; keep the queue intact. Separate “metadata unavailable” from “audio unavailable.”

**Acceptance:** denied media permissions, no relays, no network, expired URL, unsupported codec, metadata timeout and killed service each produce a truthful, recoverable state. No blank title or indefinite spinner should masquerade as loading forever.

### P1 — Stable now-playing identity and smoother metadata arrival

**Evidence:** track identity is assembled in UI helpers; metadata-map updates can replace the current MediaItem. Filename/imeta, embedded tags, remote art and poster credit arrive on different timelines.

**Proposed:** create a repository-level immutable Track/PlaybackUiState contract with a documented metadata precedence, stable media IDs, source-note link and separate artist/publisher/poster fields. Patch identity only when values actually change; avoid replacing the current item for an unrelated row update. Use a fixed artwork frame and graceful fallback/transition rather than layout reflow.

**Acceptance:** scroll rows while playing; unrelated metadata completion must not change playback position, buffering, title or art. A late cover must not shift controls. Missing artist is “Unknown artist,” never silently the note poster.

### P1 — A real queue and predictable next/previous semantics

**Evidence:** playlist selection enqueues playlist tracks, but Discover play builds a one-item queue. The mini player always presents transport buttons; no queue origin/reorder sheet is exposed. Shuffle/repeat controls and state are not part of the present full-player UI.

**Proposed:** decide explicitly whether Discover “play” means one item or the visible list. Display queue origin (“From playlist …” / “Music · Follows”). Add an accessible queue sheet, remove/reorder actions, and consistent disabled transport states. Consider shuffle/repeat for music after queue semantics are settled; do not bury them in an oversized full-player layout.

**Acceptance:** next/previous behavior matches the visible queue, including its ends, removed items and list refresh. Refreshing Discover must not silently rebuild a playing queue.

### P1 — Podcast expectations need their own small control set

**Evidence:** current tagged-podcast support shares the music transport; there is no podcast-specific position store, speed selector, played state, episode queue or jump interval.

**Proposed:** per-episode resume keyed by stable media identity, 15/30-second jumps, speed selection with explicit feedback, elapsed/remaining toggle and mark played/unplayed. Keep these secondary controls out of the mini player. Use episode/show metadata when present; do not invent a show from the sharing poster. Offer RSS support only as a separate scoped feature, with its own privacy and subscription design.

**Acceptance:** reopen a half-finished episode after process death and resume correctly; finishing an episode resets/marks position according to a documented policy. Music does not inherit an unexpected podcast speed.

### P1 — Make upload a deliberate, recoverable operation

**Evidence:** upload is an original-file queue followed by publish; the upload choice now captures Music/Podcasts. Failures can arise in file access, preparation, server upload, signer approval or relay publication.

**Proposed:** one visible operation state with category, filename, original-file status, progress if available, cancellation boundaries, publish/signing status and retry. State clearly whether a retry reuses an already uploaded URL to avoid duplicate uploads/posts. Consider a confirmation/metadata preview only if users want one; do not silently change the requested direct-upload behavior.

**Acceptance:** category changes while uploading do not reclassify the queued file. Canceling the picker does nothing. Signer rejection preserves enough state to retry publishing without re-uploading. File bytes match the original.

### P1 — Separate song identity from actions and poster credit

**Evidence:** discovery rows combine song details, poster text, playlist action and “open post.” Library artist text currently has a profile-click callback associated with the source-note author.

**Proposed:** artist/song remain the primary hierarchy; “Shared by …” explicitly owns the poster-profile action. Do not make an artist label open the unrelated poster profile. Move secondary actions into a consistent overflow sheet (About, source note, add to playlist, share). Keep play/pause a clearly labeled primary target.

**Acceptance:** every tap target's label matches its destination. About identifies artist, album/show, poster and source post separately. Anonymous/missing profiles still render legibly.

### P2 — Keep navigation and list position predictable

**Evidence:** the audio tool has its own bottom bar, selected-playlist state and two levels of discovery selectors. Category/audience changes reload the selected list. Full-player artwork lives in a scrolling column on short screens.

**Proposed:** save scroll position separately for all four discovery combinations and the selected library list. Define back behavior: playlist detail → library; source note → prior audio selection; Audio → toolbox without destroying a permitted playing session. Consider a compact header on short screens rather than letting primary transport disappear far below the fold. Keep the existing Player/Library/Discover structure.

**Acceptance:** switch tabs, open source note, return, rotate and use system back on a compact phone and landscape. The previous selection and useful viewport survive.

### P2 — Accessibility, typography and motion

**Evidence:** controls mix material icons, text arrows, clickable text and differently sized targets. Art and text can contain very long titles or absent metadata. The current implementation has semantic labels on several controls, but no device-level accessibility verification.

**Proposed:** normalize 48dp minimum action targets, focus order and labels; announce play/pause and errors without announcing every progress tick. Expose slider position/duration accessibly. Test 200% font scale, long multilingual metadata, RTL, contrast across T-Bone themes and reduced motion. Keep cover-art animation subtle and optional.

**Acceptance:** complete playback/playlist/upload flows with TalkBack and keyboard/switch navigation; distinguish selected category/audience without relying only on color. No control overlap or clipped action text at large fonts.

## Recommended order

1. Shared session handoff and explicit failure/retry state (P0).
2. Stable track identity and queue semantics (P1).
3. Podcast resume/jumps/speed, plus upload recovery (P1).
4. Action/credit hierarchy, scroll restoration, accessibility (P1/P2).
5. Only then tune spacing, transitions and visual micro-interactions with actual device feedback.

## Measurement plan before claiming “smoother”

Baseline cold/warm time-to-play, p50/p95 row-ready latency, cache hit rate, bytes fetched, frame jank, image decode memory and service handoff interruptions. Compare on a low-RAM phone and a modern phone, direct and Tor networks, with media auto-load off/on, large libraries, missing art and slow/non-range servers. Use deterministic audio fixtures with known tags and original hashes. Keep profiling/debug logs free of signed URLs and private playlist contents.

The right success criterion is continuity and predictability, not simply adding more controls. These proposals require separate implementation approval.
