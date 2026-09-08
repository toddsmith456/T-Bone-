# This fork

## Lineage

```
daomah/bony (upstream, MIT, package social.bony — approximate)
  └─ T-Bone / T-bone (MIT, package social.tbone)
       └─ THIS FORK (MIT, package social.tbone.fork)  ← you are here
```

- **Upstream source:** `https://github.com/5ToddSmith5/T-Bone-` (release tag
  `Nostr`, v0.3.36 / versionCode 44) — shipped as a source ZIP, extracted
  verbatim into this repo as the starting point.
- **License:** MIT, unchanged. See `LICENSE`. Fully open source, forever.
- **App label:** kept as **"T-bone"** per owner request. Both apps show the
  same launcher name; tell them apart by icon position, or rename this fork's
  label later in `app/src/main/res/values/strings.xml`.

## What the fork changes vs upstream T-Bone

| Area | Upstream | This fork |
|---|---|---|
| `applicationId` | `social.tbone` | **`social.tbone.fork`** — installs side-by-side with the original, never replaces it |
| `namespace` (generated code) | `social.tbone` | `social.tbone` (kept — avoids touching 200+ files; install identity comes from `applicationId`) |
| Kotlin packages | `social.tbone.*` | unchanged |
| Calendar alarm action | `social.tbone.CALENDAR_ALARM` | `social.tbone.fork.CALENDAR_ALARM` (manifest + `CalendarAlarmScheduler`) so alarms can't cross-fire between the two installs |
| FileProvider authority | `${applicationId}.fileprovider` | resolves to `social.tbone.fork.fileprovider` automatically — no conflict |
| Release signing | required `local.properties` keystore or build broke | CI-friendly: env vars → `local.properties` → debug-key fallback with a warning |
| Privacy enforcement | stance documented in README | stance **enforced**: `scripts/privacy-check.sh` + CI gates on PRs and releases (see `docs/PRIVACY.md`) |
| Releases | manual APK upload | automatic: push a `v*` tag → GitHub Actions builds + publishes APKs (see `docs/RELEASE.md`) |

## Side-by-side behavior (both apps installed)

- ✅ Install/update/remove either app independently — different package IDs,
  different data sandboxes (Room DB, DataStore, Keystore entries, alarms).
- ✅ `nostr:` links open an Android chooser (both apps handle them).
- ✅ Original T-Bone updates from its own source never touch this fork.
- ⚠️ Same launcher label ("T-bone") — long-press to check app info if unsure
  which is which. Rename freely (see above).

## Adding features ("whenever I want")

1. Branch from `main`, code, run `./scripts/privacy-check.sh`.
2. Open a PR (CI runs the privacy gate) or commit straight to `main`.
3. Release with `./scripts/make-release.sh <version>` → tag → push.
   Details: `CONTRIBUTING.md`, `docs/RELEASE.md`.

## If upstream T-Bone publishes changes

Upstream currently ships as a source ZIP, not a Git history, so:

1. Download the new `T-Bone-Source (*).zip`.
2. Unzip to a temp dir and diff against `app/`, `gradle/`, `*.kts` here.
3. Cherry-pick what you want; keep the fork-only files
   (`FORK.md`, `docs/PRIVACY.md`, `docs/RELEASE.md`, `scripts/`,
   `.github/`, and the `applicationId`/alarm-action edits).
4. Run the privacy check, bump version, release.
