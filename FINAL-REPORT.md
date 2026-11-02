# SHAHU'S TV — Final Device Test Report

**Date:** 2026-11-01
**Device:** Amazon Fire TV Stick AFTSSS (sheldonp), Fire OS 7.7.1.7, Android 9 / API 28, ARMv7, 922 MB RAM, on a Sony BRAVIA KLV-32R302E
**Connection:** ADB over Wi-Fi, `192.168.1.2:5555`
**Build:** `build-output/SHAHUS-TV.apk`, `com.shahu.tv` 1.0.0, 4.2 MB, release-signed, R8-minified

All results below were measured on the physical Fire TV with `adb`, `dumpsys` and `logcat`. The screenshots are in `test-evidence/` and were captured with `adb exec-out screencap`.

---

## 1. Build verification

| Check | Result |
|---|---|
| minSdk / targetSdk | 28 / 28 (`aapt2 dump badging`) |
| ABI | No native code, so the APK is ABI-independent and runs on `armeabi-v7a`. The device reports `primaryCpuAbi=null`, which is expected for pure-DEX apps. |
| Signature | `apksigner verify` passed |
| TV launcher entry | `leanback-launchable-activity` with banner |
| Third-party dependencies | None. Kotlin stdlib only (shrunk by R8). No AndroidX, Compose, WebView or animation libraries. |
| Permissions | `ACCESS_NETWORK_STATE` and `ACCESS_WIFI_STATE` only (normal permissions, used for the Wi-Fi glyph) |
| HOME intent | **Not registered**, by design. The Amazon launcher stays the Home screen. |
| Services / boot receivers | None |

## 2. Functional tests on the device

| # | Test | Result | Evidence |
|---|---|---|---|
| 1 | Install via `adb install -r` | ✅ Success | install log |
| 2 | Launch, branding, clock, date, Wi-Fi glyph | ✅ | `02-launch-netflix.png` |
| 3 | **YouTube focus → Spider-Man theme** (`spiderman.jpg`) | ✅ | `map-02-youtube.png` |
| 4 | **Prime Video focus → Superman theme** (`superman.jpg`) | ✅ | `map-01-prime.png`, `50-final-build-prime.png` |
| 5 | **Netflix focus → Batman theme** (`batman.jpg`) | ✅ | `map-00-netflix.png`, `51-final-build-netflix.png` |
| 6 | Hotstar, Spotify, Apple TV, JioTV+, Airtel, SonyLIV, ZEE5, VLC get their configured wallpapers | ✅ All 11 mappings matched | `map-03…map-10` |
| 7 | Wallpaper changes on **focus**, without opening the app | ✅ | logcat `decoded …` per focus |
| 8 | Only one transition: 180 ms crossfade; same image → no transition, no flicker | ✅ | code + frame stats |
| 9 | App discovery is dynamic; YouTube Music is not installed and not shown | ✅ 32 apps + Settings tile | logcat `Row built …` |
| 10 | Live detection of an **installed** app (own probe build `com.shahu.tv.probe`) | ✅ `PACKAGE_ADDED` → row rebuilt in 14 ms, tile shown | `32-package-added-live.png` |
| 11 | Live detection of an **uninstalled** app | ✅ `PACKAGE_REMOVED` → tile removed in 26 ms | logcat |
| 12 | OK launches the focused app | ✅ YouTube opened (`com.amazon.firetv.youtube/dev.cobalt.app.MainActivity`) | `20-launched-youtube.png` |
| 13 | Home button → Amazon launcher (unchanged) | ✅ `com.amazon.tv.launcher/.ui.HomeActivity_vNext` | `21-home-amazon-launcher.png` |
| 14 | Reopen remembers the last focused app, including after Fire OS killed the process while YouTube ran | ✅ | `22-reopen-remembers-focus.png` |
| 15 | Menu → App Info of the focused app; Back returns to SHAHU'S TV | ✅ | `23-menu-app-info.png` |
| 16 | Back from SHAHU'S TV → Amazon Home | ✅ | focus check |
| 17 | 40× Right / 40× Left held-key bursts: focus never lost, stops at both ends, last tile fully visible | ✅ | `06-end-of-row.png`, `07-back-to-start.png` |
| 18 | External `wallpapers.json` override (no rebuild), external image file | ✅ Netflix description and wallpaper replaced | `30-override-netflix.png` |
| 19 | Missing image path → default wallpaper | ✅ `Wallpaper not found` → `default.jpg` | logcat |
| 20 | Unsafe path `../../../../system/etc/hosts` | ✅ Rejected → default | logcat |
| 21 | Invalid JSON pushed | ✅ Ignored with warning, built-in config used, same process (no crash) | `31-invalid-json-fallback.png` |
| 22 | SHAHU'S TV appears in Amazon **Your Apps & Games** | ✅ "S" icon tile | `41-amazon-app-library-bottom.png` |
| 23 | Crash buffer (`logcat -b crash`) for the whole session | ✅ 0 entries for `com.shahu.tv` | — |
| 24 | Default HOME after all tests | ✅ Still `com.amazon.tv.launcher/.ui.HomeActivity_vNext` | `resolve-activity` |

