#!/usr/bin/env bash
# Build the signed release APK → build-output/SHAHUS-TV.apk
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$PROJECT_ROOT"

# One-time signing key. KEEP signing/ — updates must be signed with the same key.
if [ ! -f signing/shahus-tv.jks ]; then
  echo "Creating signing key (one-time)…"
  PASS="$(openssl rand -hex 16)"
  keytool -genkeypair -keystore signing/shahus-tv.jks -alias shahus-tv -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$PASS" -keypass "$PASS" -dname "CN=SHAHU'S TV, O=Personal" >/dev/null
  printf 'storeFile=signing/shahus-tv.jks\nstorePassword=%s\nkeyAlias=shahus-tv\nkeyPassword=%s\n' "$PASS" "$PASS" > signing/keystore.properties
  chmod 600 signing/keystore.properties signing/shahus-tv.jks
fi
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties

python3 -c "import json,sys; json.load(open('config/wallpapers.json'))" || { echo "config/wallpapers.json is invalid JSON"; exit 1; }

./gradlew --no-daemon -q :app:assembleRelease
cp app/build/outputs/apk/release/app-release.apk "$APK"
"$BUILD_TOOLS/apksigner" verify "$APK"
echo "Built $APK ($(du -h "$APK" | cut -f1))"
"$BUILD_TOOLS/aapt2" dump badging "$APK" | grep -E "^package|sdkVersion|targetSdk|native-code|leanback-launchable" || true
