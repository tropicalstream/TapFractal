#!/usr/bin/env python3
"""
make_icon.py — renders the TapFractal launcher icon programmatically.

The icon is an actual escape-time Julia set (c = -0.7269 + 0.1889i, the classic
double-spiral) coloured with a cyclic hot-magenta / cyan / violet palette on
near-black (#05020c). Interior points are lit by orbit traps (point + circle)
so the "eyes" of the spirals glow. On top sits the sluurp: a small eyeball-orb
(white-hot rim, cyan iris, dark pupil) with a faint glowing rope arc curving
away from it. A soft two-scale bloom finishes the picture.

Outputs (all under the project root):
  app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher_foreground.png
      adaptive-icon foreground, 108 dp canvas (108/162/216/324/432 px); the
      focal artwork (spiral core, eyeball, rope) sits inside the 72 dp safe
      zone, the fractal field continues outward so any mask shape is filled.
  app/src/main/res/mipmap-*/ic_launcher.png, ic_launcher_round.png
      legacy icons at 48/72/96/144/192 px (rounded square / circle).
  art/icon-512.png
      README hero.

Usage:
  tools/.venv/bin/python tools/make_icon.py            # render everything
  tools/.venv/bin/python tools/make_icon.py --preview  # quick art/preview.png only

Dependencies: pillow, numpy (tools/.venv).
"""

import argparse
import math
import os

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
ART = os.path.join(ROOT, "art")

BG = np.array([0x05, 0x02, 0x0C], dtype=np.float64) / 255.0

# ----------------------------------------------------------------- fractal view
# Julia constant: the classic lush double spiral.
C = complex(-0.7269, 0.1889)
# View window in the complex plane (centre + width). The master render is a
# square, so the height equals the width.
CENTER = complex(0.41, -0.20)
SPAN = 1.5
MAX_ITER = 220
ESCAPE_R = 16.0

# Orbit traps (interior lighting).
TRAP_POINT = complex(0.0, 0.0)
TRAP_CIRCLE_R = 0.55

# Composition (unit coordinates of the master square, origin top-left).
# Everything "focal" must stay within radius ~0.30 of (0.5, 0.5) so that a
# circular adaptive mask (72 dp of the 108 dp canvas) never clips it.
EYE_POS = (0.585, 0.415)
EYE_R = 0.072
ROPE_END = (0.29, 0.72)          # far end of the rope (fades out)
ROPE_CTRL = (0.60, 0.80)         # quadratic Bezier control point (the sag)

# Palette (display-space RGB, cyclic stops for the exterior bands).
PALETTE = [
    (0.00, (0.22, 0.02, 0.60)),   # deep violet
    (0.18, (0.62, 0.05, 0.95)),   # violet-magenta
    (0.34, (1.00, 0.10, 0.80)),   # hot magenta
    (0.50, (1.00, 0.30, 0.90)),   # hot pink crest
    (0.62, (0.12, 0.92, 1.00)),   # cyan
    (0.80, (0.15, 0.25, 1.00)),   # electric blue
    (1.00, (0.22, 0.02, 0.60)),   # back to violet
]
MAGENTA = np.array([1.00, 0.16, 0.86])
CYAN = np.array([0.18, 0.96, 1.00])
VIOLET = np.array([0.55, 0.22, 1.00])
WHITE_HOT = np.array([1.00, 0.97, 1.00])

FG_DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
LEGACY_DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


# --------------------------------------------------------------------- helpers
def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def palette(t):
    """Cyclic gradient lookup; t is any float array, wrapped to [0,1)."""
    t = np.mod(t, 1.0)
    xs = np.array([p[0] for p in PALETTE])
    out = np.zeros(t.shape + (3,))
    for ch in range(3):
        ys = np.array([p[1][ch] for p in PALETTE])
        out[..., ch] = np.interp(t, xs, ys)
    return out


def box_blur(img, r):
    """Fast separable box blur via cumulative sums (edge-clamped)."""
    if r < 1:
        return img
    pad = ((r, r), (r, r)) + ((0, 0),) * (img.ndim - 2)
    p = np.pad(img, pad, mode="edge")
    c = np.cumsum(p, axis=0)
    c = np.concatenate([np.zeros_like(c[:1]), c], axis=0)
    v = (c[2 * r + 1:] - c[:-2 * r - 1]) / (2 * r + 1)
    c = np.cumsum(v, axis=1)
    c = np.concatenate([np.zeros_like(c[:, :1]), c], axis=1)
    h = (c[:, 2 * r + 1:] - c[:, :-2 * r - 1]) / (2 * r + 1)
    return h


