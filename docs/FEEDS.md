# The four feeds

The home screen has one segmented strip with four feeds. They share a single
timeline renderer, so a note looks the same whichever way you arrived at it; only
the author set and the time window differ.

| # | Tab | Icon | Author set | Time |
|---|-----|------|-----------|------|
| 1 | `FOLLOWS` | person + plus | your follow list (offline list when switched on) | live |
| 2 | `CLOCK` | clock | the same follow list | you pick 1–24 h ago |
| 3 | `SUGGESTED` | T-Bone tooth | the curated roster in `assets/tbone-suggested-follows.txt` | recent |
| 4 | `GLOBAL` | globe | every author the relays carry | live |

## Follows

Unchanged. Kind 1/6/9, newest first, paged as you scroll.

## Clock

`CLOCK` freezes the *following* timeline at a point you choose: the anchor is
`now − N hours`, and the feed shows notes at or before that instant.

- The picker is **collapsed by default**: one line under the tabs showing the
  hour you chose (`3h ago · 06:05 ▾`). Tapping it expands the horizontally
  scrollable `1h … 24h` strip; picking an hour collapses it again. A permanently
  parked 24-chip row costs a whole note's worth of vertical space for a value
  you set once.
- The chosen value is persisted (`AppSettings.CLOCK_FEED_HOURS`), so reopening
  the app lands on the same window and the same collapsed line.
- Moving the window re-anchors in place. Rewinding (6 h → 24 h) keeps every note
  already on screen and quietly tops up the earlier stretch; fast-forwarding
  (24 h → 6 h) drops the notes that are now "in the future" of the new anchor and
  streams the newer ones in. The list never flashes empty.
- Notes published after the anchor are rejected in `addToFeed`, so the window
  cannot creep forward while you read.
- There is **no "load 100 older"**: the clock feed loads one page and stops,
  exactly like the following feed. Pull to refresh for more.

Scope note: this is *your* timeline, not the global firehose — it is a time
machine through the people you follow, not a public archive.

## Suggested

A fixed roster of 1,346 accounts, shipped with the app, queried in slices of 150
authors (a single REQ with all of them is ~90 kB and gets rejected or truncated).
Every slice is merged into one timeline and revealed in a single pass once the
last slice reports EOSE, so the feed does not grow chunk by chunk in front of
you. New follow lists can be dropped into the asset without touching code.

## Global

Unchanged: buffered live events, a manual/intervalled "load new" control and
100-at-a-time paging into the past.

## Icons

`ui/feed/FeedTabIcons.kt` draws all four as `ImageVector`s on a 24-unit grid.
Material's outlined icons are pre-baked fills and go muddy at 17 dp next to the
app's hairline rules; these stay crisp and take `Icon(tint = …)`, which is what
lets every theme colour them (accent when active, `TextMute` otherwise).

Three are single strokes at 1.7 units. The T-Bone mark is not: it is a **tooth
with a T cut into it**, traced from `docs/feed-shots/tbone-mark.png` (the
artwork the icon is drawn from). Its body is one closed stroke whose top notch
descends into the bar to form the stem of the T, plus the bar and a root canal.
The reference is a ~0.97-unit hairline; at 17 dp that disappears, so it is drawn
at 1.5 units with the interior reduced to the T alone. Rendered back out of the
compiled path data it overlaps the source artwork at IoU 0.49 with every stroke
within a pixel and a half — the widths match, so the only real difference is the
interior simplification described above.

## Screenshots

`docs/feed-shots/` renders the new UI from the app's own constants — the colours
from `BonyColors`, the 9 dp labels, the 17 dp icons (path data read straight out
of `FeedTabIcons.kt`) and the 360 dp phone width. There is no KVM in the build
environment, so an emulator screenshot is not possible; these are HTML renders of
the same geometry rather than device captures.
