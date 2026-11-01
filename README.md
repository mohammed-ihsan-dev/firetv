# SHAHU'S TV

A personalised, Apple TV-inspired home screen app for the Amazon Fire TV Stick (AFTSSS, Fire OS 7.7.1.7). It works alongside the Fire TV Home screen and does not replace it.

## v3.0 home screen (circular carousel)

- **Circular app carousel** with exactly 8 apps: YouTube · JioHotstar · Netflix · ZEE5 · Prime Video · FanCode · Settings · More Apps. ◀ ▶ browse (it wraps around), OK opens. The order is set by `"home"` in `config/wallpapers.json`.
- **Full-screen wallpaper per app**, crossfading in 0.3 s:
  - YouTube picks at random from 4 images and never repeats the previous one.
  - JioHotstar uses `JIO-HOTSTAR(1).jpg` once it is added; until then it shows `JIO-HOTSTAR`.
  - Netflix, Prime Video, FanCode and Settings use the supplied `Netflix.png`, `prime.jpg`, `fancode.jpg` and `settings.jpg`.
  - ZEE5 and More Apps keep their branded designs.
- **Settings tile → SHAHU'S TV Settings**: Fire TV Settings, Home Screen Mode (SHAHU'S TV / Amazon Classic), an optional experimental Home-button redirect, Open Amazon Home, and the Device Manager.
- **More Apps** opens the in-app panel with every other installed app.
- See **DEVELOPMENT-REPORT.md** for the details and **TOMORROW-TESTING.md** for the Fire TV test plan.

### Wallpaper files
- `tools/render_wallpapers.py` rasterises the Canva SVGs to same-name JPGs (headless Chrome, checked by rendering twice). It also renders the branded `bg-*.jpg` backgrounds and the circular icons.
- The original files are never modified.
- Per app, `config/wallpapers.json` supports:
  - `"wallpaper"`: a single image.
  - `"wallpapers": [...]`: random variants.
  - `"fallbackWallpaper"`: used if the main file is missing.
- The APK bundles only the wallpapers the config references.

```bash
tools/.venv/bin/python tools/render_wallpapers.py   # after changing an SVG or the branded designs
scripts/update.sh                                   # rebuild + install (keeps app data)
scripts/update.sh --wallpapers                      # push config + images only, no rebuild
scripts/restore.sh                                  # safe recovery to the Amazon interface
```

---

## Remote control

