# Youniversal theme polish — design direction & spec

Status: **Phases 1–2 implemented on beta** (looks + reading typography;
feed soft-card finish, widget/empty-state swaps, thread/profile polish —
2026-11-08). Phases 3–4 not started.
Date: 2026-11-08. Owner: beta (`beta` branch).

This document captures the full deep dive, the four decisions taken on
2026-11-08, and the phased implementation plan that follows from them. It is
UI-only work: no protocol, privacy, or behavioural changes. Nothing ships
without the usual `[build artifacts]` beta flow.

---

## 1. Goal

Make the Youniversal theme feel **on par with polished modern social clients**
(including Wisp and mainstream apps like X and Mastodon) — *in UI only*.
Inspiration, not imitation: no layouts, colour values, names, or brand
elements are copied from any reference app. We borrow transferable craft
principles: visual hierarchy, spacing rhythm, tonal surfaces, finished states
(design loading, empty and error screens), gentle motion, and curated looks.

**Hard rule:** classic T-Bone mode stays byte-for-byte as it is. Every change
in this spec applies to Youniversal mode only, or is additive and opt-in.

---

## 2. What the deep dive found (as of beta `f8afaec`)

### 2.1 Youniversal today is a complete kit, mostly unused

The design system lives inside the app at
`app/src/main/kotlin/dev/youniversal/theme/` (~3,200 lines, MIT):

| Area | What exists | Used by app screens today |
|---|---|---|
| Colour | light/dark/cream schemes, high-contrast variants, seed/wallpaper colour generation (`YouniversalPalette.kt`, `YouniversalTonalPalette.kt`) | ✅ via `ThemeHost` bridge into `BonyColors` |
| Typography | full Material 3 type scale with retuned weights/tracking (`YouniversalTypography.kt`) | ⚠️ font family only; screens keep classic `BonyType` size/weight ladder |
| Shapes | adjustable corner scale (`YouniversalShapes.kt`, `cornerScale` setting) | ✅ through `appCornerShape`/`appClip`/`appBorder` |
| Spacing | 2–32 dp rhythm, screen padding, control/bar heights (`YouniversalMetrics.kt`) | ❌ screens use ad-hoc dp values |
| Motion | duration + easing table, theme-morph specs (`YouniversalMotion.kt`) | ⚠️ colour morph only (`animateTransitions`) |
| Backdrop | Flat / Aurora glow / Vignette background layer (`YouniversalBackdrop.kt`) | ❌ unused |
| Components | ~35 finished parts: buttons (primary/tonal/outlined/text/icon), cards (elevated/outlined/hero), chips, switches, sliders, checkboxes/radios, text fields, dialogs, snackbar host, empty state, top/bottom bars, scaffold, FABs, avatars, badges, dividers, section headers, setting rows, segmented control, system-bar styling | ❌ **zero call sites** outside previews |

The UI is therefore **classic screens wearing new colours**: 73 files still
read `BonyColors.*` directly; 9+ files use the home-grown `BonyButton`,
`BonyToggle`, `BonyTextField` widgets. That is exactly why Youniversal reads
as "classic, but rounder" rather than polished.

### 2.2 What the engine already does well (keep)

- Settings surface in `SettingsScreen.kt`: app font (Standard / Soft),
  background Light/Dark/Cream/Auto, contrast, Material You wallpaper colours,
  accent seeds (Brand/Teal/Rose/Forest/Amber/Violet), corner radius, text
  size, animated theme changes, reset.
- `ThemeHost` + `BonyColors.applyYouniversal(scheme)` bridge: palette flows
  into every legacy screen automatically.
- Feed already gives each note a rounded inset card in Youniversal mode
  (`NoteCard.kt`, ~8 dp horizontal inset, `MaterialTheme.shapes.medium`).

### 2.3 Transferable polish principles from modern clients

1. **Hierarchy inside every post** — bold name first, quiet handle/time,
   comfortable reading body, compact-but-legible counts.
2. **Tonal surfaces + spacing rhythm** — posts as calm cards with consistent
   gaps; hairlines only where they mean something.
3. **Finished states** — shimmer skeletons while loading, designed empty and
   error screens (the kit already has `YouniversalEmptyState`).
4. **Gentle motion** — things ease; actions acknowledge with a small pop;
   colour changes morph (already available).
5. **Curated looks** — one tap sets a coordinated theme; fine controls remain
   for people who want them.
6. **Consistent chrome** — bars, dividers and system-bar tinting follow the
   same palette and shape system.

### 2.4 Known gaps this work must close

