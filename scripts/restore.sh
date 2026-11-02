#!/usr/bin/env bash
# RECOVERY: return the Fire TV to the original Amazon interface. Never disables/uninstalls Amazon or system packages.
#
#   scripts/restore.sh               SAFE (default): Classic mode, Home-button redirect OFF, SHAHU'S TV stays installed
#   scripts/restore.sh --uninstall   SAFE + remove SHAHU'S TV completely (asks to confirm; --yes to skip)
#   scripts/restore.sh --fix-amazon-launcher   only if the check below says Amazon's launcher is disabled:
#                                    re-ENABLES it (pm enable). It never disables anything.
set -euo pipefail
source "$(dirname "$0")/env.sh"
UNINSTALL=0; YES=0; FIX_LAUNCHER=0
for a in "$@"; do
  case "$a" in
    --uninstall) UNINSTALL=1 ;; --yes) YES=1 ;; --fix-amazon-launcher) FIX_LAUNCHER=1 ;;
    -h|--help) sed -n '2,8p' "$0"; exit 0 ;; *) die "Unknown option $a" ;;
  esac
done
ensure_device

installed=0; adb_dev shell pm list packages "$PKG" | grep -q "package:$PKG" && installed=1

# 1. Home-button redirect OFF: remove ONLY our service from the enabled accessibility services list.
cur=$(adb_dev shell settings get secure enabled_accessibility_services | tr -d '\r')
if [[ "$cur" == *"$PKG/"* ]]; then
  new=$(echo "$cur" | tr ':' '\n' | { grep -v "^$PKG/" || true; } | paste -sd ':' -)
  if [ -z "$new" ]; then adb_dev shell settings delete secure enabled_accessibility_services >/dev/null
  else adb_dev shell settings put secure enabled_accessibility_services "$new"; fi
  info "Home-button redirect (accessibility service) turned OFF. Other accessibility services untouched."
else
  info "Home-button redirect was not enabled."
fi

# 2. Classic mode + HOME alias off (our own components only).
if [ $installed -eq 1 ]; then
  adb_dev shell am start -n "$PKG/.MainActivity" --es com.shahu.tv.SET_HOME_MODE classic >/dev/null 2>&1 || true
  adb_dev shell pm disable "$PKG/.HomeAlias" >/dev/null 2>&1 || true
  info "SHAHU'S TV Home Screen Mode → Amazon Classic; SHAHU'S TV HOME alias disabled."
fi

# 3. Amazon launcher health check (read-only unless --fix-amazon-launcher).
if ! adb_dev shell pm list packages "$AMAZON_LAUNCHER" | tr -d '\r' | grep -qx "package:$AMAZON_LAUNCHER"; then
  echo "NOTE: $AMAZON_LAUNCHER is not present on $DEVICE (not a Fire TV?). Nothing to check." >&2
elif adb_dev shell pm list packages -d | tr -d '\r' | grep -qx "package:$AMAZON_LAUNCHER"; then
  if [ $FIX_LAUNCHER -eq 1 ]; then
    adb_dev shell pm enable "$AMAZON_LAUNCHER" && info "Amazon launcher re-enabled."
  else
    echo "WARNING: $AMAZON_LAUNCHER is DISABLED (SHAHU'S TV never does this). Re-enable it with: scripts/restore.sh --fix-amazon-launcher" >&2
  fi
else
  info "Amazon launcher ($AMAZON_LAUNCHER) is installed and enabled."
fi

# 4. Optional uninstall of SHAHU'S TV (and only SHAHU'S TV).
if [ $UNINSTALL -eq 1 ] && [ $installed -eq 1 ]; then
  if [ $YES -eq 0 ]; then
    read -r -p "Uninstall SHAHU'S TV ($PKG) and its settings? Type YES: " ans
    [ "$ans" = "YES" ] || die "Not uninstalled."
  fi
  adb_dev shell am force-stop "$PKG" || true
  adb_dev uninstall "$PKG"
  adb_dev shell rm -rf "/sdcard/Android/data/$PKG" 2>/dev/null || true
  info "SHAHU'S TV uninstalled."
fi

# 5. Go to the Amazon Home screen and show what HOME resolves to.
adb_dev shell input keyevent KEYCODE_HOME
info "Default HOME activity: $(adb_dev shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1 | tr -d '\r')"
