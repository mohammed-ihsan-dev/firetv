# SHAHU'S TV v3.0.0 — Fire TV test plan (most items now done: see DEVELOPMENT-REPORT.md, device session 2 Nov)

Everything below needs the real Fire TV: Sony BRAVIA KLV-32R302E with Fire TV Stick AFTSSS, Fire OS 7.7.1.7.
None of it has been run on the Fire TV yet. The APK was built and tested only on the Mac and on a local Android TV emulator (Android 12). See DEVELOPMENT-REPORT.md.

Run all commands from the project folder:

```bash
cd ~/Desktop/FIRETV
source scripts/env.sh          # sets ADB, DEVICE=192.168.1.2:5555, helper functions
```

> **Before you start:** keep the remote in your own hand while the scripted tests run. Last night, injected key presses got mixed up with someone else using the remote, and the test ended up inside **Settings → My Account → Parental Controls (PIN screen)**. Do test 0 first.

---

## 0. Parental controls were not changed (do this first)
On the TV: **Settings → Preferences → Parental Controls**.
- [ ] Parental Controls show the state you expect (normally **OFF**). If a PIN prompt appears that you never set up, cancel it.
- If a PIN was created by accident, it is your Amazon account PIN. Reset it at amazon.in → Your Account → Prime Video settings → Parental controls. **Do not** reset the device.

## 1. Reconnect ADB
1. Turn on the TV and wait for the Amazon Home screen.
2. Check that ADB debugging is still ON: **Settings → My Fire TV → Developer options → ADB debugging**.
3. Connect:
```bash
ping -c 2 192.168.1.2
"$ADB" connect 192.168.1.2:5555
"$ADB" devices                       # expect: 192.168.1.2:5555   device
```
- [ ] If `unauthorized` appears, accept "Allow USB debugging?" on the TV (tick *Always allow*) and run `"$ADB" connect` again.
- [ ] If the IP changed (see **Settings → My Fire TV → About → Network**), use `export SHAHU_DEVICE=<new-ip>:5555`.

## 2. Install / update (app data is kept)
```bash
scripts/install.sh                   # verifies the APK and signature, then runs adb install -r (in place)
```
Expected output ends with `Installed: versionName=3.0.0` and `Opened SHAHU'S TV`.
APK: `build-output/SHAHUS-TV.apk`, SHA-256 `3fec5ec05948421ea913084180c23cc03ddde66af94a591e18e96e016de3c3db`.

Start the app log, and leave this terminal open for all the tests below:
```bash
"$ADB" -s "$DEVICE" logcat -c
"$ADB" -s "$DEVICE" logcat -v time -s ShahuTV:I ShahuWallpaper:I ShahuHomeMode:I ShahuHomeButton:I ShahuDeviceMgr:I AndroidRuntime:E
```

## 3. Circular carousel and remote (use the PHYSICAL remote)
- [ ] ◀ ▶ moves between the 8 apps. The selected app is the large centre circle, with smaller circles on each side.
- [ ] It wraps around: ▶ on More Apps goes to YouTube, and ◀ on YouTube goes to More Apps.
- [ ] ▲ ▼ do nothing, and focus is never lost (the white ring stays on the centre).
- [ ] Holding ◀ or ▶ scrolls smoothly without stutter.
- [ ] Nothing on screen is clipped at the edges of the Sony screen (header, clock and the outer circles).
- [ ] BACK on the carousel returns to Amazon Home (unchanged behaviour).

## 4. All eight apps launch (OK/SELECT, then also try ENTER from a keyboard or app remote)
| # | App | Expected | ✓ |
|---|---|---|---|
| 1 | YouTube | `com.amazon.firetv.youtube` opens | [ ] |
| 2 | JioHotstar | `in.startv.hotstar` opens | [ ] |
| 3 | Netflix | `com.netflix.ninja` opens | [ ] |
| 4 | ZEE5 | `com.zee5.amazon` opens | [ ] |
| 5 | Prime Video | `com.amazon.firebat` opens | [ ] |
| 6 | FanCode | `com.fancode.tv` opens | [ ] |
| 7 | Settings | **SHAHU'S TV Settings** opens. Its first row is "Fire TV Settings" | [ ] |
| 8 | More Apps | in-app panel with every other app. BACK returns to the carousel | [ ] |

