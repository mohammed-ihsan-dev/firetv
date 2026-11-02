# Shared environment for SHAHU'S TV scripts (sourced, not executed).
# Fire TV access is attempted ONLY by ensure_device(), once, with short timeouts. Nothing here retries forever.
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$JAVA_HOME/bin:$PATH"
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEVICE="${SHAHU_DEVICE:-192.168.1.2:5555}"
DEVICE_HOST="${DEVICE%%:*}"
PKG="com.shahu.tv"
AMAZON_LAUNCHER="com.amazon.tv.launcher"
APK="$PROJECT_ROOT/build-output/SHAHUS-TV.apk"
# Prefer the SDK's adb (same binary the emulator uses) so two adb servers never fight; fall back to PATH.
if [ -x "$ANDROID_HOME/platform-tools/adb" ]; then ADB="$ANDROID_HOME/platform-tools/adb"; else ADB="$(command -v adb || true)"; fi
BUILD_TOOLS="$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -1)"

die()  { echo "ERROR: $*" >&2; exit 1; }
info() { echo "• $*"; }

# run_with_timeout SECONDS cmd...  → runs cmd, kills it after SECONDS (macOS has no `timeout`). Returns cmd's status (or 124).
run_with_timeout() {
  local secs="$1"; shift
  "$@" & local pid=$!
  # The watchdog must not hold stdout/stderr open, or "$(...)" callers would wait for its sleep to finish.
  ( sleep "$secs"; kill -0 "$pid" 2>/dev/null && kill "$pid" 2>/dev/null ) </dev/null >/dev/null 2>&1 & local watcher=$!
  local rc=0
  wait "$pid" 2>/dev/null || rc=$?          # (safe under set -e)
  pkill -P "$watcher" 2>/dev/null || true; kill "$watcher" 2>/dev/null || true; wait "$watcher" 2>/dev/null || true
  [ $rc -eq 143 ] && return 124
  return $rc
}

adb_dev() { run_with_timeout "${ADB_TIMEOUT:-120}" "$ADB" -s "$DEVICE" "$@"; }

check_adb() {
  [ -n "$ADB" ] && [ -x "$ADB" ] || die "adb not found. Install: brew install --cask android-platform-tools (or sdkmanager \"platform-tools\")."
}

# One connection attempt with short timeouts. Exits cleanly (status 2) if the Fire TV is off / unreachable.
ensure_device() {
  check_adb
  if "$ADB" devices 2>/dev/null | grep -q "^$DEVICE[[:space:]]*device$"; then return 0; fi
  if [ "$DEVICE_HOST" != "$DEVICE" ] || [[ "$DEVICE" == *:* ]]; then
    if ! ping -c 1 -t 3 "$DEVICE_HOST" >/dev/null 2>&1; then
      echo "Fire TV $DEVICE_HOST is not reachable on the network (TV off, asleep, or different IP)." >&2
      echo "Turn the TV on, wait for the Fire TV Home screen, then run this script again." >&2
      echo "If the IP changed: Settings → My Fire TV → About → Network, then SHAHU_DEVICE=<ip>:5555 $0" >&2
      exit 2
    fi
    run_with_timeout 15 "$ADB" connect "$DEVICE" >/dev/null 2>&1 || true
  fi
  if ! "$ADB" devices 2>/dev/null | grep -q "^$DEVICE[[:space:]]*device$"; then
    echo "Fire TV $DEVICE is reachable but ADB is not connected:" >&2
    "$ADB" devices >&2
    echo "Check: Settings → My Fire TV → Developer options → ADB debugging = ON, and accept the 'Allow USB debugging' prompt on the TV." >&2
    exit 2
  fi
}

# Verify an APK is ours and correctly signed before it goes anywhere near the device.
verify_apk() {
  local apk="$1"
  [ -f "$apk" ] || die "No APK at $apk — run scripts/build.sh first."
  [ -n "$BUILD_TOOLS" ] || die "Android build-tools not found under $ANDROID_HOME/build-tools"
  "$BUILD_TOOLS/apksigner" verify "$apk" >/dev/null 2>&1 || die "APK signature verification failed: $apk"
  local pkg; pkg=$("$BUILD_TOOLS/aapt2" dump badging "$apk" 2>/dev/null | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
  [ "$pkg" = "$PKG" ] || die "APK package is '$pkg', expected '$PKG' (debug builds are $PKG.probe — use the release APK)."
}
