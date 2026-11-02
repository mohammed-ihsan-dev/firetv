#!/usr/bin/env python3
"""
Render SHAHU'S TV home-screen backgrounds (1920x1080 JPG) into wallpapers/.

 1. Rasterises the user's own SVG wallpapers (Android cannot draw these Canva SVGs: embedded PNGs,
    masks, filters) with headless Chrome, keeping the original file names:
        "TV YOUTUBE 1.svg" -> "TV YOUTUBE 1.jpg", YOUTUBE-MAIN.svg -> YOUTUBE-MAIN.jpg, ...
 2. Renders the 8 circular carousel icons (app/src/main/res/drawable-nodpi/circle_*.png).
 3. Composes the branded backgrounds (Netflix, ZEE5, Prime Video, FanCode, Settings, More Apps)
    locally from HTML/CSS/SVG. All glows/blurs are baked offline -> zero blur cost on the Fire TV.

Usage:  tools/.venv/bin/python tools/render_wallpapers.py
Needs Google Chrome (or Brave) and Pillow + numpy (tools/.venv).
"""
import os
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
WALL = ROOT / "wallpapers"
W, H = 1920, 1080
CHROME = next((p for p in [
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser",
] if os.path.exists(p)), None)

USER_SVGS = ["TV YOUTUBE 1", "YOUTUBE-MAIN", "YT-WALLPAPER", "JIO-HOTSTAR"]

# --------------------------------------------------------------------------------------------- designs
# Hero content sits in the upper-centre (y ~120-560): the app grid covers the lower half on the TV.
BASE_CSS = """
html,body{margin:0;width:1920px;height:1080px;overflow:hidden;background:#000}
.s{position:absolute;inset:0;overflow:hidden}
.g{position:absolute;border-radius:50%}
.hero{position:absolute;left:0;right:0;top:330px;transform:translateY(-50%);text-align:center}
.cap{font:500 30px 'Avenir Next';letter-spacing:12px;color:rgba(255,255,255,.55);margin-top:26px;text-transform:uppercase}
"""