def gauss_blur(img, sigma):
    """Three box blurs approximate a Gaussian of the given sigma."""
    r = max(1, int(round(sigma * 0.85)))
    out = img
    for _ in range(3):
        out = box_blur(out, r)
    return out


def unit_grid(n):
    u = (np.arange(n) + 0.5) / n
    U, V = np.meshgrid(u, u)
    return U, V


# --------------------------------------------------------------------- fractal
def julia(n, ss):
    """Return (smooth_iter, escaped, trap_point, trap_circle) at n*ss resolution."""
    N = n * ss
    half = SPAN / 2.0
    xs = np.linspace(-half, half, N) + CENTER.real
    ys = np.linspace(half, -half, N) + CENTER.imag
    X, Y = np.meshgrid(xs, ys)
    z = (X + 1j * Y).astype(np.complex128)

    smooth = np.zeros((N, N))
    escaped = np.zeros((N, N), dtype=bool)
    trap_pt = np.full((N, N), 1e9)
    trap_ci = np.full((N, N), 1e9)
    log_r = math.log(ESCAPE_R)

    for i in range(MAX_ITER):
        z = z * z + C
        az = np.abs(z)
        newly = (~escaped) & (az > ESCAPE_R)
        if newly.any():
            with np.errstate(divide="ignore", invalid="ignore"):
                nu = i + 1.0 - np.log(np.log(np.maximum(az, 1.0001)) / log_r) / math.log(2.0)
            smooth = np.where(newly, nu, smooth)
            escaped |= newly
        alive = ~escaped
        trap_pt = np.where(alive, np.minimum(trap_pt, np.abs(z - TRAP_POINT)), trap_pt)
        trap_ci = np.where(alive, np.minimum(trap_ci, np.abs(az - TRAP_CIRCLE_R)), trap_ci)
        # Park escaped points so they never overflow to inf/nan.
        z = np.where(escaped, 0.0, z)

    return smooth, escaped, trap_pt, trap_ci


def downsample(arr, ss):
    if ss == 1:
        return arr
    n = arr.shape[0] // ss
    if arr.ndim == 2:
        return arr[: n * ss, : n * ss].reshape(n, ss, n, ss).mean(axis=(1, 3))
    return arr[: n * ss, : n * ss].reshape(n, ss, n, ss, arr.shape[2]).mean(axis=(1, 3))


def render_fractal(n, ss):
    smooth, escaped, trap_pt, trap_ci = julia(n, ss)
    N = n * ss
    img = np.zeros((N, N, 3))

    # -- exterior: cyclic bands, brightening toward the boundary -------------
    s = np.maximum(smooth, 0.0)
    # Phase chosen so the brightest (near-boundary) contour is hot magenta,
    # with cyan and violet contours stepping outward from it.
    band = np.log1p(s) * 0.36 + 0.49
    col = palette(band)
    # brightness: far exterior is near-black, the boundary blazes
    bright = 1.0 - np.exp(-s / 24.0)
    bright = bright ** 2.4
    ext = col * bright[..., None] * 0.92
    # faint cool haze so even the far exterior is not dead
    ext += VIOLET * (0.035 * (1.0 - bright))[..., None]

    # -- interior: orbit-trap glow ------------------------------------------
    g_pt = np.exp(-trap_pt * 9.0)
    g_ci = np.exp(-trap_ci * 55.0)
    interior = (
        VIOLET * 0.07
        + CYAN * (0.95 * g_ci)[..., None]
        + MAGENTA * (0.50 * g_pt)[..., None]
        + WHITE_HOT * (0.25 * g_ci * g_pt)[..., None]
    )

    img = np.where(escaped[..., None], ext, interior)
    return downsample(img, ss)