- Text-size setting currently reaches only part of the app (shared
  `BonyType` styles in 70+ files bypass the Youniversal type scale) — must be
  wired through in Youniversal mode, or "larger text" looks half-applied.
- No action-row conventions yet (reply/repost like/share with counts) —
  classic rows exist but are unstyled per state.
- Loading/empty/error states are bare (spinners, plain text).
- The kit's components have never been battle-tested in real screens —
  they need visual QA as they are adopted.

---

## 3. Decisions taken (user picks, 2026-11-08)

| Question | Choice |
|---|---|
| Overall ambition | **Polish + one-tap looks** (everything below, plus curated looks) |
| Feed feel | **Soft cards** — floating rounded cards on a deeper page, gaps between posts |
| Navigation & frame | **Keep the simple bar** — icon row stays; colours/corners align to the system, no floating pill |
| First extra flourish | **Reading typography** — names bold, times quiet, body airier, counts scannable |

Direction in one sentence: *one-tap curated looks + a real reading hierarchy +
feed posts as calm soft cards, with the kit wired in screen by screen — same
layouts, same simple bottom bar.*

---

## 4. Feature specs

### 4.1 One-tap Looks

**UI.** At the top of the Youniversal appearance settings, a row/grid of
"look" cards. Each card is a **live mini-preview** (a small composited mock:
page background, a mini post card, an accent dot, 2–3 text lines, corner
radius at that look's scale). Selected look shows a check/border.

**Behaviour.**
- Tapping a look applies a bundle: background style, contrast, accent seed
  (or wallpaper dynamic colour), font option, corner scale, font scale.
- Existing fine controls remain below in a "fine tune" area. Manually tweaking
  any bundled control switches the active look to **Custom** (nothing locks).
- "Wallpaper colours" stays its own standalone option rather than a look,
  since it tracks the wallpaper.
- Looks persist like today's controls (SharedPreferences via
  `YouniversalThemeState`); a look name is stored alongside so the UI can
  show which one is active.

**Shipped looks (v1)** — all built from existing engine ingredients; values
are starting points to hand-tune during implementation:

| Look | Background | Accent | Font | Corners | Text size | Contrast | Feel |
|---|---|---|---|---|---|---|---|
| **Paper** | Cream | Forest seed | Soft (Nunito Sans) | rounder (1.2×) | default | default | warm, calm, bookish |
| **Midnight** | Dark | Teal seed | Standard | default | default | default | deep, focused, night-friendly |
| **Porcelain** | Light | Violet seed | Standard | squarer (0.7×) | default | default | crisp, clean, professional |
| **Rosewood** | Auto (system light/dark) | Rose seed | Soft | roundest (1.4×) | default | default | friendly, personal, social |
| **Signal** | Auto | Brand (app green) | Standard | squarer (0.8×) | larger (1.15×) | raised | maximum legibility / accessibility |

Each look's palette is still generated by the engine from its seed, so dark,
light and cream surfaces inside the look stay coherent automatically. A later
refinement pass can hand-tune individual surfaces per look if generated
results feel too similar.

**Non-goals:** no downloadable/preset market; no per-look custom images.

### 4.2 Reading typography (Youniversal only)

Re-tune the *jobs* of text, not just the font:

| Role | Treatment |
|---|---|
| Author name | larger step, semi-bold — eye lands on *who* first |
| Handle & time | smaller, quiet colour (≈60% opacity onSurfaceVariant) |
| Post body | larger than today, generous line-height (article feel) |
| Counts & small print | compact size but medium weight — scannable numbers |
| Section headers / titles | firm titles; all-caps labels reserved for rare tags, used sparingly |
| Buttons / tabs | unchanged meaning, inherit the new scale cleanly |

**Mechanics.** Add a Youniversal type ladder that maps classic `BonyType`
styles to scaled versions driven by the engine's font scale, and route
`BonyType` getters through it when `YouniversalTheme.enabled` (same pattern as
`BonyType.setYouniversalFontFamily` already does for the family). Classic mode
keeps its mono ladder untouched. The `fontScale` control then genuinely
applies app-wide, which is also what makes the **Signal** look work.

### 4.3 Feed: soft cards (finish the half-built job)

- Page background sits one tonal step deeper so cards lift off it.
- Posts: rounded cards, consistent vertical gaps (~8 dp), horizontal inset
  (already present at 8 dp), card tone = surface (highlight = accent
  container, as today).
- **Remove hairline dividers between posts** in Youniversal mode (keep in
  high-contrast mode where separation matters).
- Quoted posts = softer inner card (already `SurfaceAlt` + small shape —
  align radius scale to the outer card).