DESIGNS = {
"netflix": """
<div class=s style="background:radial-gradient(ellipse 70% 60% at 50% 32%,#2a0306 0%,#0b0001 55%,#000 100%)"></div>
<div class=g style="left:560px;top:20px;width:800px;height:620px;background:rgba(229,9,20,.30);filter:blur(120px)"></div>
<div class=g style="left:-200px;top:700px;width:900px;height:600px;background:rgba(150,0,10,.28);filter:blur(140px)"></div>
<div class=g style="left:1300px;top:-200px;width:800px;height:500px;background:rgba(120,0,8,.25);filter:blur(140px)"></div>
<!-- ribbon light streaks -->
<div style="position:absolute;left:820px;top:-80px;width:60px;height:900px;background:linear-gradient(#ff2a35,transparent);opacity:.18;filter:blur(18px);transform:rotate(14deg)"></div>
<div style="position:absolute;left:1040px;top:-80px;width:60px;height:900px;background:linear-gradient(#ff2a35,transparent);opacity:.18;filter:blur(18px);transform:rotate(-14deg)"></div>
<div style="position:absolute;left:930px;top:-120px;width:70px;height:1000px;background:linear-gradient(#ff4650,transparent);opacity:.12;filter:blur(24px)"></div>
<div class=hero>
  <div style="font:800 230px 'Futura Condensed ExtraBold','Futura';font-stretch:condensed;color:#E50914;letter-spacing:6px;
       text-shadow:0 0 70px rgba(229,9,20,.55),0 10px 40px rgba(0,0,0,.8);transform:scaleY(1.08)">NETFLIX</div>
</div>
""",

"zee5": """
<div class=s style="background:radial-gradient(ellipse 80% 70% at 50% 35%,#3b0a5e 0%,#1c0333 50%,#0b0114 100%)"></div>
<div class=g style="left:-180px;top:-160px;width:760px;height:620px;background:rgba(225,0,122,.38);filter:blur(140px)"></div>
<div class=g style="left:1380px;top:-120px;width:700px;height:560px;background:rgba(0,179,227,.30);filter:blur(140px)"></div>
<div class=g style="left:1400px;top:700px;width:700px;height:520px;background:rgba(255,138,0,.24);filter:blur(150px)"></div>
<div class=g style="left:-120px;top:720px;width:700px;height:520px;background:rgba(130,48,198,.40);filter:blur(150px)"></div>
<div class=g style="left:700px;top:120px;width:520px;height:420px;background:rgba(160,70,230,.35);filter:blur(110px)"></div>
<!-- elegant multicolour arcs -->
<svg style="position:absolute;left:0;top:0" width="1920" height="1080">
  <defs><linearGradient id="mc" x1="0" x2="1"><stop offset="0" stop-color="#E1007A"/><stop offset=".35" stop-color="#8230C6"/>
  <stop offset=".7" stop-color="#00B3E3"/><stop offset="1" stop-color="#FF8A00"/></linearGradient></defs>
  <ellipse cx="960" cy="330" rx="430" ry="190" fill="none" stroke="url(#mc)" stroke-width="3" opacity=".55"/>
  <ellipse cx="960" cy="330" rx="520" ry="240" fill="none" stroke="url(#mc)" stroke-width="1.5" opacity=".28"/>
</svg>
<div class=hero>
  <div style="display:inline-block;padding:18px 70px;border-radius:44px;background:linear-gradient(135deg,#9b2fd6,#5a0f99);
       box-shadow:0 0 90px rgba(155,47,214,.55),inset 0 2px 0 rgba(255,255,255,.25);
       font:700 190px 'Futura';color:#fff;letter-spacing:4px;line-height:1">ZEE5</div>
</div>
""",

"prime": """
<div class=s style="background:radial-gradient(ellipse 80% 70% at 50% 30%,#0c2a4f 0%,#06152b 45%,#01050d 100%)"></div>
<div class=g style="left:560px;top:40px;width:800px;height:560px;background:rgba(26,152,255,.30);filter:blur(130px)"></div>
<div class=g style="left:-260px;top:620px;width:900px;height:640px;background:rgba(0,90,180,.25);filter:blur(150px)"></div>
<div class=g style="left:1400px;top:-160px;width:760px;height:560px;background:rgba(0,168,225,.20);filter:blur(140px)"></div>
<svg style="position:absolute;left:0;top:0;opacity:.10" width="1920" height="1080">
  <g stroke="#7cc4ff" stroke-width="1">
    <line x1="0" y1="900" x2="1920" y2="300"/><line x1="0" y1="980" x2="1920" y2="380"/><line x1="0" y1="1060" x2="1920" y2="460"/>
  </g>
</svg>
<div class=hero>
  <div style="font:700 170px 'Avenir Next';color:#fff;letter-spacing:-2px;line-height:1;text-shadow:0 0 60px rgba(26,152,255,.45)">prime video</div>
  <svg width="560" height="90" viewBox="0 0 560 90" style="display:block;margin:6px auto 0;filter:drop-shadow(0 0 18px rgba(26,152,255,.7))">
    <path d="M40 18 Q280 120 500 26" fill="none" stroke="#1A98FF" stroke-width="16" stroke-linecap="round"/>
    <path d="M462 10 L520 22 L486 70" fill="none" stroke="#1A98FF" stroke-width="16" stroke-linecap="round" stroke-linejoin="round"/>
  </svg>
</div>
""",

"fancode": """
<div class=s style="background:radial-gradient(ellipse 90% 70% at 50% 40%,#13161f 0%,#090b11 55%,#030407 100%)"></div>
<!-- stadium floodlights -->
<div style="position:absolute;left:-200px;top:-300px;width:900px;height:1400px;background:linear-gradient(rgba(255,255,255,.22),transparent 70%);
     filter:blur(60px);transform:rotate(-28deg);transform-origin:top left"></div>
<div style="position:absolute;left:1220px;top:-300px;width:900px;height:1400px;background:linear-gradient(rgba(255,255,255,.22),transparent 70%);
     filter:blur(60px);transform:rotate(28deg);transform-origin:top right"></div>
<div class=g style="left:620px;top:120px;width:680px;height:420px;background:rgba(255,90,20,.30);filter:blur(120px)"></div>
<div class=g style="left:-100px;top:760px;width:2100px;height:500px;background:rgba(20,120,60,.22);filter:blur(120px)"></div>
<svg style="position:absolute;left:0;top:0;opacity:.13" width="1920" height="1080">
  <g fill="none" stroke="#ffffff" stroke-width="3">
    <ellipse cx="960" cy="1010" rx="420" ry="120"/><line x1="0" y1="1010" x2="1920" y2="1010"/>
    <rect x="160" y="720" width="1600" height="560"/>
  </g>
</svg>
<div class=hero>
  <div style="font:800 180px 'Avenir Next';letter-spacing:-3px;line-height:1;color:#fff;text-shadow:0 0 60px rgba(255,90,20,.35)">Fan<span style="color:#FF5A1F">Code</span></div>
  <div class=cap>Live&nbsp;&nbsp;Sports</div>
</div>
""",

"settings": """
<div class=s style="background:radial-gradient(ellipse 75% 70% at 50% 32%,#2a2c31 0%,#18191d 50%,#0c0d0f 100%)"></div>
<div class=g style="left:660px;top:60px;width:600px;height:520px;background:rgba(220,225,235,.13);filter:blur(110px)"></div>
<svg style="position:absolute;left:0;top:0;opacity:.18" width="1920" height="1080">
  <g fill="none" stroke="#c9ced6">
    <circle cx="960" cy="300" r="260" stroke-width="1.5"/><circle cx="960" cy="300" r="340" stroke-width="1"/>
    <circle cx="960" cy="300" r="440" stroke-width=".8"/>
  </g>
</svg>
<div class=hero style="top:330px">
  <svg width="300" height="300" viewBox="0 0 24 24" style="display:block;margin:0 auto;filter:drop-shadow(0 12px 30px rgba(0,0,0,.6)) drop-shadow(0 0 30px rgba(255,255,255,.18))">
    <defs><linearGradient id="sv" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#fbfbfc"/><stop offset=".55" stop-color="#c3c7ce"/><stop offset="1" stop-color="#8a8f98"/></linearGradient></defs>
    <path fill="url(#sv)" d="M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z"/>
  </svg>
</div>
""",

"more-apps": """
<div class=s style="background:radial-gradient(ellipse 80% 70% at 50% 32%,#23252b 0%,#131418 50%,#08090b 100%)"></div>
<div class=g style="left:640px;top:60px;width:640px;height:520px;background:rgba(140,150,190,.16);filter:blur(120px)"></div>
<div class=g style="left:-200px;top:-200px;width:700px;height:600px;background:rgba(90,110,170,.12);filter:blur(140px)"></div>
<div class=g style="left:1450px;top:640px;width:700px;height:600px;background:rgba(150,110,170,.10);filter:blur(140px)"></div>
<div class=hero style="top:320px">
  <div style="display:inline-grid;grid-template-columns:repeat(3,96px);gap:24px;filter:drop-shadow(0 18px 30px rgba(0,0,0,.55))">
    """ + "".join(
        f'<div style="width:96px;height:96px;border-radius:26px;background:linear-gradient(160deg,{a},{b});'
        f'border:1.5px solid rgba(255,255,255,.28);box-shadow:inset 0 2px 0 rgba(255,255,255,.35)"></div>'
        for a, b in [("#ff6b6b", "#c0392b"), ("#5ac8fa", "#1e6fd9"), ("#ffd166", "#f39c12"),
                     ("#a29bfe", "#6c5ce7"), ("#f2f3f5", "#a7acb5"), ("#55efc4", "#00a884"),
                     ("#fd79a8", "#d63384"), ("#74b9ff", "#3867d6"), ("#ffeaa7", "#e1a23b")]) + """
  </div>
</div>
""",

# Plain backdrop behind the open "More Apps" panel (tiles cover most of the screen).
"library": """
<div class=s style="background:radial-gradient(ellipse 90% 80% at 50% 20%,#25272d 0%,#141519 50%,#08090b 100%)"></div>
<div class=g style="left:560px;top:-220px;width:800px;height:520px;background:rgba(140,150,190,.14);filter:blur(130px)"></div>
<div class=g style="left:-260px;top:640px;width:800px;height:620px;background:rgba(90,110,170,.10);filter:blur(150px)"></div>
<div class=g style="left:1420px;top:560px;width:760px;height:620px;background:rgba(150,110,170,.09);filter:blur(150px)"></div>
""",
}


