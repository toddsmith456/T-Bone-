#!/usr/bin/env bash
#
# changelog-section.sh — print one release's CHANGELOG.md section.
#
#   ./scripts/changelog-section.sh 0.5.5   ->  the body of "## [0.5.5]"
#
# Used by the release workflow so the published GitHub Release body carries the
# real notes (including verification limits and security disclosures) instead of
# only the generic header. Exits non-zero if the section is missing, which keeps
# a release from publishing empty notes.
set -euo pipefail

if [ $# -ne 1 ]; then
  echo "Usage: $0 <versionName>   (e.g. $0 0.5.5)" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CHANGELOG="$ROOT/CHANGELOG.md"
VERSION="$1"

[ -f "$CHANGELOG" ] || { echo "missing $CHANGELOG" >&2; exit 1; }

python3 - "$CHANGELOG" "$VERSION" <<'PY'
import re
import sys

path, version = sys.argv[1], sys.argv[2]
text = open(path, encoding="utf-8").read()

# Match "## [<version>] ..." up to the next "## [" heading (or end of file).
pattern = re.compile(
    r"^## \[" + re.escape(version) + r"\][^\n]*\n(.*?)(?=^## \[|\Z)",
    re.MULTILINE | re.DOTALL,
)
match = pattern.search(text)
if not match:
    sys.exit(f"CHANGELOG.md has no '## [{version}]' section")

body = match.group(1).strip()
if not body:
    sys.exit(f"CHANGELOG.md section for {version} is empty")

print(body)
PY