To check which app is in front:
```bash
"$ADB" -s "$DEVICE" shell dumpsys activity activities | grep -m1 mResumedActivity
```
To return to SHAHU'S TV after each app, press HOME and then open SHAHU'S TV from Your Apps, or run `"$ADB" -s "$DEVICE" shell am start -n com.shahu.tv/.MainActivity`.

## 5. Wallpapers
| App selected | Expected background | ✓ |
|---|---|---|
| YouTube | one of 4, at random: `YOUTUBE-MAIN.png`, `TV YOUTUBE 1`, `YOUTUBE-MAIN` (SVG version), `YT-WALLPAPER` | [ ] |
| JioHotstar | `JIO-HOTSTAR(1).jpg` **once that file exists** (see below); until then `JIO-HOTSTAR` from the SVG | [ ] |
| Netflix | `Netflix.png` | [ ] |
| ZEE5 | branded purple ZEE5 background | [ ] |
| Prime Video | `prime.jpg` (superheroes, blue) | [ ] |
| FanCode | `fancode.jpg` (orange, footballers) | [ ] |
| Settings | `settings.jpg` (grey gears) | [ ] |
| More Apps | branded app-library background | [ ] |

- [ ] Each change is a smooth crossfade of about 0.3 s. There is no flicker and no black frame.
- [ ] **YouTube random:** move ▶ then ◀ back onto YouTube 10 times. Every time the log shows `Wallpaper for com.amazon.firetv.youtube → X (previous Y)` and X ≠ Y. While YouTube stays selected the picture does not change.
- [ ] The images fill the whole screen without stretching. Logos and characters are still visible above the carousel.

**JIO-HOTSTAR(1).jpg is still missing.** It was not in `wallpapers/` on 2 Nov, and no stand-in was invented. When you have it:
```bash
cp ~/path/to/"JIO-HOTSTAR(1).jpg" wallpapers/
scripts/update.sh --wallpapers       # pushes it to the TV; no rebuild needed
# or to bundle it into the APK permanently:
scripts/update.sh
```

## 6. Device Manager (Settings tile → Device Manager)
- [ ] Memory shows real values from Fire OS (about 0.9 GB in total: MemTotal is 922 MB in device-inspection/09-meminfo.txt). It updates every 3 s.
- [ ] Internal storage shows the free and total space.
- [ ] Device information shows AFTSSS, Android 9 (API 28), the Fire OS build, 1920 × 1080 @ 320 dpi, and the IP address.
- [ ] **Recently active apps** shows "Usage access is not granted". Grant it once and reopen:
```bash
"$ADB" -s "$DEVICE" shell appops set com.shahu.tv GET_USAGE_STATS allow
```
  - [ ] After that, recently used apps appear with "x minutes ago", and the installed apps show their sizes and cache.
- [ ] Select an installed app, then **Stop background processes**. A toast appears and nothing crashes. A foreground app is not affected (this is expected).
- [ ] Select an installed app, then **Force stop / Clear cache… (App Info)**. The native Fire TV App Info page opens, with Force stop and Clear cache.
- [ ] **Clear cache** in App Info works. **Clear data** asks for confirmation (do not confirm).
- [ ] **Restart Fire TV…** explains the steps and opens the native settings. Restart from **My Fire TV → Restart** if you want to test reboot persistence (section 9).
- [ ] **Manage installed applications** and **Storage settings** open the native pages. If one is unsupported on Fire OS, "Fire TV Settings" opens instead with a toast. Note which.

## 7. Home Screen Mode and Classic Amazon Home
- [ ] SHAHU'S TV Settings → **Home Screen Mode** shows "SHAHU'S TV" by default.
- [ ] Choose **Amazon Fire TV — Classic Home**. Amazon Home opens immediately. Go back into SHAHU'S TV: the mode now shows "Amazon Classic".
- [ ] Choose **SHAHU'S TV — Custom Home** again.
- [ ] **Open Amazon Fire TV Home now** opens the original Amazon interface.
- [ ] The Amazon launcher is untouched:
```bash
"$ADB" -s "$DEVICE" shell pm list packages -d | grep launcher || echo "no disabled launcher — OK"
"$ADB" -s "$DEVICE" shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1
# expect: com.amazon.tv.launcher/.ui.HomeActivity_vNext
"$ADB" -s "$DEVICE" shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME
# SHAHU mode: also lists com.shahu.tv/.HomeAlias (priority 0). Classic mode: does not.
```