# --------------------------------------------------------------------------------------------- circular icons
# 8 circular carousel icons, 320x320 PNG with transparency -> app/src/main/res/drawable-nodpi/circle_<name>.png
ICON_PX = 320
ICON_DIR = ROOT / "app/src/main/res/drawable-nodpi"
GEAR = ("M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61"
        "l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41"
        " -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22"
        "L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41"
        " -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41"
        "h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32"
        "c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z")
STAR = ('<svg width="200" height="200" viewBox="-50 -50 100 100"><g transform="rotate(-12)">'
        '<path fill="#fff" d="M0,-47 L6,-6 L47,0 L6,6 L0,47 L-6,6 L-47,0 L-6,-6Z"/>'
        '<path fill="#fff" opacity=".9" transform="rotate(45)" d="M0,-26 L5,-5 L26,0 L5,5 L0,26 L-5,5 L-26,0 L-5,-5Z"/></g></svg>')
SQUARES = "".join(f'<div style="width:46px;height:46px;border-radius:13px;background:linear-gradient(160deg,{a},{b})"></div>'
                  for a, b in [("#ff6b6b", "#c0392b"), ("#5ac8fa", "#1e6fd9"), ("#ffd166", "#f39c12"),
                               ("#a29bfe", "#6c5ce7"), ("#f2f3f5", "#a7acb5"), ("#55efc4", "#00a884"),
                               ("#fd79a8", "#d63384"), ("#74b9ff", "#3867d6"), ("#ffeaa7", "#e1a23b")])
