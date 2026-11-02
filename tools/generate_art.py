#!/usr/bin/env python3
"""
SHAHU'S TV — original superhero-themed wallpaper generator.

Every image produced here is ORIGINAL procedural artwork (skylines, skies,
silhouetted caped figures, light). It contains no copyrighted characters,
costumes, logos, emblems or movie artwork. The file names (spiderman.jpg, ...)
are just "theme slots": replace any of them with your own licensed images.

Usage:  tools/.venv/bin/python tools/generate_art.py
Output: wallpapers/*.jpg (1920x1080), app/src/main/res/... launcher icon + banner
"""
import math
import os
import random

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

W, H = 1920, 1080
SS = 2  # supersampling for silhouettes
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "wallpapers")
FONT = "/System/Library/Fonts/Avenir Next.ttc"

YY, XX = np.mgrid[0:H, 0:W].astype(np.float32)


# ----------------------------------------------------------------- basics
def rgb(h):
    h = h.lstrip("#")
    return np.array([int(h[i:i + 2], 16) / 255.0 for i in (0, 2, 4)], np.float32)


def vgrad(stops):
    """stops: list of (y_fraction, '#hex')."""
    ys = np.array([s[0] for s in stops], np.float32) * (H - 1)
    cols = np.array([rgb(s[1]) for s in stops])
    out = np.zeros((H, W, 3), np.float32)
    col = np.stack([np.interp(np.arange(H), ys, cols[:, c]) for c in range(3)], -1)
    out[:] = col[:, None, :]
    return out


def glow(img, cx, cy, r, color, strength=1.0, power=2.0, sx=1.0, sy=1.0):
    d = np.sqrt(((XX - cx) / sx) ** 2 + ((YY - cy) / sy) ** 2) / r
    g = np.clip(1 - d, 0, 1) ** power * strength
    img += g[..., None] * rgb(color)


def over(img, color, a):
    c = rgb(color) if isinstance(color, str) else color
    a = np.clip(a, 0, 1)[..., None]
    img[:] = img * (1 - a) + c * a


def noise(scale, octaves=4, seed=0, persistence=0.5):
    rng = np.random.default_rng(seed)
    total = np.zeros((H, W), np.float32)
    amp, norm = 1.0, 0.0
    for o in range(octaves):
        sw = max(2, int(W / scale * 2 ** o))
        sh = max(2, int(H / scale * 2 ** o))
        small = Image.fromarray(rng.random((sh, sw)).astype(np.float32), "F")
        total += np.asarray(small.resize((W, H), Image.BICUBIC)) * amp
        norm += amp
        amp *= persistence
    return total / norm


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def blur(a, r):
    im = Image.fromarray((np.clip(a, 0, 1) * 255 + 0.5).astype(np.uint8))
    return np.asarray(im.filter(ImageFilter.GaussianBlur(r)), np.float32) / 255.0


# ----------------------------------------------------------------- vector masks
class Mask:
    def __init__(self):
        self.im = Image.new("L", (W * SS, H * SS), 0)
        self.d = ImageDraw.Draw(self.im)

    def poly(self, pts, fill=255):
        self.d.polygon([(x * SS, y * SS) for x, y in pts], fill=fill)

    def circle(self, cx, cy, r, fill=255):
        self.d.ellipse([(cx - r) * SS, (cy - r) * SS, (cx + r) * SS, (cy + r) * SS], fill=fill)

    def rect(self, x0, y0, x1, y1, fill=255):
        self.d.rectangle([x0 * SS, y0 * SS, x1 * SS, y1 * SS], fill=fill)

    def line(self, pts, w, fill=255):
        self.d.line([(x * SS, y * SS) for x, y in pts], fill=fill, width=max(1, int(w * SS)), joint="curve")

    def limb(self, p0, p1, r0, r1):
        n = max(2, int(math.dist(p0, p1) / 1.5))
        for i in range(n + 1):
            t = i / n
            self.circle(p0[0] + (p1[0] - p0[0]) * t, p0[1] + (p1[1] - p0[1]) * t, r0 + (r1 - r0) * t)

    def arr(self):
        return np.asarray(self.im.resize((W, H), Image.LANCZOS), np.float32) / 255.0


def catmull(pts, closed=True, steps=10):
    out = []
    n = len(pts)
    rng = range(n) if closed else range(n - 1)
    for i in rng:
        p0 = pts[(i - 1) % n] if closed or i > 0 else pts[i]
        p1, p2 = pts[i], pts[(i + 1) % n]
        p3 = pts[(i + 2) % n] if closed or i + 2 < n else p2
        for s in range(steps):
            t = s / steps
            t2, t3 = t * t, t * t * t
            out.append(tuple(0.5 * ((2 * p1[k]) + (-p0[k] + p2[k]) * t + (2 * p0[k] - 5 * p1[k] + 4 * p2[k] - p3[k]) * t2
                                    + (-p0[k] + 3 * p1[k] - 3 * p2[k] + p3[k]) * t3) for k in (0, 1)))
    if not closed:
        out.append(pts[-1])
    return out


