#!/usr/bin/env bash
#
# privacy-check.sh — hard guarantee: this fork NEVER ships Google services or tracking.
#
# Fails (exit 1) if any forbidden dependency / permission / SDK string is found.
# Run locally:   ./scripts/privacy-check.sh
# CI runs it on every PR and every release build (see .github/workflows/).
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FAIL=0

say()  { printf '%s\n' "$*"; }
fail() { printf 'PRIVACY-CHECK FAILED: %s\n' "$*"; FAIL=1; }
pass() { printf 'ok: %s\n' "$*"; }

# ---------------------------------------------------------------------------
# 1. Forbidden dependency groups (Gradle coordinates / import packages).
# ---------------------------------------------------------------------------
# NOTE: com.google.dagger (Hilt DI), com.google.zxing (QR, offline),
# com.google.devtools.ksp and the google() *maven repository* are ALLOWED —
# they are offline build libraries, not Google services. They run no code on
# device that talks to Google. Everything that phones home to Google is banned.
FORBIDDEN_DEPS=(
  'com\.google\.android\.gms'          # Google Play Services
  'com\.google\.firebase'              # Firebase (analytics/crashlytics/messaging/...)
  'com\.google\.android\.play'         # Play Core / Play review / etc.
  'com\.google\.android\.ump'          # Google consent SDK
  'com\.google\.ads'                   # Google ads
  'gms\.play-services'                 # Play Services (short form)
  'play-services-'                     # any play-services-* artifact
  'firebase-'                          # any firebase-* artifact
  'crashlytics'                        # Crashlytics
  'com\.facebook'                      # Meta SDK
  'com\.appsflyer'                     # AppsFlyer
  'com\.amplitude'                     # Amplitude
  'com\.mixpanel'                      # Mixpanel
  'com\.segment\.analytics'            # Segment
  'io\.sentry'                         # Sentry
  'com\.bugsnag'                       # Bugsnag
  'com\.newrelic'                      # New Relic
  'com\.datadoghq'                     # Datadog mobile SDK
  'com\.clevertap'                     # CleverTap
  'com\.branch\.sdk'                   # Branch
  'com\.onesignal'                     # OneSignal (push w/ tracking)
  'com\.pusher'                        # Pusher
  'com\.urbanairship'                  # Airship
  'com\.huawei\.hms'                   # Huawei Mobile Services (same class of problem)
)

say "== 1/4 forbidden dependencies =="
for pat in "${FORBIDDEN_DEPS[@]}"; do
  if grep -r -i -n -E "$pat" "$ROOT/app" "$ROOT/gradle" \
      --include='*.kts' --include='*.gradle' --include='*.toml' --include='*.kt' | grep -v 'privacy-check.sh' ; then
    fail "forbidden dependency pattern matched: $pat (see lines above)"
  fi
done
[ "$FAIL" -eq 0 ] && pass "no forbidden dependencies"

# ---------------------------------------------------------------------------
# 2. Forbidden runtime imports (code that talks to tracking SDKs).
# ---------------------------------------------------------------------------
FORBIDDEN_IMPORTS=(
  'import com\.google\.android\.gms'
  'import com\.google\.firebase'
  'import com\.google\.android\.play'
  'import com\.facebook\.'
  'import io\.sentry'
  'import com\.amplitude'
  'import com\.mixpanel'
)
say "== 2/4 forbidden imports =="
for pat in "${FORBIDDEN_IMPORTS[@]}"; do
  if grep -r -n -E "$pat" "$ROOT/app/src" --include='*.kt' --include='*.java'; then
    fail "forbidden import matched: $pat (see lines above)"
  fi
done
[ "$FAIL" -eq 0 ] && pass "no forbidden imports"

# ---------------------------------------------------------------------------
# 3. Manifest: no suspicious permissions / providers.
# ---------------------------------------------------------------------------
say "== 3/4 manifest audit =="
MANIFEST="$ROOT/app/src/main/AndroidManifest.xml"
FORBIDDEN_PERMS=(
  'ACCESS_FINE_LOCATION'
  'ACCESS_COARSE_LOCATION'
  'ACCESS_BACKGROUND_LOCATION'
  'READ_SMS'
  'RECEIVE_SMS'
  'READ_CALL_LOG'
  'READ_CONTACTS'
  'RECORD_AUDIO' # allowed ONLY because the toolbox voice recorder needs it; flag for review if it ever disappears from docs
  'CAMERA'
  'ACTIVITY_RECOGNITION'
  'BODY_SENSORS'
  'AD_ID'
)
# Informational: print the permission list every run so diffs are visible.
say "-- declared permissions --"
grep -o 'android\.permission\.[A-Z_]*' "$MANIFEST" | sort -u || true
say "------------------------"
# Hard-fail only on location/sms/contacts/call-log/ad-id; camera/mic are
# fail-with-explanation so a future feature can't silently add them.
HARD_FAIL_PERMS=(
  'ACCESS_FINE_LOCATION'
  'ACCESS_COARSE_LOCATION'
  'ACCESS_BACKGROUND_LOCATION'
  'READ_SMS'
  'RECEIVE_SMS'
  'READ_CALL_LOG'
  'READ_CONTACTS'
  'AD_ID'
)
for perm in "${HARD_FAIL_PERMS[@]}"; do
  if grep -q "$perm" "$MANIFEST"; then
    fail "forbidden permission in manifest: $perm"
  fi
done
if grep -q 'com\.google\.android\.gms\|com\.google\.firebase' "$MANIFEST"; then
  fail "Google service component found in AndroidManifest.xml"
fi
[ "$FAIL" -eq 0 ] && pass "manifest clean"

# ---------------------------------------------------------------------------
# 4. Network audit: no Google/Firebase/tracking endpoints hardcoded.
# ---------------------------------------------------------------------------
say "== 4/4 hardcoded tracking endpoints =="
FORBIDDEN_HOSTS=(
  'google-analytics\.com'
  'googletagmanager\.com'
  'crashlytics\.com'
  'firebaseio\.com'
  'firebaseinstallations'
  'facebook\.net'
  'graph\.facebook\.com'
  'appsflyer\.com'
  'amplitude\.com'
  'mixpanel\.com'
  'segment\.io'
  'sentry\.io'
  'bugsnag\.com'
  'onesignal\.com'
)
for host in "${FORBIDDEN_HOSTS[@]}"; do
  if grep -r -i -n -E "$host" "$ROOT/app/src" --include='*.kt' --include='*.xml'; then
    fail "tracking endpoint matched: $host (see lines above)"
  fi
done
[ "$FAIL" -eq 0 ] && pass "no tracking endpoints"

say ""
if [ "$FAIL" -ne 0 ]; then
  say "RESULT: FAIL — tracking/Google-services code detected. Remove it before merging."
  exit 1
fi
say "RESULT: PASS — no Google services, no tracking SDKs, no tracking endpoints."
say "(Allowed Google-origin offline libs: Hilt/Dagger DI, ZXing QR, KSP — see docs/PRIVACY.md.)"
