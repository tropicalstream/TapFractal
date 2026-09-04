#!/usr/bin/env python3
"""Picture-level (APL) check for TapFractal screenshots on the waveguide.

usage: apl.py <png> [--left] [--full] [--json] [--vibrant]

Measures the LEFT-EYE crop (x < width/2) of a 1280x480 on-device screenshot by default
(`--left` is accepted for clarity; `--full` measures the whole image, e.g. an already cropped
640x480 file is measured whole automatically when its width <= 640).

Prints JSON: {meanLuma, brightFraction, paleFraction, maxLuma, meanSat, colorfulness, hueSpread,
              pass, vibrant, width, height}
  luma           = 0.2126 R + 0.7152 G + 0.0722 B on sRGB-DECODED (linear) values
  meanLuma       = mean luma over the crop                           acceptance <= 0.20
  brightFraction = fraction of pixels with luma > 0.55               acceptance <= 0.10
  paleFraction   = fraction of pixels with min(r, g, b) > 0.45       acceptance <= 0.02
  maxLuma        = maximum pixel luma
  meanSat        = mean HSV saturation (sRGB-encoded values, (max-min)/max) over the LIT pixels
                   (luma > 0.08)                                      --vibrant: >= 0.60
  colorfulness   = Hasler-Suesstrunk M on the crop (sRGB 0..255: rg = R-G, yb = (R+G)/2-B,
                   M = sqrt(var rg + var yb) + 0.3 * sqrt(mean rg^2 + mean yb^2)) --vibrant: >= 55
  hueSpread      = fraction of the hue wheel (36 x 10 deg bins) covered by lit pixels with sat > 0.5
                   (a bin counts when it holds >= 0.05 % of the crop)   informational
  vibrant        = meanSat >= 0.60 and colorfulness >= 55 (always reported)
  pass           = the dark-frame rules; with --vibrant ALSO the vibrant rule
Capture with the HUD hidden (Show HUD = Off, or `settings put global tapfractal_debug nohud`).
Acceptance is judged on three captures per room: idle cruise, 0.3 s after a flick, 0.3 s after a hit.
Exit status 0 = pass, 1 = fail, 2 = usage/error.
"""
import json
import math
import sys

try:
    from PIL import Image
except ImportError:  # pragma: no cover
    sys.stderr.write("pillow missing: tools/.venv/bin/pip install pillow\n")
    sys.exit(2)

MEAN_MAX, BRIGHT_MAX, PALE_MAX = 0.20, 0.10, 0.02
BRIGHT_LUMA, PALE_MIN = 0.55, 0.45
SAT_MIN, COLORFUL_MIN = 0.60, 55.0        # --vibrant rule
LIT_LUMA = 0.08                            # pixels that count for meanSat / hueSpread
HUE_SAT_MIN, HUE_BINS, HUE_BIN_MIN = 0.5, 36, 0.0005


def srgb_to_linear_table():
    t = []
    for i in range(256):
        c = i / 255.0
        t.append(c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4)
    return t


def hue_deg(r, g, b):
    """HSV hue in degrees of an sRGB triple (0..255); caller guarantees max > min."""
    mx, mn = max(r, g, b), min(r, g, b)
    d = float(mx - mn)
    if mx == r:
        h = 60.0 * (((g - b) / d) % 6.0)
    elif mx == g:
        h = 60.0 * ((b - r) / d + 2.0)
    else:
        h = 60.0 * ((r - g) / d + 4.0)
    return h % 360.0


def measure(img):
    lin = srgb_to_linear_table()
    img = img.convert("RGB")
    w, h = img.size
    n = w * h
    # histogram the (r,g,b) triples to keep pure-python fast enough for 640x480
    counts = {}
    it = img.get_flattened_data() if hasattr(img, "get_flattened_data") else img.getdata()   # (r, g, b) tuples
    for p in it:
        counts[p] = counts.get(p, 0) + 1
    sum_l = 0.0
    bright = 0
    pale = 0
    max_l = 0.0
    # vibrance metrics
    lit = 0
    sum_sat = 0.0
    sum_rg = sum_yb = sum_rg2 = sum_yb2 = 0.0
    hue_bins = [0] * HUE_BINS
    for (r, g, b), c in counts.items():
        lr, lg, lb = lin[r], lin[g], lin[b]
        l = 0.2126 * lr + 0.7152 * lg + 0.0722 * lb
        sum_l += l * c
        if l > BRIGHT_LUMA:
            bright += c
        if min(lr, lg, lb) > PALE_MIN:
            pale += c
        if l > max_l:
            max_l = l
        rg = float(r - g)
        yb = 0.5 * (r + g) - b
        sum_rg += rg * c
        sum_yb += yb * c
        sum_rg2 += rg * rg * c
        sum_yb2 += yb * yb * c
        if l > LIT_LUMA:
            lit += c
            mx = max(r, g, b)
            mn = min(r, g, b)
            sat = (mx - mn) / float(mx) if mx > 0 else 0.0
            sum_sat += sat * c
            if sat > HUE_SAT_MIN:
                hue_bins[int(hue_deg(r, g, b) / 360.0 * HUE_BINS) % HUE_BINS] += c
    mean = sum_l / n
    bf = bright / n
    pf = pale / n
    mean_sat = sum_sat / lit if lit else 0.0
    mu_rg, mu_yb = sum_rg / n, sum_yb / n
    var_rg = max(sum_rg2 / n - mu_rg * mu_rg, 0.0)
    var_yb = max(sum_yb2 / n - mu_yb * mu_yb, 0.0)
    colorfulness = math.sqrt(var_rg + var_yb) + 0.3 * math.sqrt(mu_rg * mu_rg + mu_yb * mu_yb)
    hue_spread = sum(1 for k in hue_bins if k >= HUE_BIN_MIN * n) / float(HUE_BINS)
    dark_ok = bool(mean <= MEAN_MAX and bf <= BRIGHT_MAX and pf <= PALE_MAX)
    vibrant = bool(mean_sat >= SAT_MIN and colorfulness >= COLORFUL_MIN)
    return {
        "meanLuma": round(mean, 4),
        "brightFraction": round(bf, 4),
        "paleFraction": round(pf, 4),
        "maxLuma": round(max_l, 4),
        "meanSat": round(mean_sat, 4),
        "colorfulness": round(colorfulness, 2),
        "hueSpread": round(hue_spread, 4),
        "pass": dark_ok,
        "vibrant": vibrant,
        "width": w,
        "height": h,
    }


def main(argv):
    args = [a for a in argv[1:] if not a.startswith("--")]
    flags = {a for a in argv[1:] if a.startswith("--")}
    if len(args) != 1:
        sys.stderr.write(__doc__)
        return 2
    img = Image.open(args[0])
    w, h = img.size
    if "--full" not in flags and w > 640:
        img = img.crop((0, 0, w // 2, h))   # left eye
    res = measure(img)
    if "--vibrant" in flags:
        res["pass"] = bool(res["pass"] and res["vibrant"])
    res["file"] = args[0]
    print(json.dumps(res))
    return 0 if res["pass"] else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
