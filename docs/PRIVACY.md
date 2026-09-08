# Privacy guarantee — this fork

> **This fork is 100% privacy-focused, forever. No Google services. No tracking. Ever.**

This document is a binding rule for every contribution to this fork. If a
change conflicts with it, the change does not merge.

## 1. What is banned (hard rules)

1. **Google Play Services** (`com.google.android.gms`, any `play-services-*`
   artifact) — never a dependency, never an import.
2. **Firebase** in any form (Analytics, Crashlytics, Messaging, Remote Config,
   Installations, Performance, `firebase-*`) — never.
3. **Google Play Core / UMP / Ads SDKs** — never.
4. **Any third-party tracking / analytics / crash-reporting SDK** — including
   but not limited to Meta/Facebook, AppsFlyer, Amplitude, Mixpanel, Segment,
   Sentry, Bugsnag, New Relic, Datadog RUM, CleverTap, Branch, OneSignal,
   Pusher, Airship — never. Crash reports are local log files the user chooses
   to share (Settings → Share logs). Push notifications are in-app only.
5. **Hardcoded tracking endpoints** (google-analytics.com, googletagmanager,
   crashlytics.com, firebaseio.com, facebook graph, appsflyer, amplitude,
   mixpanel, segment, sentry, bugsnag, onesignal, …) — never.
6. **Location / SMS / call-log / contacts permissions** — never added to the
   manifest. The permission list is printed on every privacy-check run so any
   addition is visible in CI logs.
7. **Private keys stay out of the app.** Signing is delegated to an external
   signer (Amber NIP-55 / nsecBunker NIP-46); the app never handles exportable
   secret keys beyond the Android Keystore local fallback. Any feature that
   would exfiltrate key material is rejected.
8. **No feature may phone home.** New network calls go to user-chosen Nostr
   relays / Blossom servers / Tor proxy only. No telemetry beacons, no update
   pings, no "anonymous usage stats".

## 2. What is allowed (and why it is NOT "Google services")

These Google-*origin* artifacts are **offline libraries** — they execute no
network code and talk to no Google server. They are permitted:

| Artifact | What it is | Why it's fine |
|---|---|---|
| `google()` Maven repository | Download host for AndroidX/AGP artifacts | Build-time only; the app makes zero runtime calls to Google |
| `com.google.dagger:hilt-*` | Compile-time dependency injection | Codegen + annotations; no network |
| `com.google.zxing:core` | QR-code generation (Verify Identity screen) | Pure offline computation |
| `com.google.devtools.ksp` | Kotlin Symbol Processing (Room/Hilt codegen) | Build-time only |

If you are ever unsure whether a `com.google.*` artifact is a service or an
offline library, assume it is banned until proven otherwise in the PR.

## 3. How it is enforced

- **`scripts/privacy-check.sh`** — scans dependencies, imports, manifest
  permissions, and hardcoded endpoints. Fails the build on any match.
- **CI** — `.github/workflows/privacy.yml` runs the check on every PR;
  `.github/workflows/build-release.yml` runs it before every release build.
  A release cannot ship if the check fails.
- **Review rule** — every PR that adds a dependency, a permission, or a network
  call must explain in the PR description why it does not violate this document.

## 4. Data the app holds (on-device only)

- Nostr events (Room/SQLite), encrypted notes (AES-256-GCM, Keystore key),
  DataStore preferences, log file (`filesDir/logs/`).
- `allowBackup=false`. No cloud backup, no automatic upload anywhere.
- Image fetches respect the Tor toggle; pasted/shared links are stripped of
  tracking parameters (`LinkCleaner`).

## 5. Changing this document

Weakening any rule above requires an explicit, separately-tagged release and a
`CHANGELOG.md` entry titled **PRIVACY POLICY CHANGE** so users can stay on the
previous version. Strengthening the rules is always welcome.
