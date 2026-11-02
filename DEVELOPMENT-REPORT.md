# SHAHU'S TV v3.0.0 — offline development report (1–2 Nov 2026)

The Fire TV was powered off for this round, so no ADB connection or installation was made to it. All testing was done on the Mac: unit tests, plus a local **Android TV emulator** (API 31 ARM64, 1920 × 1080 @ 320 dpi, the same screen geometry as the Fire TV). Anything specific to Fire OS is listed as untested and is covered in **TOMORROW-TESTING.md**.

## Result summary

| Deliverable | Status | Verified how |
|---|---|---|
| Circular app carousel (8 apps) | Done | Emulator: navigation, wrap-around both ways, focus never lost, screenshots |
| YouTube random wallpapers | Done: **4-image pool** | Unit tests (500 picks, never the same twice) and emulator log (all 4 used, a new pick only on entering YouTube) |
| JioHotstar wallpaper | Done, waiting on the file | `JIO-HOTSTAR(1).jpg` **does not exist** in `wallpapers/`. The app uses it as soon as it is added (tested on the emulator with a stand-in copy) and shows `JIO-HOTSTAR` until then |
| New wallpapers: Netflix.png, prime.jpg, fancode.jpg, settings.jpg, YOUTUBE-MAIN.png | Done | Emulator screenshots: full screen, 16:9, no distortion |
| ZEE5 / More Apps backgrounds | Kept (branded designs) | Emulator |
| Home Screen Mode (SHAHU'S TV / Classic Amazon) | Done | Emulator: switching both ways, HOME alias enabled and disabled, Classic persists after a restart |
| Home-button redirect (optional) | Implemented, **NOT verified** | The emulator cannot simulate a physical HOME key, so this needs the Fire TV |
| Device Manager | Done | Emulator: memory, storage, recent apps (with usage access), app actions, device info |
| Release APK | Built and signed | `apksigner verify` OK |
| Debug APK | Built | `app/build/outputs/apk/debug/app-debug.apk` |
| install.sh / update.sh / restore.sh | Rewritten | Offline and error paths on the Mac, full flows against the emulator |
| TOMORROW-TESTING.md | Written | — |

## APKs
| File | Package | Use |
|---|---|---|
| `build-output/SHAHUS-TV.apk` (12 MB) | `com.shahu.tv` v3.0.0 (versionCode 3) | **Install this one.** It is signed with `signing/`, so it updates the installed v2 in place |
| `app/build/outputs/apk/debug/app-debug.apk` (15 MB) | `com.shahu.tv.probe` | Debug build. It installs as a separate app, and `install.sh` refuses it deliberately |

## What changed

### Carousel (`CarouselView.kt`, `CarouselMath.kt`, `activity_main.xml`)
- The 4 × 2 grid is replaced by a flat, wrap-around carousel. The selected app's circle (136 dp) sits in the centre, with three smaller circles fading out on each side. There is no 3D wheel.
- The carousel is one focusable view that handles ◀ ▶ itself. ▲ ▼ are absorbed, and OK or ENTER fires on key-up like a normal click.
- Animation: translate, scale and alpha on the render thread, 300 ms with ease-out, no bounce. A soft halo in the app's brand colour sits behind the centre, with a white focus ring around it.
- The circular icons are original renders (`res/drawable-nodpi/circle_*.png`, 320 px each), made by `tools/render_wallpapers.py`.
- Launching is unchanged: `AppRepository.launchIntent`. The **Settings** tile now opens **SHAHU'S TV Settings**, whose first row is the native Fire TV Settings it opened before.

### Wallpapers (`config/wallpapers.json`)
| App | Background |
|---|---|
| YouTube | random from `YOUTUBE-MAIN.png`, `TV YOUTUBE 1.jpg`, `YOUTUBE-MAIN.jpg`, `YT-WALLPAPER.jpg`; never the same twice in a row |
| JioHotstar | `JIO-HOTSTAR(1).jpg` → falls back to `JIO-HOTSTAR.jpg` (from `JIO-HOTSTAR.svg`) while that file is missing |
| Netflix | `Netflix.png` |
| ZEE5 | `bg-zee5.jpg` (branded) |
| Prime Video | `prime.jpg` |
| FanCode | `fancode.jpg` |
| Settings | `settings.jpg` |
| More Apps | `bg-more-apps.jpg` (branded) |

- Images are scaled to fill the screen without stretching. All the supplied images are 16:9, so nothing meaningful is cropped. The 1672 × 941 PNGs are scaled up by about 15 %.
- The dark gradients baked into the backgrounds are lighter now (top max 50 %, bottom max 72 %), so the artwork stays the main visual.
- Memory use is unchanged: one background thread, at most 2 full-screen bitmaps cached, the next likely wallpaper preloaded, and unused bitmaps recycled.
- **No original wallpaper was modified or deleted.** The SVGs are rasterised to separate `.jpg` copies with the same names. The renderer now renders each SVG twice and only accepts the result when both agree. One earlier headless-Chrome render had placed `JIO-HOTSTAR` layers wrongly, and this check stops that from slipping through.
- The build bundles only the wallpapers the config references (now a Gradle `Sync`, so stale files drop out).

### Home Screen Mode (`HomeMode.kt`, `HomeButtonService.kt`, `SettingsActivity.kt`)
Fire OS 7.7.1.7, from `device-inspection/`:
- HOME resolves to Amazon's launcher at priority 950.
- The Home **key** is routed by Fire OS itself (signature permission `HOME_PRESSED`).

So a normal app cannot simply become the default Home. Here is what was built, all inside our own package:
- **SHAHU'S TV mode (default):** enables `.HomeAlias`, our HOME intent filter. On the emulator it is listed as a HOME candidate at priority 0, while the system launcher still wins, as on Fire OS.
- **Optional Home-button redirect:** an accessibility service that only looks at KEYCODE_HOME. A short press opens SHAHU'S TV; long-press passes through. It is off until it is enabled in Accessibility or via adb, and Classic mode makes it pass everything through. **Whether Fire OS delivers HOME to it is unknown until tomorrow.**
- **Classic mode:** disables the alias and opens Amazon Home.
- The **"Open Amazon Fire TV Home now"** row is always available.
- Amazon's launcher is never disabled, uninstalled or modified.
- **SHAHU'S TV is NOT confirmed as the active Home experience.** That depends on test 8 in TOMORROW-TESTING.md.

### Device Manager (`DeviceManagerActivity.kt`)
Only supported Android 9 APIs are used, and no numbers are invented:
- Memory: `ActivityManager.MemoryInfo`, live every 3 s. Storage: `StatFs`. There is no RAM booster.
- Recently active apps and app sizes: `UsageStatsManager` and `StorageStatsManager`. Both need usage access, granted once via adb. Without it, the screen says so.
- Per app: Open, **Stop background processes** (`killBackgroundProcesses`, background only), and **Force stop / Clear cache…**. The last one opens the native App Info page, because Android 9 does not allow third-party force stop or cache clearing. Uninstall (user apps only) uses the system dialog after our own confirmation.
- **Restart:** apps cannot reboot Fire TV, so it opens the native settings and shows the remote shortcut.
- Nothing runs automatically. No app data is cleared, and nothing is disabled.

### Scripts
- `env.sh`:
  - One connection attempt, with a ping first and short timeouts (macOS has no `timeout`, so it has its own helper).
  - Clear messages, and exit code 2 when the Fire TV is off.
  - Uses the SDK's adb.
  - Checks the APK's signature and package before installing.
- `install.sh`: `adb install -r`, which keeps app data. It explains signature and downgrade errors and never uninstalls automatically.
- `update.sh`: build and install, `--no-build`, or `--wallpapers` (push only).
- `restore.sh`:
  - Safe by default: removes **only** our accessibility service, sets Classic mode, disables our HOME alias, checks the Amazon launcher read-only, and presses HOME.
  - `--uninstall` asks you to type YES.
  - `--fix-amazon-launcher` can only *enable* the launcher.

## Offline test results
| Test | Result |
|---|---|
| Unit tests (`CarouselMathTest`, `VariantPickerTest`), 7 tests | 7 passed |
| Release build, signing, `apksigner verify` | OK |
| Debug build | OK |
| Emulator: 8 carousel positions, wrap ◀/▶, ▲/▼ ignored | OK, screenshots taken |
| Emulator: each app's background; YouTube randomness: 8 picks from the 3-image pool, then 11 picks from the 4-image pool, never the same twice | OK |
| Emulator: JioHotstar uses `JIO-HOTSTAR(1).jpg` when present (stand-in copy, removed afterwards), falls back when absent | OK |
| Emulator: launching a missing app shows "Could not open …" with no crash | OK |
| Emulator: SHAHU'S TV Settings, Home Screen Mode dialog, switching both ways | OK |
| Emulator: Device Manager (memory, storage, recent apps with usage access, app action dialog, device info) | OK |
| Emulator: More Apps panel opens, moves, and BACK returns to the carousel | OK |
| Scripts: Fire TV offline (unroutable test address) → clean exit 2, no adb connect | OK |
| Scripts: debug APK rejected; adb missing; unknown option | OK |
| Scripts on the emulator: update.sh, install.sh, restore.sh (keeps other accessibility services, Classic persists), restore.sh --uninstall --yes | OK |
| Physical HOME redirect | **Not testable** (injected keys bypass accessibility filters; the emulator image has no root) |

### Bugs found and fixed during offline testing
- `run_with_timeout` leaked its pipe, so every `$(adb …)` waited the full 120 s. Fixed.
- Under `set -e`, `restore.sh` exited silently when ours was the only accessibility service. Fixed.
- Headless Chrome occasionally mis-rendered `JIO-HOTSTAR.svg`. The renderer now checks for two matching renders.
- The More Apps panel drew tiles under its title and hint while scrolling. Fixed.
- The Gradle `Copy` kept stale wallpapers in the APK. It is now `Sync`.

## Not done / open items
- **`JIO-HOTSTAR(1).jpg` is missing.** Add it to `wallpapers/` and run `scripts/update.sh --wallpapers`, or `scripts/update.sh`.
- Nothing has been verified on the Fire TV: launches, HOME behaviour, Fire OS settings intents, performance on the Mali GPU, reboot. See TOMORROW-TESTING.md.
- **Incident from the last on-device session (1 Nov, about 23:37):** while someone else was using the physical remote, injected test keys reached **Settings → My Account → Parental Controls → PIN management**. I can't tell from here whether any setting was changed, so check it first: TOMORROW-TESTING.md, test 0. Scripted key injection is not used any more while someone is using the remote.

---

# Fire TV device session — 2 Nov 2026 (11:00–12:05)

Device: AFTSSS (sheldonp), Fire OS 7.7.1.7, ADB at 192.168.1.2:5555. Installed: `build-output/SHAHUS-TV.apk` v3.0.0 (SHA-256 `97a580a9…d564`), updated in place over v2.0.0 with app data kept. Screenshots: `test-evidence/v3-*.png`.

Unless a row says otherwise, keys were sent over ADB (`input keyevent`), and only while SHAHU'S TV was confirmed to be in the foreground. HOME tests marked "physical" used the real remote.

## Verified on the TV
| Test | Result |
|---|---|
| ADB reconnect (saved IP, authorized) | ✅ `192.168.1.2:5555 device product:sheldonp model:AFTSSS` |
| Parental controls unchanged | ✅ `PARENTAL_CONTROLS_STATE=0` (off) |
| Unit tests and build | ✅ 7/7 tests; signed release APK |
| Carousel ◀ ▶ through all 8 apps, wrap-around both ways, ▲ ▼ ignored | ✅ |
| Backgrounds: YouTube (random), JioHotstar (fallback, see below), Netflix.png, ZEE5, prime.jpg, fancode.jpg, settings.jpg, More Apps | ✅ `v3-sheet-8-backgrounds.png` |
| YouTube random: 11 entries, never the same image twice, all 4 used, stable while selected | ✅ |
| Select (DPAD_CENTER and ENTER) launches YouTube, JioHotstar, Netflix, ZEE5, Prime Video, FanCode, SHAHU'S TV Settings, More Apps (26 apps) | ✅ Netflix can't be screenshotted (protected video) and was verified by its foreground window |
| BACK: More Apps panel → carousel; Settings → carousel | ✅ |
| Device Manager: RAM (305 MB free of 0.94 GB), storage (1.1 GB free of 5.3 GB), recent apps and app sizes (after granting usage access), app actions, Stop background processes, native App Info (Force stop / Clear data / Clear cache) | ✅ |
| Fire OS native pages | Manage apps ✅ · My Fire TV (Restart) ✅ · Storage page: does not exist on Fire OS, row now hidden · Accessibility and Usage-access pages: Amazon placeholders, buttons now hidden |
| Performance | The first build had 45 % janky frames while stepping quickly. Wallpapers are now decoded at 1280×720, which looks the same on this 1366×768 Sony panel: 7.4 % janky, 90th percentile 15 ms, memory 70 → 53 MB. Wallpaper decode 70–520 ms, median 127 ms. Cold start 1.46 s |
| Home Screen Mode switching: Classic opens Amazon Home; SHAHU'S TV returns to SHAHU'S TV; "Open Amazon Fire TV Home now" | ✅ |
| **Startup in SHAHU'S TV mode**: boot animation, then Amazon Home, then SHAHU'S TV opens automatically and stays | ✅ SHAHU'S TV appears about 31 s after boot completes (about 75 s after power-on). This uses a BOOT_COMPLETED receiver at priority 999; without the priority it took about 2 min |
| **HOME inside SHAHU'S TV** (SHAHU'S TV mode) | ✅ with a limitation: Amazon Home shows for about **5 s**, then SHAHU'S TV returns by itself |
| HOME twice (second press within about 5 s) | ✅ stays on Amazon Home. This is the escape hatch |
| HOME inside Amazon Home | ✅ stays on Amazon Home |
| Apps opened from SHAHU'S TV, Fire TV Settings, "Open Amazon Home", long-press HOME (Quick Settings) | ✅ not bounced back |
| Reboot persistence: Home mode, last selected app, usage access | ✅ |
| Amazon launcher preserved | ✅ enabled, never modified; HOME resolves to `com.amazon.tv.launcher/.ui.HomeActivity_vNext` |

