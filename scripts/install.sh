#!/usr/bin/env bash
# Install (or update in place, keeping data) SHAHU'S TV on the Fire TV, then open it.
# Never touches the Amazon launcher, system apps or Fire TV settings.
#   scripts/install.sh [--no-launch] [--apk path/to.apk]
set -euo pipefail
source "$(dirname "$0")/env.sh"
LAUNCH=1
while [ $# -gt 0 ]; do
  case "$1" in
    --no-launch) LAUNCH=0 ;;
    --apk) shift; APK="${1:?--apk needs a path}" ;;
    -h|--help) sed -n '2,5p' "$0"; exit 0 ;;
    *) die "Unknown option $1" ;;
  esac; shift
done

verify_apk "$APK"
info "APK OK: $(basename "$APK") — $("$BUILD_TOOLS/aapt2" dump badging "$APK" | sed -n "s/^package: .*versionName='\([^']*\)'.*/v\1/p")"
ensure_device
info "Fire TV connected: $DEVICE ($(adb_dev shell getprop ro.product.model | tr -d '\r'), Fire OS $(adb_dev shell getprop ro.build.version.name | tr -d '\r' | awk '{print $3}'))"

info "Installing (adb install -r: in-place update, app data kept)…"
if ! out=$(adb_dev install -r "$APK" 2>&1); then
  echo "$out" >&2
  case "$out" in
    *INSTALL_FAILED_UPDATE_INCOMPATIBLE*) die "The installed SHAHU'S TV is signed with a different key. NOT uninstalling automatically. Use the original signing/ key, or run scripts/restore.sh --uninstall first (loses SHAHU'S TV settings)." ;;
    *INSTALL_FAILED_VERSION_DOWNGRADE*) die "A newer SHAHU'S TV is already installed. Not downgrading." ;;
    *INSTALL_FAILED_INSUFFICIENT_STORAGE*) die "Not enough storage on the Fire TV." ;;
    *) die "Install failed (see above)." ;;
  esac
fi
echo "$out" | tail -1
info "Installed: $(adb_dev shell dumpsys package "$PKG" | grep -m1 versionName | xargs)"
if [ $LAUNCH -eq 1 ]; then
  adb_dev shell am start -n "$PKG/.MainActivity" >/dev/null && info "Opened SHAHU'S TV"
fi
