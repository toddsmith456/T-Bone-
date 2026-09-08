#!/usr/bin/env bash
#
# setup-remote.sh — point this fork at your new GitHub repo and push.
#
# Usage: ./scripts/setup-remote.sh <repo-url>
#   e.g. ./scripts/setup-remote.sh git@github.com:YOURUSER/T-Bone-Fork.git
#   e.g. ./scripts/setup-remote.sh https://github.com/YOURUSER/T-Bone-Fork.git
#
set -euo pipefail

if [ $# -ne 1 ]; then
  echo "Usage: $0 <github-repo-url>"
  exit 2
fi
URL="$1"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if git remote get-url origin >/dev/null 2>&1; then
  echo "origin already set to: $(git remote get-url origin)"
  read -r -p "Replace it with $URL? [y/N] " ans
  if [ "${ans:-N}" != "y" ] && [ "${ans:-N}" != "Y" ]; then
    echo "Aborted. (Remove manually: git remote remove origin)"
    exit 1
  fi
  git remote set-url origin "$URL"
else
  git remote add origin "$URL"
fi

git branch -M main
echo ""
echo "Remote set. Pushing main (you need push access to $URL)..."
git push -u origin main
echo ""
echo "Done. Next: create your first release tag to test CI:"
echo "  git tag v0.3.36-fork1 && git push origin v0.3.36-fork1"
echo "Watch it build under GitHub -> Actions -> 'Release APKs'."