## Fire OS 7.7.1.7 limitations (all verified)
1. **The Home button cannot be taken over.**
   - Fire OS always starts `com.amazon.tv.launcher/.ui.HomeActivity_vNext` by explicit component (as uid 1000).
   - `cmd package set-home-activity` reports "Success" but is ignored. SHAHU'S TV now clears that dormant setting itself.
   - A physical HOME press never reaches accessibility key filters (tested with the real remote).
   - `amazon.intent.action.HOME_PRESSED` is signature-protected: Amazon's Vizzini receives it, SHAHU'S TV does not.
2. **The "about 5 s" in Scenario A comes from Android 9's app-switch protection.** After a HOME press the system holds back activity starts from normal apps (`Activity start request from 10006 stopped`, released about 4.5 s later). Only system-signed apps are exempt.
3. **Amazon Home always appears first after boot.** SHAHU'S TV can only follow it once Fire OS hands out BOOT_COMPLETED.
4. The only way to remove Amazon Home entirely would be disabling `com.amazon.tv.launcher`. That would also break Fire TV Settings, because `android.settings.SETTINGS` resolves to `com.amazon.tv.launcher/.ui.SettingsActivity`. **Not done, and not recommended.**

## Not yet verified (testing stopped on request)
- **Classic mode across a reboot:** Amazon Home should stay, and SHAHU'S TV should not open. The code path is simple (`BootReceiver` does nothing unless the mode is SHAHU'S TV), but it was not tested on the TV.
- **Steps 6 and 7 of the Classic sequence:** the run was blocked because the TV showed **"Unable to Detect Your Remote"** after the last reboot. The physical remote needs re-pairing: hold HOME for 10 s near the TV, or replace the batteries.
- **JioHotstar's own image:** `JIO-HOTSTAR(1).jpg` is still not in `wallpapers/`, so JioHotstar shows `JIO-HOTSTAR.jpg`. Add the file, then run `scripts/update.sh --wallpapers`.

## Recovery
- Inside SHAHU'S TV: **Settings → Home Screen Mode → Amazon Fire TV — Classic Home**. This turns off the auto-start after boot and the HOME return immediately.
- **Double-press HOME** at any time to stay on Amazon Home.
- From the Mac: `scripts/restore.sh` sets Classic mode, removes only SHAHU'S TV's accessibility entry, and checks the Amazon launcher. `scripts/restore.sh --uninstall` removes SHAHU'S TV completely.
- Without a Mac: Fire TV **Settings → Applications → Manage Installed Applications → SHAHU'S TV → Force stop or Uninstall**. Amazon Home is always underneath and works normally.
- To return to SHAHU'S TV from Amazon Home: open **SHAHU'S TV** from Your Apps. Tip: highlight it, press ☰ and choose **Move to front**, so it sits first in the row.
