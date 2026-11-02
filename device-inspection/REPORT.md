# SHAHU'S TV — Phase A Device Inspection Report

Date: 2026-11-01. Only read-only ADB commands were run. Nothing on the device was changed.
Raw outputs are in the numbered `.txt` files in this directory.

## Connection
- `192.168.1.2:5555` (Wi-Fi ADB), state `device`, model AFTSSS, product sheldonp.

## 1. Hardware and SDK
| Item | Value |
|---|---|
| Model / codename | AFTSSS / sheldonp |
| Fire OS | 7.7.1.7 (PS7717.5741N), security patch 2026-03-01 |
| Android / API | 9 / SDK 28 |
| CPU | 4× ARMv7 (32-bit only; ABI `armeabi-v7a`) |
| GPU | OpenGL ES 3.2 |
| App heap | 128 MB growth limit / 256 MB max |

## 2. Display
- Physical 1920×1080 at 320 dpi (xhdpi), which is **960×540 dp** (sw540dp, `television`, `-touch`, `dpad`).
- The UI is designed on a 960×540 dp canvas.

## 3. Streaming apps (verified launchable via LEANBACK_LAUNCHER)
| Service | Package | Launch activity |
|---|---|---|
| Netflix | `com.netflix.ninja` | `.MainActivity` |
| Prime Video | `com.amazon.firebat` (PVFTV-324.0104-L) | `com.amazon.pyrocore.IgnitionActivity` |
| YouTube | `com.amazon.firetv.youtube` | `dev.cobalt.app.MainActivity` |
| YouTube Music | **NOT INSTALLED** | — |
| Disney+ Hotstar | `in.startv.hotstar` | `com.hotstar.MainActivity` |
| Spotify | `com.spotify.tv.android` | `.SpotifyTVActivity` |
| Apple TV | `com.apple.atve.amazon.appletv` | `.MainActivity` |
| Amazon Music | `com.amazon.bueller.music` | `com.amazon.music.MainActivity` |
| Others | JioTV+, Airtel Xstream, SonyLIV, ZEE5, Sun NXT, discovery+, DAZN, FanCode, YuppTV, MX Player, VLC, YouTube Kids, miniTV, etc. | see `12-leanback-launchables.txt` |

31 leanback-launchable activities in total.

## 4. Default launcher
- HOME resolves to `com.amazon.tv.launcher/.ui.HomeActivity_vNext` (system priv-app, **priority 950**).
- Other HOME candidates: `com.amazon.firehomestarter/.HomeStarterActivity` (priority 1) and `com.amazon.tv.settings.v2/.system.FallbackHome` (-1000).
- No user-preferred HOME activity is set.
- The stock launcher holds signature permissions `com.amazon.tv.launcher.HOME_PRESSED` and `RECEIVE_HOME_LONGPRESSED_ACTION`. These suggest the Home key is routed to Amazon's launcher by Fire OS itself, not by standard HOME resolution.

## 5. RAM and storage
- RAM: 922 MB total, about **243 MB available**, with 384 MB zram swap heavily used. Memory is tight.
- `/data`: 4.9 GB, **1.0 GB free** (79% used).
- `/system`: 244 MB free (read-only).

## 6. Custom HOME activity
- **Installing** an app that declares a HOME intent filter: possible. It is a normal APK install.
- **Becoming the Home-button target**: unlikely without disruptive changes.
  - A third-party app's HOME filter can't outrank the system launcher's priority 950.
  - Fire OS 7 intercepts the Home key for `com.amazon.tv.launcher`.
  - This is to be **tested in Phase H**, not assumed. Safe fallbacks: launch from the app row, auto-start at boot via `BOOT_COMPLETED`, or a long-press shortcut.
  - Disabling the stock launcher is excluded by the safety rules.

## 7. Wi-Fi
- `dumpsys wifi` reports Wi-Fi enabled, connected, RSSI −53 dBm, 2.4 GHz.
- The app can read this itself through `ConnectivityManager` and `WifiManager.getConnectionInfo().getRssi()`. Only signal level is needed; reading the SSID would require location permission.

## Host toolchain (Mac)
- No JDK, no Android SDK, no Gradle. These need to be installed (host-only, not the device).
