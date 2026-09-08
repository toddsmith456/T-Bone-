#!/usr/bin/env bash
#
# make-release.sh — bump versionCode/versionName and stage a CHANGELOG entry.
#
# Usage: ./scripts/make-release.sh 0.3.37
#
# Then: edit CHANGELOG.md notes -> commit -> git tag v0.3.37 -> git push origin main --tags
# CI builds the APKs and publishes the GitHub Release. See docs/RELEASE.md.
#
set -euo pipefail

if [ $# -ne 1 ]; then
  echo "Usage: $0 <new-versionName>   (e.g. $0 0.3.37)"
  exit 2
fi
NEW_NAME="$1"
if ! [[ "$NEW_NAME" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "error: version must look like 0.3.37 (got '$NEW_NAME')"
  exit 2
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE="$ROOT/app/build.gradle.kts"

CODE=$(grep -oE 'versionCode = [0-9]+' "$GRADLE" | grep -oE '[0-9]+')
OLD_NAME=$(grep -oE 'versionName = "[^"]+"' "$GRADLE" | sed 's/.*"\([^"]*\)".*/\1/')
NEW_CODE=$((CODE + 1))

sed -i "s/versionCode = $CODE/versionCode = $NEW_CODE/" "$GRADLE"
sed -i "s/versionName = \"$OLD_NAME\"/versionName = \"$NEW_NAME\"/" "$GRADLE"

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

echo ""
echo "Bumped: versionCode $CODE -> $NEW_CODE, versionName $OLD_NAME -> $NEW_NAME"
echo ""
echo "Next steps:"
echo "  1. Edit CHANGELOG.md release notes"
echo "  2. ./scripts/privacy-check.sh"
echo "  3. git add -A && git commit -m \"Release $NEW_NAME\""
echo "  4. git tag v$NEW_NAME && git push origin main --tags"
echo "  CI will build + publish the GitHub Release with APKs."