# ------------------------------------------------------------------- the sluurp
def eyeball(n):
    """Additive eyeball-orb layer + halo layer (both n×n×3) and an opaque mask."""
    U, V = unit_grid(n)
    ex, ey = EYE_POS
    dx, dy = U - ex, V - ey
    d = np.sqrt(dx * dx + dy * dy) / EYE_R  # 1.0 at the orb rim

    layer = np.zeros((n, n, 3))

    # Pupil: dark core, slightly offset so the eye "looks" along the rope.
    px, py = ex - 0.012, ey + 0.010
    dp = np.sqrt((U - px) ** 2 + (V - py) ** 2) / EYE_R
    pupil = 1.0 - smoothstep(0.36, 0.42, dp)

    # Iris: cyan with radial fibres, darker rim.
    ang = np.arctan2(V - py, U - px)
    fibres = 0.5 + 0.5 * np.cos(ang * 22.0 + dp * 9.0)
    iris_mask = smoothstep(0.32, 0.38, dp) * (1.0 - smoothstep(0.72, 0.80, dp))
    iris_col = CYAN * (0.50 + 0.50 * fibres)[..., None] + np.array([0.05, 0.25, 1.00]) * (0.45 * (dp / 0.80))[..., None]
    iris_col *= (1.0 - 0.55 * smoothstep(0.64, 0.80, dp))[..., None]  # limbal ring

    # White-hot sclera / core ring.
    sclera_mask = smoothstep(0.72, 0.80, dp) * (1.0 - smoothstep(0.96, 1.02, d))
    sclera_col = WHITE_HOT * (1.15 - 0.25 * smoothstep(0.80, 1.0, d))[..., None]

    # Specular glint.
    gx, gy = ex - 0.026, ey - 0.028
    glint = np.exp(-(((U - gx) ** 2 + (V - gy) ** 2) / (0.010 * EYE_R) ** 2) * 0.28)

    body = (
        iris_col * iris_mask[..., None]
        + sclera_col * sclera_mask[..., None]
        + WHITE_HOT * (glint * 1.2)[..., None]
    )
    body *= (1.0 - pupil)[..., None]
    body += np.array([0.02, 0.01, 0.05]) * pupil[..., None]

    opaque = 1.0 - smoothstep(0.98, 1.04, d)
    layer = body

    # Halo: white-hot close in, cyan further out, then a wide magenta bloom.
    halo = (
        WHITE_HOT * (0.9 * np.exp(-np.maximum(d - 1.0, 0.0) * 9.0))[..., None]
        + CYAN * (0.9 * np.exp(-np.maximum(d - 1.0, 0.0) * 3.2))[..., None]
        + MAGENTA * (0.35 * np.exp(-np.maximum(d - 1.0, 0.0) * 1.3))[..., None]
    )
    halo *= (1.0 - opaque)[..., None]
    return layer, halo, opaque


def rope(n):
    """Faint glowing rope: quadratic Bezier from the orb rim, fading along its length."""
    U, V = unit_grid(n)
    p0 = np.array(EYE_POS)
    p1 = np.array(ROPE_CTRL)
    p2 = np.array(ROPE_END)
    # Start on the orb rim, heading toward the control point.
    t0 = np.linspace(0, 1, 400)
    pts = ((1 - t0) ** 2)[:, None] * p0 + (2 * (1 - t0) * t0)[:, None] * p1 + (t0 ** 2)[:, None] * p2

    dist = np.full((n, n), 1e9)
    along = np.zeros((n, n))
    for k, (x, y) in enumerate(pts):
        dk = np.sqrt((U - x) ** 2 + (V - y) ** 2)
        closer = dk < dist
        dist = np.where(closer, dk, dist)
        along = np.where(closer, t0[k], along)

    # Hide the part inside the orb, fade toward the far end.
    ex, ey = EYE_POS
    inside = np.sqrt((U - ex) ** 2 + (V - ey) ** 2) < EYE_R * 1.02
    fade = (1.0 - smoothstep(0.55, 1.0, along)) * (0.35 + 0.65 * (1.0 - along))
    fade = fade * (~inside)
    core_w = 0.0034
    core = np.exp(-(dist / core_w) ** 2)
    glow = np.exp(-dist / 0.016)
    layer = (
        WHITE_HOT * (1.1 * core)[..., None]
        + MAGENTA * (0.75 * glow)[..., None]
        + VIOLET * (0.25 * np.exp(-dist / 0.040))[..., None]
    )
    layer *= fade[..., None]
    # tiny knots along the rope so it reads as a rope, not a line
    knots = 0.5 + 0.5 * np.cos(along * 2 * np.pi * 26.0)
    layer *= (0.75 + 0.35 * knots)[..., None]
    # Soft dark band under the rope so it reads over bright fractal filaments.
    shadow = 1.0 - 0.75 * np.exp(-(dist / 0.0075) ** 2) * fade
    return layer, shadow