ICONS = {
    "youtube": ("radial-gradient(circle at 50% 30%,#ffffff,#e9e9ec)",
                '<div style="width:150px;height:106px;border-radius:30px;background:#FF0000;display:flex;align-items:center;justify-content:center">'
                '<div style="width:0;height:0;border-left:44px solid #fff;border-top:27px solid transparent;border-bottom:27px solid transparent;margin-left:10px"></div></div>'),
    "hotstar": ("linear-gradient(135deg,#0b5cff 0%,#7b2ff7 52%,#ff0a78 100%)", STAR),
    "netflix": ("radial-gradient(circle at 50% 35%,#2b0306,#050000 75%)",
                '<div style="font:800 214px \'Futura Condensed ExtraBold\',\'Futura\';color:#E50914;line-height:1;margin-top:14px;'
                'text-shadow:0 0 30px rgba(229,9,20,.45)">N</div>'),
    "zee5": ("linear-gradient(150deg,#9b2fd6,#4b0b85)",
             '<div style="font:700 78px Futura;color:#fff;letter-spacing:1px">ZEE5</div>'),
    "prime": ("radial-gradient(circle at 50% 30%,#123a6b,#04101f 80%)",
              '<div style="text-align:center"><div style="font:700 70px \'Avenir Next\';color:#fff;line-height:.95;letter-spacing:-1px">prime<br>video</div>'
              '<svg width="170" height="40" viewBox="0 0 170 40" style="display:block;margin:2px auto 0"><path d="M12 8 Q85 44 150 12" fill="none" stroke="#1A98FF" stroke-width="8" stroke-linecap="round"/>'
              '<path d="M136 4 L156 10 L146 28" fill="none" stroke="#1A98FF" stroke-width="8" stroke-linecap="round" stroke-linejoin="round"/></svg></div>'),
    "fancode": ("linear-gradient(150deg,#ff7a1a,#e64100)",
                '<div style="font:italic 900 74px \'Avenir Next\';color:#fff;line-height:.92;text-align:center;letter-spacing:-1px">FAN<br>CODE</div>'),
    "settings": ("radial-gradient(circle at 50% 30%,#4a4d55,#1b1c20 80%)",
                 f'<svg width="170" height="170" viewBox="0 0 24 24"><defs><linearGradient id="s" x1="0" y1="0" x2="0" y2="1">'
                 f'<stop offset="0" stop-color="#fdfdfd"/><stop offset="1" stop-color="#9da2ab"/></linearGradient></defs><path fill="url(#s)" d="{GEAR}"/></svg>'),
    "more": ("radial-gradient(circle at 50% 30%,#3a3c44,#141519 80%)",
             f'<div style="display:grid;grid-template-columns:repeat(3,46px);gap:13px">{SQUARES}</div>'),
}


