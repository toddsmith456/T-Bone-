#!/usr/bin/env bash
#
# make-release.sh — bump the single-source release identity and stage a
# CHANGELOG entry.
#
# Usage: ./scripts/make-release.sh 0.5.2
#
# Release identity lives in version.properties. app/build.gradle.kts reads it,
# and BuildConfig exposes it to every in-app version display. A tag gate in CI
# rejects a release tag whose version does not match both files.
#
set -euo pipefail

if [ $# -ne 1 ]; then
  echo "Usage: $0 <new-versionName>   (e.g. $0 0.5.2)"
  exit 2
fi
NEW_NAME="$1"
if ! [[ "$NEW_NAME" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "error: version must look like 0.5.2 (got '$NEW_NAME')"
  exit 2
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION_FILE="$ROOT/version.properties"
GRADLE="$ROOT/app/build.gradle.kts"

if [ ! -f "$VERSION_FILE" ]; then
  echo "error: missing $VERSION_FILE"
  exit 1
fi

read_prop() {
  sed -nE "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*([^[:space:]#]+).*/\1/p" "$VERSION_FILE" | head -n 1
}

CODE="$(read_prop versionCode)"
OLD_NAME="$(read_prop versionName)"
if ! [[ "$CODE" =~ ^[0-9]+$ ]] || ! [[ "$OLD_NAME" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "error: invalid version.properties (versionCode='$CODE', versionName='$OLD_NAME')"
  exit 1
fi
if ! python3 - "$OLD_NAME" "$NEW_NAME" <<'PY'
import sys

def parts(v):
    return tuple(map(int, v.split(".")))

raise SystemExit(0 if parts(sys.argv[2]) > parts(sys.argv[1]) else 1)
PY
then
  echo "error: $NEW_NAME must be newer than current $OLD_NAME"
  exit 2
fi

NEW_CODE=$((CODE + 1))
python3 - "$VERSION_FILE" "$NEW_NAME" "$NEW_CODE" <<'PY'
import sys

path, name, code = sys.argv[1], sys.argv[2], sys.argv[3]
updated = {"versionCode": False, "versionName": False}
lines = []
for line in open(path, encoding="utf-8"):
    key = line.split("=", 1)[0].strip().lstrip("# ").strip()
    if key == "versionCode":
        line = f"versionCode={code}\n"
        updated["versionCode"] = True
    elif key == "versionName":
        line = f"versionName={name}\n"
        updated["versionName"] = True
    lines.append(line)
if not all(updated.values()):
    raise SystemExit("version.properties must define versionCode and versionName")
open(path, "w", encoding="utf-8").writelines(lines)
PY

# Insert CHANGELOG stub under [Unreleased] if not already present.
TODAY="$(date +%F)"
if ! grep -q "## \[$NEW_NAME\]" "$ROOT/CHANGELOG.md"; then
  python3 - "$ROOT/CHANGELOG.md" "$NEW_NAME" "$TODAY" <<'EOF'
import sys
path, ver, today = sys.argv[1], sys.argv[2], sys.argv[3]
stub = f"""## [{ver}] — {today}

### Added
- (describe changes here)

### Fixed
- (describe fixes here)

"""
text = open(path).read()
marker = "## [Unreleased]"
assert marker in text, "CHANGELOG.md missing [Unreleased] section"
text = text.replace(marker, marker + "\n\n" + stub.rstrip() + "\n", 1)
open(path, "w").write(text)
print(f"CHANGELOG stub for {ver} added — please edit the entries.")
EOF
fi

# Fail early if the Gradle wiring, changelog, or optional RELEASE_TAG disagree.
"$ROOT/scripts/verify-release-version.sh"

echo ""
echo "Bumped: versionCode $CODE -> $NEW_CODE, versionName $OLD_NAME -> $NEW_NAME"
echo ""
echo "Next steps:"
echo "  1. Edit CHANGELOG.md release notes"
echo "  2. ./scripts/privacy-check.sh"
echo "  3. git add -A && git commit -m \"Release $NEW_NAME\""
echo "  4. git tag -a v$NEW_NAME -m \"Release v$NEW_NAME\" && git push origin main --tags"
echo "  CI verifies the tag matches version.properties before building."
