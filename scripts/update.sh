#!/usr/bin/env bash
# Update SHAHU'S TV on the Fire TV WITHOUT clearing its data.
#   scripts/update.sh               → rebuild the release APK, then install it in place
#   scripts/update.sh --no-build    → install the already-built build-output/SHAHUS-TV.apk
#   scripts/update.sh --wallpapers  → only push config/wallpapers.json + wallpapers/*.jpg (no rebuild, no install)
set -euo pipefail
source "$(dirname "$0")/env.sh"
case "${1:-}" in
  --wallpapers)
    python3 -c "import json; json.load(open('$PROJECT_ROOT/config/wallpapers.json'))" || die "config/wallpapers.json is invalid JSON — not pushed"
    ensure_device
    DEST="/sdcard/Android/data/$PKG/files"
    adb_dev shell pm list packages "$PKG" | grep -q "package:$PKG" || die "SHAHU'S TV is not installed yet — run scripts/install.sh first."
    adb_dev shell mkdir -p "$DEST/wallpapers"
    adb_dev push "$PROJECT_ROOT/config/wallpapers.json" "$DEST/wallpapers.json" >/dev/null
    for f in "$PROJECT_ROOT"/wallpapers/*; do
      case "$f" in *.jpg|*.jpeg|*.png|*.webp) adb_dev push "$f" "$DEST/wallpapers/" >/dev/null; echo "pushed $(basename "$f")";; esac
    done
    info "Pushed to $DEST. Applied when SHAHU'S TV next comes to the foreground."
    adb_dev shell am start -n "$PKG/.MainActivity" >/dev/null || true
    ;;
  --no-build) "$(dirname "$0")/install.sh" ;;
  ""|--build) "$(dirname "$0")/build.sh"; "$(dirname "$0")/install.sh" ;;
  -h|--help) sed -n '2,5p' "$0" ;;
  *) die "Unknown option $1 (see --help)" ;;
esac