# Carousel icons made from the user's own logo files instead of the generated designs (never overwritten).
USER_ICONS = {"zee5": WALL / "ZEE5-logo.png"}
USER_ICON_ZOOM = {"zee5": 0.84}   # fraction of the (square) logo kept, centred, so the mark fills the circle


def user_icon(name: str, src: Path, out: Path):
    """Square logo -> 320 px circular icon (anti-aliased mask, same hairline rim + top sheen as the others)."""
    ss = 4
    size = ICON_PX * ss
    im = Image.open(src).convert("RGB")
    w, h = im.size
    side = int(min(w, h) * USER_ICON_ZOOM.get(name, 1.0))
    im = im.crop(((w - side) // 2, (h - side) // 2, (w + side) // 2, (h + side) // 2)).resize((size, size), Image.LANCZOS)
    from PIL import ImageDraw
    inset = 3 * ss
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((inset, inset, size - inset, size - inset), fill=255)
    # top sheen (white 20% -> 0 over the upper half) and hairline rim (white 16%)
    sheen = Image.new("RGBA", (size, size), (255, 255, 255, 0))
    grad = np.zeros((size, size), np.uint8)
    half = size // 2
    grad[:half, :] = (np.linspace(51, 0, half)[:, None]).astype(np.uint8)
    sheen.putalpha(Image.fromarray(grad))
    base = im.convert("RGBA")
    base.alpha_composite(sheen)
    rim = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ImageDraw.Draw(rim).ellipse((inset, inset, size - inset, size - inset), outline=(255, 255, 255, 41), width=2 * ss)
    base.alpha_composite(rim)
    base.putalpha(mask)
    base.resize((ICON_PX, ICON_PX), Image.LANCZOS).save(out, optimize=True)
    print(f"  {out.relative_to(ROOT)}  (from {src.name})")


def render_icons(tmp: Path):
    ICON_DIR.mkdir(parents=True, exist_ok=True)
    cells = []
    for i, (name, (bg, inner)) in enumerate(ICONS.items()):
        cells.append(
            f'<div style="position:absolute;left:{i * ICON_PX}px;top:0;width:{ICON_PX}px;height:{ICON_PX}px">'
            f'<div style="position:absolute;inset:3px;border-radius:50%;background:{bg};overflow:hidden;display:flex;align-items:center;justify-content:center;'
            f'box-shadow:inset 0 0 0 2px rgba(255,255,255,.16)">{inner}'
            f'<div style="position:absolute;inset:0;border-radius:50%;background:linear-gradient(180deg,rgba(255,255,255,.20),rgba(255,255,255,0) 48%)"></div>'
            f'</div></div>')
    html = tmp / "icons.html"
    html.write_text('<html><head><style>html,body{margin:0;background:transparent}</style></head><body>' + "".join(cells) + "</body></html>")
    png = tmp / "icons.png"
    subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1",
                    f"--window-size={ICON_PX * len(ICONS)},{ICON_PX}", "--default-background-color=00000000",
                    "--virtual-time-budget=4000", f"--screenshot={png}", html.as_uri()],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    sheet = Image.open(png).convert("RGBA")
    for i, name in enumerate(ICONS):
        out = ICON_DIR / f"circle_{name}.png"
        if name in USER_ICONS and USER_ICONS[name].exists():
            user_icon(name, USER_ICONS[name], out)
            continue
        sheet.crop((i * ICON_PX, 0, (i + 1) * ICON_PX, ICON_PX)).save(out, optimize=True)
        print(f"  {out.relative_to(ROOT)}  {out.stat().st_size // 1024} KB")


# --------------------------------------------------------------------------------------------- helpers
def chrome_shot(html_path: Path, png_path: Path):
    if not CHROME:
        sys.exit("Google Chrome / Brave not found — needed to render SVG/HTML")
    subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1",
                    f"--window-size={W},{H}", "--virtual-time-budget=8000", "--allow-file-access-from-files",
                    f"--screenshot={png_path}", html_path.as_uri()],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def stable_shot(html_path: Path, stem: Path, attempts: int = 5) -> Path:
    """
    Headless Chrome occasionally composites these Canva SVGs (masks + embedded PNGs) with layers in the
    wrong place. Render until two consecutive renders agree (mean pixel difference < 0.5) and use that.
    """
    prev = None
    for i in range(attempts):
        png = stem.with_name(f"{stem.name}-{i}.png")
        chrome_shot(html_path, png)
        cur = np.asarray(Image.open(png).convert("RGB"), dtype=np.int16)
        if prev is not None and np.abs(cur - prev).mean() < 0.5:
            return png
        prev = cur
    sys.exit(f"Unstable render for {html_path.name}: renders never agreed after {attempts} attempts")


