#!/usr/bin/env bash
set -euo pipefail
case "${1:-beta}" in
  beta) PKG=social.tbone.fork.beta; PREVIOUS=/tmp/previous-beta.apk; NEW=beta-preflight/app-beta.apk ;;
  release) PKG=social.tbone.fork; PREVIOUS=/tmp/previous-release.apk; NEW=beta-preflight/app-release.apk ;;
  *) echo "Expected beta or release" >&2; exit 2 ;;
esac
DATA=/data/user/0/$PKG
# A fresh emulator, not a user's installed app.
adb install "$PREVIOUS"
adb shell am start -W -n "$PKG/social.tbone.MainActivity" >/dev/null
adb shell am force-stop "$PKG"
adb root >/dev/null
adb wait-for-device
OWNER=$(adb shell stat -c '%u:%g' "$DATA/files" | tr -d '\r')
adb shell "mkdir -p '$DATA/files/webxdc/apps/test/files' '$DATA/files/webxdc/updates' '$DATA/cache/webxdc_share'; echo legacy > '$DATA/files/webxdc/apps/test/files/index.html'; echo journal > '$DATA/files/webxdc/updates/test.log'; echo share > '$DATA/cache/webxdc_share/file.txt'; echo preserve > '$DATA/files/unrelated-upgrade-marker'; chown -R '$OWNER' '$DATA/files/webxdc' '$DATA/cache/webxdc_share' '$DATA/files/unrelated-upgrade-marker'"
# Android must accept the signed APK as an in-place upgrade without uninstalling.
adb install -r "$NEW"
adb shell am start -W -n "$PKG/social.tbone.MainActivity" >/dev/null
sleep 15
adb shell "test ! -e '$DATA/files/webxdc' && test ! -e '$DATA/cache/webxdc_share' && test \"\$(cat '$DATA/files/unrelated-upgrade-marker')\" = preserve && test -f '$DATA/shared_prefs/legacy_mini_app_cleanup_v1.xml'"
adb shell pidof "$PKG" >/dev/null
echo "Signed $1 installed over its prior version; legacy private files removed and unrelated data preserved."
