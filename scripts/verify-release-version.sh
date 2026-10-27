#!/usr/bin/env bash
#
# verify-release-version.sh — prevent the 0.5.1 version/tag mismatch from
# happening again. Run locally, on PRs, and before every tagged release build.
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION_FILE="$ROOT/version.properties"
GRADLE="$ROOT/app/build.gradle.kts"
CHANGELOG="$ROOT/CHANGELOG.md"

fail() { echo "VERSION-CHECK FAILED: $*" >&2; exit 1; }

read_prop() {
  sed -nE "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*([^[:space:]#]+).*/\1/p" "$VERSION_FILE" | head -n 1
}

[ -f "$VERSION_FILE" ] || fail "missing version.properties"
[ -f "$GRADLE" ] || fail "missing app/build.gradle.kts"
[ -f "$CHANGELOG" ] || fail "missing CHANGELOG.md"

VERSION_CODE="$(read_prop versionCode)"
VERSION_NAME="$(read_prop versionName)"
[[ "$VERSION_CODE" =~ ^[0-9]+$ ]] && [ "$VERSION_CODE" -gt 0 ] ||
  fail "version.properties versionCode must be a positive integer (got '$VERSION_CODE')"
[[ "$VERSION_NAME" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] ||
  fail "version.properties versionName must be x.y.z (got '$VERSION_NAME')"

# The Gradle build must consume the single source of truth rather than repeat
# a literal versionCode/versionName that can drift from the tag.
grep -q 'versionCode = appVersionCode' "$GRADLE" ||
  fail "app/build.gradle.kts must set versionCode from version.properties"
grep -q 'versionName = appVersionName' "$GRADLE" ||
  fail "app/build.gradle.kts must set versionName from version.properties"
if grep -Eq '^[[:space:]]*version(Code|Name)[[:space:]]*=[[:space:]]*("[0-9]|[0-9])' "$GRADLE"; then
  fail "app/build.gradle.kts contains a hardcoded literal version; use version.properties"
fi

# Runtime code must not pin another release string; BuildConfig is the only
# in-app source. Historical changelog/audit entries are intentionally excluded.
if grep -RInE 'VERSION_NAME[[:space:]]*=[[:space:]]*"[0-9]+\.[0-9]+\.[0-9]+"|versionName[[:space:]]*=[[:space:]]*"[0-9]+\.[0-9]+\.[0-9]+"' \
    "$ROOT/app/src" --include='*.kt' --include='*.java' --include='*.xml'; then
  fail "found a hardcoded app version in app/src; use BuildConfig.VERSION_NAME"
fi

grep -qF "## [$VERSION_NAME]" "$CHANGELOG" ||
  fail "CHANGELOG.md has no ## [$VERSION_NAME] section"

# A tagged release must be named exactly v<versionName>. RELEASE_TAG is used
# for local verification and by the tagged release workflow. On PR/branch runs
# GITHUB_REF_NAME is a branch name, so only consult it when GitHub says this
# checkout is actually a tag.
TAG="${RELEASE_TAG:-}"
if [ -z "$TAG" ] && [ "${GITHUB_REF_TYPE:-}" = "tag" ]; then
  TAG="${GITHUB_REF_NAME:-}"
fi
if [ -n "$TAG" ]; then
  [[ "$TAG" == v* ]] || fail "release tag '$TAG' must begin with v"
  [ "$TAG" = "v$VERSION_NAME" ] ||
    fail "tag '$TAG' does not match version.properties versionName '$VERSION_NAME'"
fi

echo "VERSION-CHECK PASSED: $VERSION_NAME (versionCode $VERSION_CODE)${TAG:+ for tag $TAG}"