# ------------------------------------------------------------------ composite
def compose(n, ss):
    print(f"  julia {n * ss}x{n * ss} ({MAX_ITER} it) ...", flush=True)
    img = render_fractal(n, ss)

    print("  sluurp + rope ...", flush=True)
    eye, halo, opaque = eyeball(n)
    rp, rope_shadow = rope(n)

    img = img * rope_shadow[..., None] + rp + halo
    img = img * (1.0 - opaque)[..., None] + eye * opaque[..., None]

    print("  bloom ...", flush=True)
    bright = np.maximum(img - 0.70, 0.0)
    b_small = gauss_blur(bright, n * 0.008)
    b_large = gauss_blur(bright, n * 0.040)
    img = img + 0.45 * b_small + 0.30 * b_large

    # Soft tone-map: keep hot cores hot without a flat white field.
    img = 1.0 - np.exp(-img * 1.45)
    img = np.clip(img, 0.0, 1.0)

    # Radial vignette into the background colour so the outer field (only seen
    # under square-ish adaptive masks) is calm and blends into the bg layer.
    U, V = unit_grid(n)
    r = np.sqrt((U - 0.5) ** 2 + (V - 0.5) ** 2)
    vig = smoothstep(0.34, 0.70, r)
    img = img * (1.0 - vig)[..., None] + BG * vig[..., None]
    # Lift the floor to the bg colour everywhere (never pure black).
    img = np.maximum(img, BG)
    return img


def to_image(arr, alpha=None):
    rgb = (np.clip(arr, 0, 1) * 255.0 + 0.5).astype(np.uint8)
    if alpha is None:
        return Image.fromarray(rgb, "RGB").convert("RGBA")
    a = (np.clip(alpha, 0, 1) * 255.0 + 0.5).astype(np.uint8)
    return Image.fromarray(np.dstack([rgb, a]), "RGBA")


def rounded_alpha(n, radius_frac):
    U, V = unit_grid(n)
    rr = radius_frac
    x = np.abs(U - 0.5) - (0.5 - rr)
    y = np.abs(V - 0.5) - (0.5 - rr)
    d = np.sqrt(np.maximum(x, 0) ** 2 + np.maximum(y, 0) ** 2) + np.minimum(np.maximum(x, y), 0) - rr
    return 1.0 - smoothstep(-1.0 / n, 1.0 / n, d)


def circle_alpha(n):
    U, V = unit_grid(n)
    d = np.sqrt((U - 0.5) ** 2 + (V - 0.5) ** 2) - 0.5
    return 1.0 - smoothstep(-1.0 / n, 1.0 / n, d)


def crop_center(img, frac):
    n = img.shape[0]
    m = int(round(n * frac))
    o = (n - m) // 2
    return img[o:o + m, o:o + m]


def resize(img_rgba, size):
    return img_rgba.resize((size, size), Image.LANCZOS)


def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, "PNG", optimize=True)
    print(f"  wrote {path}")


# ------------------------------------------------------------------------ main
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", action="store_true", help="only write art/preview.png (fast)")
    ap.add_argument("--size", type=int, default=1024, help="master render size (px)")
    ap.add_argument("--ss", type=int, default=2, help="supersample factor for the fractal")
    args = ap.parse_args()

    if args.preview:
        n = min(args.size, 512)
        master = compose(n, 1)
        save(to_image(master), os.path.join(ART, "preview.png"))
        return

    print("rendering master ...")
    master = compose(args.size, args.ss)
    written = []

    # README hero — the central 76% so the composition fills the square.
    hero = crop_center(master, 0.76)
    hero_img = to_image(hero, rounded_alpha(hero.shape[0], 0.12))
    p = os.path.join(ART, "icon-512.png")
    save(resize(hero_img, 512), p)
    written.append(p)

    # Adaptive foreground: full master (focal art inside the 72 dp safe zone),
    # alpha fades to the background layer colour at the far corners.
    U, V = unit_grid(master.shape[0])
    r = np.sqrt((U - 0.5) ** 2 + (V - 0.5) ** 2)
    fg_alpha = 1.0 - smoothstep(0.60, 0.72, r)
    fg_img = to_image(master, fg_alpha)
    for dens, px in FG_DENSITIES.items():
        p = os.path.join(RES, f"mipmap-{dens}", "ic_launcher_foreground.png")
        save(resize(fg_img, px), p)
        written.append(p)

    # Legacy icons: same crop as the hero; rounded square + circle.
    sq_img = to_image(hero, rounded_alpha(hero.shape[0], 0.16))
    rd_img = to_image(hero, circle_alpha(hero.shape[0]))
    for dens, px in LEGACY_DENSITIES.items():
        p = os.path.join(RES, f"mipmap-{dens}", "ic_launcher.png")
        save(resize(sq_img, px), p)
        written.append(p)
        p = os.path.join(RES, f"mipmap-{dens}", "ic_launcher_round.png")
        save(resize(rd_img, px), p)
        written.append(p)

    print(f"done: {len(written)} files")


if __name__ == "__main__":
    main()