# ----------------------------------------------------------------- figures
STAND = dict(head=(0, -7.45), neck=(0, -6.85), shL=(-0.95, -6.45), shR=(0.95, -6.45),
             elL=(-1.15, -5.0), elR=(1.15, -5.0), haL=(-1.2, -3.7), haR=(1.2, -3.7),
             hipL=(-0.5, -4.0), hipR=(0.5, -4.0), knL=(-0.62, -2.0), knR=(0.62, -2.0),
             ftL=(-0.75, -0.1), ftR=(0.75, -0.1))


def pose(**over_):
    p = dict(STAND)
    p.update(over_)
    return p


def xform(pts, x, y, s, ang):
    ca, sa = math.cos(math.radians(ang)), math.sin(math.radians(ang))
    return [(x + (px * ca - py * sa) * s, y + (px * sa + py * ca) * s) for px, py in pts]


def draw_figure(m, J, x, y, s, ang=0.0, build=1.0, cape=None, cape_mask=None):
    """Draws an original generic heroic silhouette. J = joints in head units."""
    P = {k: xform([v], x, y, s, ang)[0] for k, v in J.items()}
    b = build
    # torso with a heroic V-taper
    def lerp(a, c, t):
        return (a[0] + (c[0] - a[0]) * t, a[1] + (c[1] - a[1]) * t)
    wl = lerp(P["shL"], P["hipL"], 0.72)
    wr = lerp(P["shR"], P["hipR"], 0.72)
    cx = ((wl[0] + wr[0]) / 2, (wl[1] + wr[1]) / 2)
    wl, wr = lerp(wl, cx, 0.22), lerp(wr, cx, 0.22)
    m.poly([P["shL"], P["shR"], wr, P["hipR"], P["hipL"], wl])
    m.limb(P["neck"], P["head"], 0.27 * s * b, 0.24 * s * b)
    m.circle(*P["head"], 0.43 * s)
    jaw = lerp(P["head"], P["neck"], 0.35)
    m.circle(*jaw, 0.36 * s)
    for side in "LR":
        m.circle(*P["sh" + side], 0.42 * s * b)
        m.limb(P["sh" + side], P["el" + side], 0.37 * s * b, 0.29 * s * b)
        m.limb(P["el" + side], P["ha" + side], 0.29 * s * b, 0.2 * s * b)
        m.circle(*P["ha" + side], 0.23 * s * b)
        m.circle(*P["hip" + side], 0.5 * s * b)
        m.limb(P["hip" + side], P["kn" + side], 0.52 * s * b, 0.34 * s * b)
        m.limb(P["kn" + side], P["ft" + side], 0.36 * s * b, 0.2 * s * b)
        m.circle(*P["ft" + side], 0.24 * s * b)
    if cape:
        pts = catmull(xform(cape, x, y, s, ang), True, 8)
        (cape_mask or m).poly(pts)
    return P


def rim(mask, dx, dy, width=1.5):
    shifted = np.roll(np.roll(mask, int(dy), 0), int(dx), 1)
    r = np.clip(mask - shifted, 0, 1)
    return blur(r, width)


def silhouette(img, mask, body="#06070b", rim_col=None, dx=0, dy=0, rim_strength=1.0):
    over(img, body, mask)
    if rim_col:
        img += (rim(mask, dx, dy) * mask * rim_strength * 0.75)[..., None] * rgb(rim_col)


# ----------------------------------------------------------------- environment
def skyline(seed, base, hmin, hmax, wmin, wmax, color, haze_col=None, haze=0.0,
            windows=0.0, win_cols=("#ffd9a0", "#cfe3ff"), spires=0.15, img=None, x0=-60, x1=W + 60):
    rng = random.Random(seed)
    m = Mask()
    win = Image.new("RGB", (W, H), (0, 0, 0))
    wd = ImageDraw.Draw(win)
    x = x0
    while x < x1:
        w = rng.uniform(wmin, wmax)
        h = rng.uniform(hmin, hmax) * (1.4 if rng.random() < 0.12 else 1.0)
        top = base - h
        m.rect(x, top, x + w, H)
        if rng.random() < 0.45:  # setback
            sw = w * rng.uniform(0.45, 0.75)
            sx = x + (w - sw) / 2
            sh = h * rng.uniform(0.08, 0.2)
            m.rect(sx, top - sh, sx + sw, top)
            top -= sh
            if rng.random() < 0.5:
                tw = sw * 0.5
                m.rect(sx + (sw - tw) / 2, top - sh * 0.6, sx + (sw + tw) / 2, top)
                top -= sh * 0.6
        if rng.random() < spires:
            cxm = x + w / 2
            m.poly([(cxm - w * 0.12, top), (cxm + w * 0.12, top), (cxm, top - h * 0.25)])
        if rng.random() < 0.25:
            m.line([(x + w * 0.5, top), (x + w * 0.5, top - rng.uniform(15, 45))], 1.6)
        if windows > 0:
            cw, chh = rng.choice([(3, 5), (4, 6), (2, 4)])
            gx, gy = cw + rng.randint(3, 6), chh + rng.randint(4, 8)
            yy = int(top + 10)
            while yy < H:
                xx = int(x + 4)
                while xx < x + w - 5:
                    if rng.random() < windows:
                        c = rng.choice(win_cols)
                        k = rng.uniform(0.25, 1.0)
                        cc = tuple(int(int(c[i:i + 2], 16) * k) for i in (1, 3, 5))
                        wd.rectangle([xx, yy, xx + cw, yy + chh], fill=cc)
                    xx += gx
                yy += gy
        x += w + rng.uniform(-6, 10)
    a = m.arr()
    col = np.ones((H, W, 3), np.float32) * rgb(color)
    if haze_col:
        hz = np.clip((YY - (base - hmax)) / (hmax + 1), 0, 1)
        col = col * (1 - haze * (1 - hz))[..., None] + rgb(haze_col) * (haze * (1 - hz))[..., None]
    img[:] = img * (1 - a[..., None]) + col * a[..., None]
    if windows > 0:
        img += np.asarray(win, np.float32) / 255.0 * a[..., None] * 0.9
    return a