def save_jpg(png_path: Path, out: Path, dither: bool):
    img = Image.open(png_path).convert("RGB")
    if img.size != (W, H):
        img = img.resize((W, H), Image.LANCZOS)
    if dither:  # break up 8-bit banding in the large dark gradients
        a = np.asarray(img).astype(np.float32)
        a += np.random.default_rng(7).normal(0, 1.1, a.shape[:2])[..., None]
        img = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))
    img.save(out, "JPEG", quality=88, optimize=True, progressive=False, subsampling=0 if dither else 2)
    print(f"  {out.relative_to(ROOT)}  {out.stat().st_size // 1024} KB")


def main():
    with tempfile.TemporaryDirectory() as td:
        tmp = Path(td)
        print("User SVG wallpapers:")
        for name in USER_SVGS:
            svg = WALL / f"{name}.svg"
            if not svg.exists():
                sys.exit(f"Missing {svg}")
            html = tmp / f"{name}.html"
            html.write_text(f'<html><body style="margin:0;overflow:hidden;background:#000">'
                            f'<img src="{svg.as_uri()}" style="width:{W}px;height:{H}px;display:block"></body></html>')
            png = stable_shot(html, tmp / name)
            save_jpg(png, WALL / f"{name}.jpg", dither=False)

        print("Circular carousel icons:")
        render_icons(tmp)

        print("Branded backgrounds:")
        for name, body in DESIGNS.items():
            html = tmp / f"bg-{name}.html"
            html.write_text(f"<html><head><style>{BASE_CSS}</style></head><body>{body}</body></html>")
            png = tmp / f"bg-{name}.png"
            chrome_shot(html, png)
            save_jpg(png, WALL / f"bg-{name}.jpg", dither=True)


if __name__ == "__main__":
    main()