## 8. Physical HOME button (EXPERIMENTAL redirect — the key test)
Fire OS sends HOME to Amazon's launcher, so SHAHU'S TV **cannot** be the Home-button target by normal means. The optional accessibility service is the only non-root, non-disabling approach. It could not be tested on the emulator, because injected keys skip accessibility filters and the emulator image has no root.

a) Without the redirect (the baseline):
- [ ] HOME → Amazon Home. Long-press HOME → the Quick Settings overlay.

b) Turn the redirect on. Try the TV menu first: **Settings → Accessibility → SHAHU'S TV Home button → On**. If it isn't listed there, use adb:
```bash
"$ADB" -s "$DEVICE" shell settings put secure enabled_accessibility_services com.shahu.tv/com.shahu.tv.HomeButtonService
"$ADB" -s "$DEVICE" shell settings put secure accessibility_enabled 1
```
Watch the log for `ShahuHomeButton` lines, then:
- [ ] Short HOME press inside Netflix (or any app) → SHAHU'S TV carousel. The log shows `Home key DOWN received` and `→ SHAHU'S TV: true`.
  - If **no** `Home key DOWN` line appears, Fire OS does not deliver HOME to accessibility services on this build. The redirect cannot work here, so turn it off (step d) and use Classic Home with SHAHU'S TV in Your Apps / the Home row.
  - If Amazon Home flashes briefly before SHAHU'S TV appears, that is expected: the DOWN key is deliberately passed through so long-press keeps working.
- [ ] Long-press HOME still opens Quick Settings.
- [ ] HOME while the More Apps panel is open → the carousel, with the panel closed.
- [ ] Switch to **Classic** mode: HOME goes to Amazon Home again, even with the service on.

c) If anything feels wrong, recover at once:
```bash
scripts/restore.sh           # turns OFF only our service, sets Classic mode, presses HOME
```
d) To turn off only the redirect:
```bash
scripts/restore.sh           # same safe command; SHAHU'S TV stays installed
```

## 9. Reboot persistence
```bash
"$ADB" -s "$DEVICE" reboot          # or: Settings → My Fire TV → Restart
# wait for the TV, then reconnect:
"$ADB" connect 192.168.1.2:5555
```
- [ ] The Fire TV boots to **Amazon Home**. SHAHU'S TV adds no boot service, so this is expected and unchanged.
- [ ] SHAHU'S TV opens from Your Apps, and the last selected app is remembered.
- [ ] Home Screen Mode keeps its setting.
- [ ] If the redirect was on: it is still listed in `settings get secure enabled_accessibility_services`, and a short HOME press opens SHAHU'S TV.

## 10. Performance and memory
```bash
"$ADB" -s "$DEVICE" shell dumpsys meminfo com.shahu.tv | grep -E "TOTAL|Native Heap|Graphics"
"$ADB" -s "$DEVICE" shell dumpsys gfxinfo com.shahu.tv | grep -E "Total frames|Janky|90th|99th"
"$ADB" -s "$DEVICE" shell dumpsys gfxinfo com.shahu.tv reset
```
- [ ] Hold ▶ for 10 s, then check gfxinfo. Janky frames should be under about 10 %.
- [ ] In the log, `decoded … in Xms` should be under about 300 ms for each wallpaper. Netflix.png and YOUTUBE-MAIN.png are PNGs, so they will be slowest.
- [ ] meminfo TOTAL should stay roughly under 120 MB. The wallpaper cache holds at most 2 full-screen bitmaps (about 16 MB).
- [ ] After opening Netflix, the log shows `onTrimMemory … releasing wallpaper` when Fire OS asks for memory.

## 11. Rollback (only if needed)
```bash
scripts/restore.sh                       # safe: Classic, redirect off, SHAHU'S TV stays installed
scripts/restore.sh --uninstall           # also remove SHAHU'S TV (asks you to type YES)
scripts/restore.sh --fix-amazon-launcher # only if restore reports the Amazon launcher as DISABLED
```
None of these disable or uninstall any Amazon or system package.