def clouds(img, seed, y0, y1, color, density=0.5, scale=380, alpha=0.8, light=None, soft=0.25):
    n = noise(scale, 5, seed)
    band = smoothstep(y0 - 120, y0 + 60, YY) * (1 - smoothstep(y1 - 60, y1 + 120, YY))
    c = smoothstep(1 - density - soft, 1 - density + soft, n) * band
    col = np.ones((H, W, 3), np.float32) * rgb(color)
    if light is not None:
        lx, ly, lc, lr = light
        d = np.clip(1 - np.sqrt((XX - lx) ** 2 + (YY - ly) ** 2) / lr, 0, 1) ** 1.6
        col += d[..., None] * rgb(lc) * (n[..., None] * 1.4)
    a = (c * alpha)[..., None]
    img[:] = img * (1 - a) + col * a


def stars(img, seed, n, ymax, bright=0.8):
    rng = np.random.default_rng(seed)
    xs = rng.integers(0, W, n)
    ys = (rng.random(n) ** 1.6 * ymax).astype(int)
    b = rng.random(n) ** 3 * bright
    for x, y, v in zip(xs, ys, b):
        img[y, x] += v
        if v > 0.5 and 0 < x < W - 1 and 0 < y < H - 1:
            img[y, x - 1] += v * 0.3; img[y, x + 1] += v * 0.3
            img[y - 1, x] += v * 0.3; img[y + 1, x] += v * 0.3


def moon(img, cx, cy, r, color="#f3efe4", glow_col="#9fb3d9", seed=3, phase_dx=None):
    d = np.sqrt((XX - cx) ** 2 + (YY - cy) ** 2)
    disc = smoothstep(r + 1.5, r - 1.5, d)
    tex = noise(r * 0.9, 4, seed)
    tex2 = noise(r * 0.25, 3, seed + 1)
    shade = 0.78 + 0.22 * smoothstep(0.35, 0.65, tex) - 0.08 * smoothstep(0.55, 0.7, tex2)
    limb_dark = 1 - 0.25 * (d / r) ** 3
    col = rgb(color)[None, None, :] * (shade * limb_dark)[..., None]
    if phase_dx is not None:
        d2 = np.sqrt((XX - cx - phase_dx) ** 2 + (YY - cy) ** 2)
        disc = disc * (0.12 + 0.88 * smoothstep(r - 2, r + 2, d2))
    glow(img, cx, cy, r * 4.0, glow_col, 0.35, 2.4)
    glow(img, cx, cy, r * 1.7, glow_col, 0.35, 2.0)
    img[:] = img * (1 - disc[..., None]) + col * disc[..., None]


def rays(img, cx, cy, color, count=40, seed=1, strength=0.35, reach=1500, spread=1.0):
    rng = np.random.default_rng(seed)
    ang = np.arctan2(YY - cy, XX - cx)
    vals = rng.random(count * 4) ** 3
    xs = np.linspace(-math.pi, math.pi, len(vals))
    a1 = np.interp(ang * spread, xs, vals, period=2 * math.pi)
    vals2 = rng.random(count * 9) ** 4
    a2 = np.interp(ang * spread, np.linspace(-math.pi, math.pi, len(vals2)), vals2, period=2 * math.pi)
    d = np.sqrt((XX - cx) ** 2 + (YY - cy) ** 2)
    fall = np.clip(1 - d / reach, 0, 1) ** 1.5
    img += ((a1 * 0.7 + a2 * 0.5) * fall * strength)[..., None] * rgb(color)


def beam(img, x0, y0, angle_deg, width_deg, color, strength=0.25, length=1600):
    ang = np.degrees(np.arctan2(YY - y0, XX - x0))
    diff = (ang - angle_deg + 180) % 360 - 180
    d = np.sqrt((XX - x0) ** 2 + (YY - y0) ** 2)
    b = np.exp(-(diff / width_deg) ** 2) * np.clip(1 - d / length, 0, 1) * smoothstep(0, 120, d)
    img += (b * strength)[..., None] * rgb(color)


