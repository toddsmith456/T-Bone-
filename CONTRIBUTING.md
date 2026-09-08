# Contributing to this fork

Small project, simple rules. The non-negotiable one: **privacy first**.

## Workflow

1. Branch from `main`: `git checkout -b feat/my-thing`
2. Code. Keep the fork-only edits intact (see below).
3. Run checks:
   ```bash
   ./scripts/privacy-check.sh   # REQUIRED — must pass
   ./gradlew :app:assembleDebug # if you have the Android SDK
   ```
4. Update `CHANGELOG.md` under `[Unreleased]`.
5. PR or direct commit to `main`, then release per `docs/RELEASE.md`.

## Do not break these (fork identity)

- `applicationId = "social.tbone.fork"` in `app/build.gradle.kts` — this is
  what keeps the fork side-by-side with the original. Never change it back to
  `social.tbone`.
- Alarm action `social.tbone.fork.CALENDAR_ALARM` (manifest +
  `CalendarAlarmScheduler.kt`) — keep it unique vs upstream.
- `namespace = "social.tbone"` — generated-code package; leave it unless you
  also rename all `social.tbone.*` Kotlin packages and imports.

## Privacy rules (hard)

Read `docs/PRIVACY.md` before adding **any** dependency, permission, or
network call. Summary: no Google services, no Firebase, no analytics/crash/
tracking SDKs, no location/SMS/contacts permissions, no telemetry, keys never
leave the signer. PRs adding a dependency/permission/network call must explain
why they comply.

## License

MIT — same as upstream. Every contribution is MIT-licensed. Keep `LICENSE`
and copyright notices intact.