| Key | Action |
|---|---|
| ◀ ▶ | Turn the carousel (it wraps around). The wallpaper follows the selected app. |
| OK / Select / Enter | Open the selected app (Settings → SHAHU'S TV Settings, More Apps → the app panel) |
| ☰ Menu | Open the selected app's App Info page in Fire TV Settings |
| Back | In More Apps: back to the carousel. On the carousel: leave to Amazon Home. |
| Home | Amazon Home (Fire OS default). See Home Screen Mode for the optional experimental redirect. |

Up and Down do nothing on the carousel, so focus can never get lost. The last selected app is remembered.

**To open it:** go to Fire TV **Your Apps & Games** and select **SHAHU'S TV** (the "S" icon). To put it in the Fire TV front row, highlight it, press ☰ and choose **Move**.

---

## Project layout

```
FIRETV/
  app/                     Android app (Kotlin, plain Android Views, no third-party libraries)
    src/main/java/com/shahu/tv/
      MainActivity.kt      Home: carousel, More Apps panel, launching, clock/network, wallpaper crossfade
      CarouselView.kt      Flat wrap-around circular carousel (CarouselMath.kt: geometry + random wallpaper picker)
      SettingsActivity.kt  SHAHU'S TV Settings (Fire TV Settings, Home Screen Mode, Device Manager)
      DeviceManagerActivity.kt  Memory/storage/recent apps/app actions/device info (supported APIs only)
      HomeMode.kt, HomeButtonService.kt  Home Screen Mode + optional Home-button redirect
      TvUi.kt              Small builder for D-pad settings lists
      AppRepository.kt     Discovers installed/launchable apps; renders tile banners at tile size
      WallpaperConfig.kt   Reads wallpapers.json (built-in + external override, never crashes)
      WallpaperLoader.kt   Decodes wallpapers off the UI thread; 2-image cache; scrims baked in
      WifiIndicatorView.kt Small Canvas Wi-Fi / Ethernet glyph
  wallpapers/              Wallpaper images bundled into the APK (1920x1080 JPG)
  config/wallpapers.json   App → wallpaper mapping, order, names and descriptions
  scripts/                 build.sh, install.sh, update.sh, restore.sh
  build-output/SHAHUS-TV.apk
  signing/                 Release signing key (KEEP: needed to update the app in place)
  tools/generate_art.py    Generator for the original wallpaper art + app icon/banner
  device-inspection/       Phase A read-only inspection of the Fire TV
  test-evidence/           Screenshots taken from the real Fire TV during testing
  FINAL-REPORT.md          Device test report with measurements
```

---

## Build, install, update, restore

The scripts need Java 17 and the Android SDK, both installed via Homebrew: `openjdk@17` and `android-commandlinetools` (platform 34, build-tools 34). The Fire TV address defaults to `192.168.1.2:5555`. To use a different address, set `SHAHU_DEVICE=ip:port` before running a script.

```bash
scripts/build.sh                 # builds + signs build-output/SHAHUS-TV.apk
scripts/install.sh               # installs on the Fire TV (in place, keeps settings) and opens it
scripts/update.sh                # build + install in one step (--no-build: install the existing APK)
scripts/update.sh --wallpapers   # push config + wallpapers to the device only, no rebuild
scripts/restore.sh               # SAFE RECOVERY: Amazon Classic Home, redirect off, app stays installed
scripts/restore.sh --uninstall   # also remove SHAHU'S TV completely (asks to confirm)
```

### Rollback

`scripts/restore.sh` (safe default):
1. Removes **only** SHAHU'S TV's Home-button service from the enabled accessibility services. Any other services are left alone.
2. Sets Home Screen Mode to Amazon Classic and disables SHAHU'S TV's own HOME alias.
3. Checks, read-only, that `com.amazon.tv.launcher` is installed and enabled.
4. Presses Home and prints the default Home activity, which should be `com.amazon.tv.launcher/.ui.HomeActivity_vNext`.

`--uninstall` also removes `com.shahu.tv` and its `/sdcard/Android/data/com.shahu.tv` folder. No Amazon or system package is ever disabled or removed. If the Fire TV is off, every script stops with a clear message (exit code 2). You can also uninstall manually: **Settings → Applications → Manage Installed Applications → SHAHU'S TV → Uninstall**.

### Signing key

The first build creates `signing/shahus-tv.jks`. Back up the `signing/` folder. Without it, a new build can't be installed over the existing app; you'd have to uninstall first and would lose the remembered focus.

---

## Wallpaper configuration guide

### How a wallpaper is chosen
1. The focused app's package is looked up in `applications` in `wallpapers.json`.
2. If it has no entry, a wallpaper is picked from `fallbackWallpapers`, and the same app always gets the same one.
3. If that image is missing or can't be read, `defaultWallpaper` is used.
4. If even that fails, a plain dark background is shown. The app never crashes because of a wallpaper.

### `wallpapers.json` format

```json
{
  "defaultWallpaper": "wallpapers/default.jpg",
  "fallbackWallpapers": ["wallpapers/hero-original.jpg", "wallpapers/hero-neutral.jpg"],
  "pinned":  ["com.netflix.ninja", "com.amazon.firebat", "com.amazon.firetv.youtube"],
  "hidden":  ["com.amazon.firetv.troubleshooting"],
  "applications": {
    "com.netflix.ninja": {
      "name": "Netflix",
      "wallpaper": "wallpapers/batman.jpg",
      "description": "Series, films and originals."
    }
  }
}
```

| Field | Meaning |
|---|---|
| `applications.<package>.wallpaper` | Image for that app, as a relative path such as `wallpapers/x.jpg` |
| `applications.<package>.name` | Optional display name. Defaults to the app's own label. |
| `applications.<package>.description` | Optional one-line text under the name |
| `pinned` | Apps shown first, in this order, if installed. All other apps follow alphabetically. |
| `hidden` | Packages never shown in the row |
| `shahu.settings` | Special key for the Fire TV Settings tile at the end of the row |

Only installed apps appear. Listing an app that isn't installed is harmless: it is simply skipped, and its tile appears by itself if you install it later.

**Package names on this Fire TV:**

| App | Package |
|---|---|
| Netflix | `com.netflix.ninja` |
| Prime Video | `com.amazon.firebat` |
| YouTube | `com.amazon.firetv.youtube` |
| Disney+ Hotstar | `in.startv.hotstar` |
| Spotify | `com.spotify.tv.android` |
| Apple TV | `com.apple.atve.amazon.appletv` |
| JioTV+ | `com.jio.media.jiotvplus` |
| Airtel Xstream | `tv.airtel.xstream.tvapp` |
| SonyLIV | `com.onemainstream.sonyliv.android` |
| ZEE5 | `com.zee5.amazon` |
| VLC | `org.videolan.vlc` |

To list every package on the device, run `adb shell pm list packages -3`.

### Change wallpapers without rebuilding
1. Put your images (JPG/PNG/WebP) in `FIRETV/wallpapers/`, for example `wallpapers/my-spiderman.jpg`.
2. Edit `config/wallpapers.json` and point the app at the image, for example `"wallpaper": "wallpapers/my-spiderman.jpg"`.
3. Run `scripts/update.sh --wallpapers`.

The files are copied to `/sdcard/Android/data/com.shahu.tv/files/` on the Fire TV. SHAHU'S TV picks up changes the next time it comes to the foreground, and the script brings it to the foreground for you.

How the on-device copy behaves:
- Its entries override the built-in ones key by key, so a partial file that only changes Netflix works.
- If the JSON is invalid, it is ignored and the built-in configuration is used.
- Paths containing `..` or starting with `/` are rejected for safety.

To make your changes permanent inside the APK, run `scripts/update.sh`, which rebuilds the APK and bundles `wallpapers/` and `config/wallpapers.json`.

To undo on-device overrides, run `adb shell rm -r /sdcard/Android/data/com.shahu.tv/files/wallpapers.json /sdcard/Android/data/com.shahu.tv/files/wallpapers`.

### Image guidelines
- **1920×1080, 16:9.** Larger images are downscaled automatically. Other aspect ratios are center-cropped, never stretched.
- Put the main subject in the **upper-right two-thirds**. The app name sits at the lower left and the app row along the bottom (roughly the lowest 300 px).
- Don't darken the image yourself. Soft dark gradients are added automatically behind the header, the text and the app row.
- Keep each file under about 2 MB; around 300–600 KB JPEG is ideal.

### About the included artwork
The bundled wallpapers are **original artwork generated by `tools/generate_art.py`**: night skylines, skies, webs, moons and silhouetted caped heroes. They contain no copyrighted characters, costumes, logos or movie posters.

The names `spiderman.jpg`, `superman.jpg` and `batman.jpg` are just **theme slots**. To show the actual characters, replace those files with images you are entitled to use, such as wallpapers you purchased, official wallpapers released for personal use, or your own artwork, and run `scripts/update.sh --wallpapers`.

| Slot file | Original artwork | Used by |
|---|---|---|
| `batman.jpg` | Caped sentinel on a ledge, rainy night city, full moon | Netflix |
| `superman.jpg` | Caped hero flying into the sunrise above the clouds | Prime Video |
| `spiderman.jpg` | Hero swinging on a line over a red/indigo city, web | YouTube |
| `spiderman-alt.jpg` | Hero hanging upside down across the moon, blue night | Disney+ Hotstar |
| `superman-alt.jpg` | Hovering hero, red cape, dawn city | Apple TV |
| `batman-alt.jpg` | Glider cape across the moon | JioTV+ |
| `hero-original.jpg` | Original hero team on a ridge at sunset | Airtel Xstream, ZEE5 |
| `hero-dark.jpg` | Lone hero under a streetlight in the rain | Spotify, SonyLIV |
| `hero-neutral.jpg` | Distant rooftop hero over a grey city | VLC |
| `default.jpg` | SHAHU'S TV skyline with red, blue and gold searchlights | Settings, fallback |

To regenerate or tweak the art, run `tools/.venv/bin/python tools/generate_art.py`. If the venv is missing, create it with `python3 -m venv tools/.venv && tools/.venv/bin/pip install numpy pillow`.
# firetv