The test overrides from 18–21 and the probe app from 10–11 were removed afterwards. The device runs the built-in configuration.

## 3. Performance measurements

### Startup (`am start -W`, cold start after force-stop)

| Build | TotalTime | `onCreate` |
|---|---|---|
| First build | 1503 ms | 1045 ms |
| Final | **640 / 829 / 1162 ms** (3 runs) | 297–616 ms |

What changed: tile banners from other apps are loaded off the UI thread, except the tiles visible on the first frame.

### Wallpaper decode (background thread, 1920×1080)
59–125 ms typical, 278 ms worst case observed (while the device was swapping).

### Frame rendering (`dumpsys gfxinfo`)

| Scenario | First build | Final build |
|---|---|---|
| Rapid: 40× Right + 40× Left | 52.6% janky, p50 21 ms, p99 48 ms, 113 missed vsync | **0.00% janky, p50 5 ms, p99 9 ms, 0 missed vsync** |
| Normal pace: 1 press / 0.6 s, 24 presses (wallpaper crossfade every step) | — | **0.00% janky, p50 5 ms, p90 7 ms, p99 8–9 ms** (runs 3 and 4) |

What changed:
1. The readability gradients are painted into the wallpaper bitmap once, on the decoder thread. Previously they were three full-screen layers drawn every frame.
2. Banner corners are pre-rounded inside the tile bitmaps, removing per-frame outline clipping.
3. Duplicate full-screen backgrounds were removed.
4. `Bitmap.prepareToDraw()` uploads each new wallpaper texture before it fades in.

### Memory (`dumpsys meminfo com.shahu.tv`, PSS)

| State | Total PSS | Native heap | Graphics | Java heap |
|---|---|---|---|---|
| First build, foreground | 102.3 MB | 52.6 MB | 34.5 MB | 1.0 MB |
| Final, foreground, after heavy navigation | **79.9–83.9 MB** | 29.0–29.6 MB | 31.5–39.7 MB | 1.6 MB |
| Final, in background (after Home) | **36.7 MB** | 21.3 MB | 0.1 MB | — |

What makes up the foreground figure:
- **Two** full-screen wallpapers (16.2 MB): the one on screen plus the next one, preloaded.
- 33 tile banners rendered at tile size (about 6.6 MB, instead of the apps' full-size banners).
- The window's own screen buffers, most of the graphics figure. Any full-screen app needs these.

How memory is released:
- Wallpapers that leave the cache are recycled immediately.
- On `onStop` the preload cache is cleared.
- On `onTrimMemory(BACKGROUND+)`, which happens while a streaming app runs, everything is released.

Device free RAM (`MemAvailable`) was 243 MB at inspection and 281 MB during testing.

### Background behaviour
SHAHU'S TV has no services, timers, wake locks or boot receivers. The clock uses the system's minute broadcast, and it and the network listener are only registered while SHAHU'S TV is on screen. While YouTube was playing, Fire OS reclaimed the SHAHU'S TV process, which is normal for background apps. It reopened in a cold start with focus restored.

## 4. Safety

During this work, nothing was:
- rooted, flashed or factory-reset
- disabled, uninstalled or modified in any system app
- changed in protected settings, display resolution or density

**Nothing replaced the Amazon launcher.** The only device changes are:
- the `com.shahu.tv` package and its own data folder
- a temporary `com.shahu.tv.probe` test build, already removed

`scripts/restore.sh` removes everything.

## 5. Known limitations

1. **Artwork is original, not the actual characters.** Licensed Spider-Man, Superman and Batman artwork couldn't be legally sourced or generated here. The bundled wallpapers are original superhero-themed scenes in those slots, and you can replace them with your own licensed images without rebuilding (see the README).
2. **Home button.** It opens Amazon's Home, as you requested. SHAHU'S TV is opened from **Your Apps & Games**; you can move it to the front row with ☰ → Move. On Fire OS 7, a sideloaded app also can't take over the Home button without disruptive changes.
3. Amazon's library shows sideloaded apps with their **icon** (the "S" tile), not the 16:9 banner.
4. While a heavy streaming app runs, Fire OS may close SHAHU'S TV to free memory. Reopening is then a cold start of about 0.6–1.2 s, and the last focused app is still restored.
5. The Fire TV screensaver still appears after idle time. That's Amazon's setting, left untouched.
6. The Wi-Fi indicator shows connection and signal level only. Showing the network name would need location permission, so it was left out.
7. The Hotstar banner reads "JioHotstar" (the app's current branding). The row title uses "Disney+ Hotstar" from `wallpapers.json`; change it there if you prefer.
8. The row lists every launchable app, including Amazon utilities such as Troubleshooting. Add any you don't want to `hidden` in `wallpapers.json`.
9. YouTube Music isn't installed, so it isn't shown. If installed later, it appears automatically with a fallback wallpaper, or one you assign by adding its package to `wallpapers.json`.