def rain(img, seed, n, color="#b9c6db", alpha=0.12, angle=100, lmin=25, lmax=70):
    rng = random.Random(seed)
    im = Image.new("L", (W, H), 0)
    d = ImageDraw.Draw(im)
    ca, sa = math.cos(math.radians(angle)), math.sin(math.radians(angle))
    for _ in range(n):
        x, y = rng.uniform(-100, W + 100), rng.uniform(-100, H)
        L = rng.uniform(lmin, lmax)
        d.line([(x, y), (x + ca * L, y + sa * L)], fill=int(rng.uniform(80, 255)), width=1)
    a = np.asarray(im.filter(ImageFilter.GaussianBlur(0.6)), np.float32) / 255.0
    img += (a * alpha)[..., None] * rgb(color)


def web(img, cx, cy, a0, a1, spokes, rings, rmax, color="#e8ecf5", alpha=0.35, seed=5, width=1.3):
    rng = random.Random(seed)
    m = Mask()
    angs = [a0 + (a1 - a0) * i / (spokes - 1) + rng.uniform(-2, 2) for i in range(spokes)]
    radii = []
    r = rmax * 0.06
    while r < rmax:
        radii.append(r)
        r *= rng.uniform(1.16, 1.24)
    for a in angs:
        m.line([(cx, cy), (cx + math.cos(math.radians(a)) * rmax * 1.1, cy + math.sin(math.radians(a)) * rmax * 1.1)], width)
    for r in radii[: rings]:
        for i in range(len(angs) - 1):
            p0 = (cx + math.cos(math.radians(angs[i])) * r, cy + math.sin(math.radians(angs[i])) * r)
            p1 = (cx + math.cos(math.radians(angs[i + 1])) * r, cy + math.sin(math.radians(angs[i + 1])) * r)
            mid = ((p0[0] + p1[0]) / 2, (p0[1] + p1[1]) / 2)
            ctrl = (mid[0] + (cx - mid[0]) * 0.12, mid[1] + (cy - mid[1]) * 0.12)  # sag toward centre
            pts = [((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * ctrl[0] + t * t * p1[0],
                    (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * ctrl[1] + t * t * p1[1]) for t in np.linspace(0, 1, 14)]
            m.line(pts, width * 0.85)
    a = m.arr()
    fade = np.clip(1 - np.sqrt((XX - cx) ** 2 + (YY - cy) ** 2) / (rmax * 1.05), 0, 1) ** 0.6
    img += ((a + blur(a, 4) * 1.5) * alpha * fade)[..., None] * rgb(color)


def finish(img, name, vignette=0.45, grain=0.012, seed=0):
    d = np.sqrt(((XX - W / 2) / (W * 0.62)) ** 2 + ((YY - H / 2) / (H * 0.62)) ** 2)
    img *= (1 - vignette * smoothstep(0.55, 1.25, d))[..., None]
    rng = np.random.default_rng(seed + 99)
    img += (rng.standard_normal((H, W)).astype(np.float32) * grain)[..., None]
    # soft filmic tone curve
    img = 1 - np.exp(-np.clip(img, 0, None) * 1.25)
    img = img / (1 - math.exp(-1.25))
    out = Image.fromarray((np.clip(img, 0, 1) * 255 + 0.5).astype(np.uint8))
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, name)
    out.save(path, "JPEG", quality=88, optimize=True, progressive=False, subsampling=0)
    print("wrote", path, os.path.getsize(path) // 1024, "KB")


# ----------------------------------------------------------------- scenes
def scene_web_city():  # "spiderman.jpg" — red/blue twilight, swinging hero, web
    img = vgrad([(0, "#0b1030"), (0.45, "#2a1440"), (0.72, "#7a1c2a"), (0.86, "#c0392b"), (1, "#3a0f17")])
    glow(img, 1350, 780, 900, "#ff5a3c", 0.35, 2.0, 1.6, 0.6)
    stars(img, 11, 260, 450, 0.7)
    clouds(img, 4, 380, 700, "#5a1f3a", 0.42, 420, 0.55, (1350, 780, "#ff7a50", 900))
    web(img, W + 40, -60, 95, 185, 13, 14, 1050, "#dfe6ff", 0.22, 7)
    skyline(21, 1030, 230, 520, 70, 170, "#1a0e1f", "#6a1f33", 0.6, 0.0, spires=0.2, img=img)
    skyline(22, 1080, 160, 430, 90, 210, "#07070c", None, 0, 0.11, ("#ffcf8a", "#9fc4ff", "#ff9a7a"), img=img)
    # web line + swinging figure
    m = Mask()
    J = pose(haR=(0.35, -9.7), elR=(0.85, -8.25), elL=(-1.75, -6.1), haL=(-2.75, -5.55),
             hipL=(-0.45, -4.0), hipR=(0.45, -4.0), knL=(-1.35, -3.1), ftL=(-0.95, -1.35),
             knR=(0.45, -2.55), ftR=(1.35, -1.55))
    P = draw_figure(m, J, 1330, 610, 44, ang=-18)
    m.line([P["haR"], (1080, -20)], 2.2)
    a = m.arr()
    silhouette(img, a, "#07060c", "#ff6a55", -4, -2, 1.4)
    img += rim(a, 3, 1)[..., None] * rgb("#5b7bff") * 0.8
    finish(img, "spiderman.jpg", seed=1)


def scene_web_alt():  # "spiderman-alt.jpg" — blue night, hanging hero, big moon, neon
    img = vgrad([(0, "#03050f"), (0.5, "#0b1a3d"), (0.8, "#1d2f6b"), (1, "#0a0d1c")])
    stars(img, 12, 400, 600, 0.8)
    moon(img, 1380, 330, 205, "#f6f1ea", "#6f8fe0", 9)
    clouds(img, 8, 450, 760, "#22305e", 0.38, 360, 0.6, (1380, 330, "#9db4ff", 600))
    web(img, -40, -40, -5, 85, 12, 12, 820, "#cfe0ff", 0.18, 3)
    skyline(31, 1040, 260, 560, 60, 150, "#0c1430", "#2c3f80", 0.55, 0.02, ("#7fb0ff",), img=img)
    skyline(32, 1080, 140, 380, 80, 200, "#05060b", None, 0, 0.14, ("#ff4d5e", "#7fb0ff", "#ffd28a"), img=img)
    glow(img, 600, 1080, 700, "#ff2e4d", 0.22, 2.0, 1.4, 0.5)
    m = Mask()
    J = pose(ftL=(-0.18, 0.0), ftR=(0.18, 0.0), knL=(-0.32, -2.0), knR=(0.32, -2.0),
             hipL=(-0.45, -4.0), hipR=(0.45, -4.0),
             elL=(-1.35, -5.3), haL=(-0.55, -4.55), elR=(1.45, -5.6), haR=(2.2, -4.6))
    P = draw_figure(m, J, 1395, 145, 40, ang=180)
    m.line([((P["ftL"][0] + P["ftR"][0]) / 2, P["ftL"][1]), (1395, -10)], 2.0)
    a = m.arr()
    silhouette(img, a, "#05060c", "#cfe0ff", 0, 5, 1.0)
    img += rim(a, -3, -2)[..., None] * rgb("#ff3355") * 0.9
    finish(img, "spiderman-alt.jpg", seed=2)


def scene_sky_guardian():  # "superman.jpg" — above the clouds at sunrise, flying hero
    img = vgrad([(0, "#0b2a6b"), (0.35, "#2b5bb0"), (0.6, "#7fa6d6"), (0.75, "#f2c58a"), (1, "#c98a52")])
    glow(img, 1480, 470, 1100, "#ffd38a", 0.75, 2.2, 1.3, 0.8)
    glow(img, 1480, 470, 260, "#fff4d6", 1.1, 1.6)
    rays(img, 1480, 470, "#ffe6b0", 50, 3, 0.22, 1700)
    clouds(img, 5, 650, 1080, "#e9d2b8", 0.62, 300, 0.95, (1480, 470, "#ffd9a0", 1100), soft=0.2)
    clouds(img, 6, 820, 1080, "#b88a6a", 0.55, 220, 0.7, None, soft=0.25)
    clouds(img, 7, 120, 330, "#cfdcf5", 0.3, 520, 0.35)
    m, cm = Mask(), Mask()
    J = pose(haR=(0.7, -9.6), elR=(0.95, -8.1), elL=(-1.05, -5.1), haL=(-0.9, -3.85),
             hipL=(-0.42, -4.0), hipR=(0.42, -4.0), knL=(-0.38, -2.0), ftL=(-0.32, 0.0),
             knR=(0.38, -2.15), ftR=(0.65, -0.45))
    cape = [(-0.9, -6.7), (0.9, -6.7), (1.55, -4.6), (2.0, -2.2), (2.5, 0.6), (1.6, 1.7), (0.8, 1.0),
            (-0.1, 2.1), (-0.9, 1.3), (-1.9, 1.9), (-1.75, -1.0), (-1.4, -4.5)]
    draw_figure(m, J, 1470, 560, 36, ang=62, cape=cape, cape_mask=cm)
    ca, ba = cm.arr(), m.arr()
    over(img, "#3a070c", ca)
    img += (rim(ca, -3, 3) * 0.9)[..., None] * rgb("#ff7a4a")
    silhouette(img, ba, "#0d1426", "#fff1cf", -4, 3, 1.6)
    finish(img, "superman.jpg", vignette=0.35, seed=3)


def scene_sky_alt():  # "superman-alt.jpg" — hovering hero above a dawn city
    img = vgrad([(0, "#081a45"), (0.4, "#1f4a92"), (0.68, "#6f8fc0"), (0.8, "#e7b884"), (1, "#3b2a3a")])
    glow(img, 400, 760, 900, "#ffc27a", 0.6, 2.0, 1.4, 0.6)
    rays(img, 400, 760, "#ffd9a0", 40, 8, 0.12, 1500)
    clouds(img, 9, 200, 600, "#c9d6ef", 0.35, 480, 0.45, (400, 760, "#ffcf9a", 1200))
    skyline(41, 1000, 120, 330, 40, 110, "#2c3556", "#8a8fb0", 0.6, 0.0, img=img)
    skyline(42, 1080, 90, 260, 60, 150, "#141826", None, 0, 0.06, ("#ffd9a0",), img=img)
    m, cm = Mask(), Mask()
    J = pose(knL=(-0.55, -2.0), ftL=(-0.5, 0.0), knR=(0.5, -2.2), ftR=(0.65, -0.3),
             elL=(-1.35, -5.1), haL=(-1.5, -3.85), elR=(1.35, -5.1), haR=(1.5, -3.85))
    cape = [(-0.9, -6.75), (0.9, -6.75), (1.35, -5.6), (0.9, -3.4), (0.1, -1.4), (-1.0, -0.7),
            (-2.4, -1.0), (-3.6, -1.9), (-4.5, -3.1), (-3.5, -3.4), (-2.6, -4.6), (-1.6, -6.0)]
    draw_figure(m, J, 1420, 640, 50, ang=0, cape=cape, cape_mask=cm)
    ca, ba = cm.arr(), m.arr()
    over(img, "#5c0d14", ca)
    img += (rim(ca, 4, 0) * 0.8)[..., None] * rgb("#ffb070")
    silhouette(img, ba, "#0e1630", "#ffd7a0", 4, 2, 1.4)
    img += rim(ba, -3, 0)[..., None] * rgb("#6f9dff") * 0.5
    finish(img, "superman-alt.jpg", vignette=0.4, seed=4)


def scene_night_sentinel():  # "batman.jpg" — rainy gothic city, caped sentinel on a ledge
    img = vgrad([(0, "#020306"), (0.5, "#0b1018"), (0.85, "#1a2230"), (1, "#07090d")])
    moon(img, 1370, 300, 190, "#e9e6dc", "#8a99b5", 4)
    clouds(img, 10, 150, 620, "#1c2330", 0.45, 330, 0.75, (1370, 300, "#a9b6cc", 650), soft=0.22)
    beam(img, 520, 1080, -78, 2.2, "#c8d3e6", 0.16)
    beam(img, 980, 1080, -98, 1.8, "#c8d3e6", 0.12)
    skyline(51, 1000, 260, 600, 60, 160, "#0e131c", "#2a3445", 0.6, 0.02, ("#e8b86a",), 0.5, img=img)
    skyline(52, 1080, 160, 420, 70, 190, "#050609", None, 0, 0.08, ("#e8b86a", "#d9a05a"), 0.3, img=img)
    # ledge / gargoyle block
    st = Mask()
    st.poly([(1080, 1080), (1080, 845), (1180, 820), (1640, 820), (1700, 845), (1700, 1080)])
    st.poly([(1150, 830), (1210, 770), (1290, 760), (1330, 800), (1300, 830)])
    over(img, "#040508", st.arr())
    m = Mask()
    J = pose(elL=(-1.05, -5.1), haL=(-1.0, -3.9), elR=(1.05, -5.1), haR=(1.0, -3.9))
    cape = [(-0.85, -6.75), (0.85, -6.75), (1.45, -5.6), (1.85, -3.2), (2.15, -0.7), (1.55, -0.2),
            (0.95, -0.5), (0.35, -0.1), (-0.3, -0.45), (-0.95, -0.12), (-1.55, -0.5), (-2.15, -0.25),
            (-1.85, -3.0), (-1.45, -5.5)]
    draw_figure(m, J, 1420, 822, 50, cape=cape)
    a = m.arr()
    silhouette(img, a, "#030406", "#c9d4e8", -3, 3, 1.2)
    rain(img, 3, 1800, "#a9b8d0", 0.16)
    finish(img, "batman.jpg", vignette=0.5, seed=5)


def scene_night_glide():  # "batman-alt.jpg" — glider cape across the moon
    img = vgrad([(0, "#03040a"), (0.55, "#101828"), (0.85, "#232c3f"), (1, "#08090e")])
    moon(img, 1300, 380, 260, "#efe9dc", "#7f8fae", 6)
    clouds(img, 12, 250, 700, "#161c28", 0.42, 360, 0.7, (1300, 380, "#a3b0c8", 700))
    skyline(61, 1020, 280, 640, 50, 140, "#0b0f17", "#273042", 0.55, 0.02, ("#e0b070",), 0.45, img=img)
    skyline(62, 1080, 150, 380, 70, 180, "#040508", None, 0, 0.07, ("#e0b070",), 0.3, img=img)
    m = Mask()
    J = pose(elL=(-2.2, -6.25), haL=(-3.45, -6.45), elR=(2.2, -6.25), haR=(3.45, -6.45),
             ftL=(-0.25, -0.05), ftR=(0.25, -0.05), knL=(-0.35, -2.0), knR=(0.35, -2.0))
    cape = [(-3.6, -6.75), (-2.0, -6.85), (0.0, -7.0), (2.0, -6.85), (3.6, -6.75), (3.5, -5.6),
            (3.0, -4.3), (2.4, -4.85), (1.9, -3.3), (1.2, -3.9), (0.6, -2.0), (0.0, -2.8),
            (-0.6, -2.0), (-1.2, -3.9), (-1.9, -3.3), (-2.4, -4.85), (-3.0, -4.3), (-3.5, -5.6)]
    draw_figure(m, J, 1300, 610, 42, ang=-8, cape=cape)
    a = m.arr()
    silhouette(img, a, "#030407", "#bcc8de", 0, 4, 0.8)
    rain(img, 6, 900, "#a9b8d0", 0.08)
    finish(img, "batman-alt.jpg", vignette=0.5, seed=6)


def scene_guardians():  # "hero-original.jpg" — original team on a ridge at sunset
    img = vgrad([(0, "#06131f"), (0.4, "#0f3a4a"), (0.66, "#3f7a7a"), (0.8, "#f08a4b"), (1, "#3a1c1a")])
    glow(img, 1350, 840, 1000, "#ff9a4a", 0.55, 2.0, 1.6, 0.6)
    rays(img, 1350, 840, "#ffc58a", 36, 12, 0.14, 1500)
    stars(img, 13, 160, 300, 0.5)
    clouds(img, 14, 180, 640, "#1f4d58", 0.4, 460, 0.55, (1350, 840, "#ffb27a", 1000))
    m = Mask()
    ridge = [(-10, 1080), (-10, 960)] + [(x, 900 + 40 * math.sin(x / 210) + 18 * math.sin(x / 57)) for x in range(0, W + 20, 20)] + [(W + 10, 1080)]
    m.poly(ridge)
    cm = Mask()
    s = 40
    cape = [(-0.85, -6.75), (0.85, -6.75), (1.6, -5.3), (2.6, -3.0), (3.4, -1.0), (2.2, -1.2),
            (0.9, -0.6), (-0.7, -0.4), (-1.5, -3.0), (-1.4, -5.5)]
    draw_figure(m, pose(), 1350, 902, s * 1.05, cape=cape, cape_mask=cm)
    draw_figure(m, pose(elL=(-1.5, -5.1), haL=(-1.8, -3.8), elR=(1.5, -5.1), haR=(1.8, -3.8),
                        ftL=(-0.95, -0.1), ftR=(0.95, -0.1), knL=(-0.85, -2.0), knR=(0.85, -2.0)),
                1150, 912, s * 1.08, build=1.25)
    draw_figure(m, pose(elR=(1.4, -6.8), haR=(1.3, -8.4), ftL=(-0.5, -0.1), ftR=(0.55, -0.1)),
                1550, 905, s * 0.95, build=0.88)
    a = np.maximum(m.arr(), cm.arr())
    silhouette(img, a, "#05080b", "#ffb27a", 3, 3, 1.3)
    finish(img, "hero-original.jpg", vignette=0.45, seed=7)


def scene_dark_rain():  # "hero-dark.jpg" — lone hero under a streetlight in the rain
    img = vgrad([(0, "#05040a"), (0.5, "#120d22"), (0.85, "#1f1636"), (1, "#07060c")])
    skyline(71, 900, 300, 700, 60, 150, "#100c1c", "#2a2148", 0.5, 0.012, ("#b48cff", "#ffb070"), 0.35, img=img)
    clouds(img, 15, 0, 380, "#1a1430", 0.45, 380, 0.6)
    beam(img, 1420, 120, 90, 9.5, "#e9d8ff", 0.42, 1100)
    glow(img, 1420, 125, 60, "#fff0d8", 1.2, 1.5)
    glow(img, 1420, 920, 420, "#9a7cff", 0.35, 2.0, 1.6, 0.25)
    st = Mask()
    st.rect(1404, 120, 1414, 1080)
    st.poly([(1380, 112), (1440, 112), (1428, 128), (1392, 128)])
    st.rect(0, 960, W, 1080)
    over(img, "#06050b", st.arr())
    m = Mask()
    cm = Mask()
    cape = [(-0.85, -6.75), (0.85, -6.75), (1.4, -5.4), (1.7, -3.0), (1.9, -0.6), (1.1, -0.35),
            (0.0, -0.55), (-1.1, -0.3), (-1.9, -0.6), (-1.7, -3.0), (-1.4, -5.4)]
    draw_figure(m, pose(), 1560, 962, 44, cape=cape, cape_mask=cm)
    a = np.maximum(m.arr(), cm.arr())
    silhouette(img, a, "#05040a", "#d8c8ff", -4, 0, 1.2)
    rain(img, 9, 2200, "#c9b8ff", 0.16)
    # wet floor reflection
    refl = np.clip(1 - np.abs(XX - 1420) / 260, 0, 1) * smoothstep(960, 1080, YY) * 0.35
    img += refl[..., None] * rgb("#9a7cff")
    finish(img, "hero-dark.jpg", vignette=0.55, seed=8)


def scene_neutral():  # "hero-neutral.jpg" — desaturated night city, distant rooftop hero
    img = vgrad([(0, "#07080b"), (0.55, "#14171d"), (0.85, "#262a33"), (1, "#0a0b0e")])
    stars(img, 14, 220, 420, 0.4)
    clouds(img, 16, 120, 560, "#1e2128", 0.42, 420, 0.55, (1400, 260, "#8d939f", 700))
    glow(img, 1400, 260, 500, "#8d939f", 0.25, 2.0)
    skyline(81, 1000, 260, 640, 60, 150, "#111318", "#2e323b", 0.5, 0.018, ("#d9d2c0",), 0.3, img=img)
    st = Mask()
    st.poly([(1180, 1080), (1180, 760), (1720, 760), (1720, 1080)])
    st.rect(1300, 690, 1316, 760)
    over(img, "#06070a", st.arr())
    m = Mask()
    cm = Mask()
    cape = [(-0.85, -6.75), (0.85, -6.75), (1.6, -5.0), (2.6, -2.4), (3.0, -0.6), (1.8, -0.8),
            (0.6, -0.3), (-0.8, -0.4), (-1.5, -3.0), (-1.4, -5.5)]
    draw_figure(m, pose(), 1520, 762, 30, cape=cape, cape_mask=cm)
    skyline(82, 1080, 120, 300, 80, 200, "#06070a", None, 0, 0.06, ("#d9d2c0",), 0.2, img=img)
    a = np.maximum(m.arr(), cm.arr())
    silhouette(img, a, "#06070a", "#c2c7d2", -3, 2, 0.9)
    finish(img, "hero-neutral.jpg", vignette=0.5, seed=9)


def scene_default():  # "default.jpg" — SHAHU'S TV default: three hero lights over the skyline
    img = vgrad([(0, "#05060b"), (0.5, "#0b0d18"), (0.8, "#171a2c"), (1, "#08090d")])
    stars(img, 15, 380, 650, 0.7)
    glow(img, 1150, 900, 700, "#d4283c", 0.30, 2.0, 1.3, 0.8)
    glow(img, 1500, 880, 700, "#2f6bff", 0.30, 2.0, 1.3, 0.8)
    glow(img, 1800, 900, 600, "#ffb24a", 0.22, 2.0, 1.3, 0.8)
    beam(img, 1150, 1080, -96, 1.6, "#ff7a8a", 0.14)
    beam(img, 1500, 1080, -84, 1.6, "#8fb0ff", 0.14)
    beam(img, 1800, 1080, -72, 1.6, "#ffd59a", 0.10)
    clouds(img, 17, 150, 560, "#151a2e", 0.38, 420, 0.55)
    skyline(91, 1020, 220, 520, 60, 150, "#0e1120", "#2a3050", 0.5, 0.02, ("#ffd28a", "#9fc0ff"), img=img)
    skyline(92, 1080, 140, 360, 70, 190, "#05060a", None, 0, 0.1, ("#ffd28a", "#9fc0ff", "#ff8a8a"), img=img)
    finish(img, "default.jpg", vignette=0.45, seed=10)


# ----------------------------------------------------------------- app icon + banner
def brand_assets():
    res = os.path.join(ROOT, "app", "src", "main", "res")
    os.makedirs(os.path.join(res, "drawable-xhdpi"), exist_ok=True)
    os.makedirs(os.path.join(res, "mipmap-xhdpi"), exist_ok=True)
    # banner 320x180 (xhdpi) — shown in the Fire TV "Your Apps" row
    bw, bh = 640, 360
    im = Image.new("RGB", (bw, bh), (8, 9, 13))
    px = np.zeros((bh, bw, 3), np.float32) + rgb("#08090d")
    yy, xx = np.mgrid[0:bh, 0:bw].astype(np.float32)
    for cx, col, k in ((150, "#d4283c", 0.45), (490, "#2f6bff", 0.45)):
        d = np.clip(1 - np.sqrt(((xx - cx) / 1.2) ** 2 + ((yy - 420) / 0.9) ** 2) / 420, 0, 1) ** 2
        px += d[..., None] * rgb(col) * k
    im = Image.fromarray((np.clip(px, 0, 1) * 255).astype(np.uint8))
    d = ImageDraw.Draw(im)
    f = ImageFont.truetype(FONT, 92, index=0)
    text = "SHAHU'S TV"
    tw = d.textlength(text, font=f)
    d.text(((bw - tw) / 2, 112), text, font=f, fill=(255, 255, 255))
    d.rectangle([(bw - 120) / 2, 240, (bw + 120) / 2, 245], fill=(212, 40, 60))
    im.resize((320, 180), Image.LANCZOS).save(os.path.join(res, "drawable-xhdpi", "banner.png"))
    # icon 96x96 (xhdpi)
    ic = Image.new("RGB", (384, 384), (8, 9, 13))
    d = ImageDraw.Draw(ic)
    d.ellipse([-120, 230, 260, 560], fill=(80, 16, 26))
    d.ellipse([140, 230, 520, 560], fill=(16, 34, 92))
    ic = ic.filter(ImageFilter.GaussianBlur(40))
    d = ImageDraw.Draw(ic)
    f = ImageFont.truetype(FONT, 200, index=0)
    tw = d.textlength("S", font=f)
    d.text(((384 - tw) / 2, 40), "S", font=f, fill=(255, 255, 255))
    d.rectangle([152, 300, 232, 308], fill=(212, 40, 60))
    ic.resize((96, 96), Image.LANCZOS).save(os.path.join(res, "mipmap-xhdpi", "ic_launcher.png"))
    print("wrote banner + icon")


SCENES = [scene_web_city, scene_web_alt, scene_sky_guardian, scene_sky_alt, scene_night_sentinel,
          scene_night_glide, scene_guardians, scene_dark_rain, scene_neutral, scene_default]

if __name__ == "__main__":
    import sys
    only = sys.argv[1:]
    for fn in SCENES:
        if not only or fn.__name__ in only:
            fn()
    if not only or "brand" in only:
        brand_assets()