- Media clipped to the card's inner corners (rounding via `clip` on the media
  container; dovetails with the fitted-media layout from `f8afaec`).
- Repost banners, content warnings, polls: live inside the same card shell —
  one calm rhythm down the feed.
- Thread view: connector lines between a parent and its replies (subtle,
  palette `outline`), replies indented once — borrowed principle, our own
  treatment.
- Profile header: same tonal card language (banner area, avatar with ring
  only if it reads well — evaluate during implementation).

### 4.4 Component swap-in (screen by screen, Youniversal mode only)

Replace home-grown widgets with kit equivalents, same layout positions:

| Priority | Screens | Swap |
|---|---|---|
| 1 | Feed, composer | buttons, text fields, toggles, dividers, empty/loading states, snackbars |
| 2 | Settings, dialogs | setting rows, switches, sliders, alert dialogs |
| 3 | Profile, threads, notifications | buttons, chips, empty states, avatars/badges |
| 4 | Long tail | remaining dialogs, toolbars, FABs |

Pattern: keep a thin wrapper (like `appCornerShape`/`appBorder` today) that
renders the kit component when `YouniversalTheme.enabled` and the classic
widget otherwise, so a screen can be migrated one call site at a time with no
behaviour change.

Also: designed **loading skeletons** for the feed (shimmer blocks shaped like
post cards) and **designed empty/error states** via `YouniversalEmptyState`.

### 4.5 Motion & feedback (opportunistic, not a phase on its own)

Use the existing `YouniversalMotion` table: screen transitions ease rather
than snap; like/repost acknowledge with a tiny scale pop; colour changes morph
(already shipped). Haptics: light tick on key taps (respect system settings;
only where the app already has a tap). Keep everything behind the existing
"animate theme changes" spirit — subtle, never bouncy.

### 4.6 Bottom bar (per the "keep simple" decision)

No floating pill, no redesign. Youniversal mode only: bar colours and corner
shape follow the system (already partly true via `appCornerShape`), active tab
gets a soft pill behind the icon (already present), labels and divider follow
the new type/divider rules. System-bar icon tinting already handled by
`YouniversalSystemBars`.

---

## 5. Phases

| Phase | Content | Why first |
|---|---|---|
| **P1 — shipped** | Looks (bundles + live preview cards) + reading typography + type-scale wiring | Instantly visible 80%; looks are what users play with and show off |
| **P2 — shipped** | Feed soft-card finish (gaps, dividers, media corners, thread connectors, profile header) + composer & settings component swap | Most-seen surfaces get the modern treatment |
| **P3** | Dialogs, empty/loading/error states, profile/thread/notifications/long-tail swaps, motion polish | Consistency everywhere |
| **P4** | Screenshot review across Light/Dark/Cream × looks, contrast/legibility pass, perf pass (palette morph, skeleton shimmer cost) | Ship gate |

Each phase is independently shippable as `[build artifacts]` on `beta`.

---

## 6. Risks & mitigations

| Risk | Mitigation |
|---|---|
| `BonyType` shared by 70+ files; changing values would alter classic mode | Bridge pattern only: Youniversal ladder resolved at read time when enabled; classic constants untouched; unit-test that classic styles are unchanged |
| Kit components never used in real screens — visual surprises | Adopt behind wrappers, screen by screen; screenshot each screen in both modes before/after |
| Looks too similar once generated | Hand-tune surfaces per look in a follow-up if needed; previews make differences visible immediately |
| Palette morph + shimmer cost on low-end devices | Motion and shimmer respect the existing animate toggle and degrade to static on demand; measure before P4 sign-off |
| Text size partially applied today | P1 explicitly wires font scale through `BonyType` bridge — acceptance test: Signal look visibly enlarges body text everywhere |
| Classic mode regression | Every phase tested in both engines; classic screenshot diff is the gate |

## 7. Acceptance (per phase)

- Youniversal mode: looks apply/reset to Custom correctly and persist across
  restart; typography roles visibly differ; feed reads as calm cards with no
  dividers; kit components render on migrated screens; no crashes on
  rotation/font-scale 0.7×–2×.
- Classic mode: pixel-identical behaviour; all existing tests green.
- Privacy check unchanged (UI-only, no new permissions or endpoints).
- Ship only via `beta` + `[build artifacts]`; public release only on explicit
  request.

## 8. Out of scope

Anything not UI: protocol/features, notification logic, media pipeline
behaviour (except purely visual media clipping), classic-mode redesign,
floating navigation bars, copying any reference app's assets or layouts.
