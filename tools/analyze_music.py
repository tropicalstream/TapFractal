#!/usr/bin/env python3
"""
analyze_music.py — build-time music envelope analyser for TapFractal.

Turns each bundled track (app/src/main/assets/music/scene<N>.mp3) into a
compact envelope file the app indexes at runtime with
MediaPlayer.currentPosition (no mic, no Visualizer permission):

    app/src/main/assets/music/scene<N>.env.json
    {
      "hopMs":  20,
      "bpm":    <float>,
      "energy": [0..255 int per 20 ms hop],   smoothed RMS loudness
      "bass":   [0..255 int per hop],         20-200 Hz band RMS
      "treble": [0..255 int per hop],         4-12 kHz band RMS
      "onsets": [ms ints, ascending, >= 340 ms apart]
    }

This format is FIXED (producer and consumer both follow it). Nothing else is
written into the file; if the file is missing or malformed the app degrades to
energy 0.5, bass/treble 0.3 and a synthetic 120 BPM beat.

Hop i covers audio time [20*i, 20*i+20) ms; arrays have ceil(duration/20 ms)
entries so the whole track is covered. Each array is normalised so that its
99th percentile maps to 255 (values above are clipped).

--------------------------------------------------------------------------
Method (exact)
--------------------------------------------------------------------------
Decoding: three ffmpeg decodes of the same file to raw f32le mono. Stereo is
down-mixed (L+R)/2 by ffmpeg's aformat/aresample; the filter graphs below run
after the resample, so cut-off frequencies are in absolute Hz.

  A  full band   aformat=channel_layouts=mono,aresample=22050
  B  bass band   A's graph + highpass=f=20,highpass=f=20,lowpass=f=200,lowpass=f=200
  C  treble band aformat=channel_layouts=mono,aresample=44100
                 + highpass=f=4000,highpass=f=4000,lowpass=f=12000,lowpass=f=12000

  Every highpass/lowpass is ffmpeg's RBJ-cookbook biquad with poles=2 and
  Q = 0.707 (Butterworth), computed in f64. Two cascaded sections per edge
  give a 4th-order, 24 dB/octave skirt with -6 dB at the corner frequency.
  The treble band is decoded at 44 100 Hz because 12 kHz is above the 22 050 Hz
  Nyquist limit; its 20 ms hop is therefore 882 samples instead of 441.

energy : RMS over each 441-sample (20 ms) block of A, then a one-pole EMA
         y[i] = y[i-1] + a*(x[i] - y[i-1]) with a = 1 - exp(-20/60) = 0.2835
         (time constant 60 ms). Loudness is deliberately not band-weighted.
bass   : RMS over each 441-sample block of B (no extra smoothing; the app
         applies its own EMA, tau 0.12 s).
treble : RMS over each 882-sample block of C (no extra smoothing).

onsets : SuperFlux-style spectral flux on A. STFT: 1024-sample periodic Hann
         window (46.4 ms), hop 147 samples (6.667 ms = 150 frames/s), frames
         centred (input padded by 512 samples each side), magnitude compressed
         as log(1 + 100*|X|). Flux[n] = sum_k max(0, S[n,k] - max(S[n-1,k-1],
         S[n-1,k], S[n-1,k+1])) — the +-1-bin max filter on the previous frame
         suppresses vibrato/pad shimmer. The flux is scaled so its 99th
         percentile is 1, smoothed by a 3-frame (20 ms) moving average, then a
         frame n is an onset candidate when
             odf[n] == max(odf[n-4 .. n+4])                (local peak, +-27 ms)
             odf[n] >= mean(odf[n-15 .. n+4]) + 0.10      (adaptive: -100..+30 ms)
             odf[n] >= 0.06                               (absolute floor)
         Candidates are accepted strongest-first subject to a 340 ms refractory
         window (|t_i - t_j| >= 340 ms), so at any tempo the app's uBeat pulses
         at most ~3 times per second (photosensitivity gate, DESIGN §6). Onset
         time = frame centre in ms, rounded; the flux of a sharp transient peaks
         while the transient sits on the window's rising slope, so reported
         times run up to ~12 ms late, well inside a 20 ms hop.

bpm    : autocorrelation of the onset-strength curve (the same flux, detrended
         by subtracting a 1 s moving average and half-wave rectifying, then
         mean-removed; unbiased normalised autocorrelation via FFT) over lags
         corresponding to 60-180 BPM at 150 frames/s. Local maxima of the
         autocorrelation are ranked by ac * prior with a mild log-normal prior
         centred on 120 BPM (sigma = 1 octave) to settle half/double-time ties;
         the winning lag is refined by parabolic interpolation and then
         sharpened from the autocorrelation peak at 2, 4, ... 32 beats
         (refine_harmonic), which resolves DAW-quantised tempos to ~0.01 BPM
         (134.87 -> 135.00 on slot 4). The ac value at
         half and double the winner (when inside 60-180) is printed so the
         octave ambiguity is visible. A 4/4 track whose bar pattern repeats
         every two beats (kick / snare) autocorrelates at least as strongly at
         the two-beat lag, so "half-time" readings are expected and are
         accepted by the sanity check (see EXPECTED_BPM).

--------------------------------------------------------------------------
Results for the shipped tracks (2026-09-02, this script, ffmpeg 8.1)
--------------------------------------------------------------------------
  slot  track                             bpm     ac     onsets/s  grid fit  half/double-time note
  ----  --------------------------------  ------  -----  --------  --------  ---------------------------------------
   1    Dream Catcher (Kevin MacLeod)     140.00  0.507    1.88    78/80 %   70.0 BPM (ac 0.484 = 0.96x of the winner)
                                                                            is the felt half-time tempo (catalogue
                                                                            70); 140 is the hi-hat/kick pulse grid.
   2    Ethernight Club (Kevin MacLeod)   117.00  0.534    2.03    38/55 %   unambiguous (catalogue 117). The lower
                                                                            grid fit is syncopation (strongest-first
                                                                            picking keeps off-beat marimba accents),
                                                                            not drift: the phase-per-16 s figure and
                                                                            the 513 ms median gap are on the beat.
   3    Warriors Fall (cdk ft. Darryl J)   87.00  0.748    2.35    55/56 %   174.0 BPM (ac 0.507 = 0.68x) is the
                                                                            two-step drum & bass tempo (page 174);
                                                                            87 = snare backbeat, wins outright. 99 %
                                                                            of the onsets sit on the 174 grid.
   4    The Beach (Platinum Butterfly)    135.00  0.754    1.59    56/63 %   67.5 BPM (ac 0.785 = 1.04x) is the
                                                                            two-beat bar pattern; the prior keeps 135
                                                                            (artist: 135).
  bpm = coarse autocorrelation winner sharpened at the 32-beat multiple
  (140.003 / 116.998 / 87.001 / 135.002 before rounding to 2 decimals).
  grid fit = onsets within +-40 ms of the file's bpm grid, one phase for the
  whole track / one phase per 16 s.
  Sanity: all four PASS (bpm within 4 % of the catalogue value or its octave;
  onset density inside 0.5-3/s). Whole run ~8 s on an Apple-silicon Mac.
  The same table, with the fit rationale, is in docs/MUSIC.md.

--------------------------------------------------------------------------
Usage
--------------------------------------------------------------------------
  tools/.venv/bin/python tools/analyze_music.py --all
      analyse app/src/main/assets/music/scene1..4.mp3 -> scene<N>.env.json
  tools/.venv/bin/python tools/analyze_music.py path/to/slot3.mp3 [--out-dir DIR]
      the trailing digit of the input's basename selects the slot N
  tools/.venv/bin/python tools/analyze_music.py --check app/src/main/assets/music/scene3.env.json
      validate an envelope file against the fixed format
  --expect BPM  override the built-in expected BPM for the sanity check
  --strict      exit 1 when any sanity check warns

Dependencies: numpy (tools/.venv); ffmpeg + ffprobe on PATH.
"""

import argparse
import json
import math
import os
import re
import shutil
import subprocess
import sys

import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSET_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "music")

# ---------------------------------------------------------------- constants
HOP_MS = 20
SR = 22050                      # analysis rate for energy / bass / onsets / bpm
SR_TREBLE = 44100               # treble band: Nyquist must exceed 12 kHz
HOP = SR * HOP_MS // 1000       # 441 samples
HOP_T = SR_TREBLE * HOP_MS // 1000  # 882 samples
ENERGY_TAU_MS = 60.0
ENERGY_ALPHA = 1.0 - math.exp(-HOP_MS / ENERGY_TAU_MS)   # 0.2835

BASS_FILTERS = ["highpass=f=20", "highpass=f=20", "lowpass=f=200", "lowpass=f=200"]
TREBLE_FILTERS = ["highpass=f=4000", "highpass=f=4000", "lowpass=f=12000", "lowpass=f=12000"]
BIQUAD_OPTS = ":p=2:w=0.707:t=q:precision=f64"

ODF_WIN = 1024                  # 46.4 ms Hann
ODF_HOP = 147                   # 6.667 ms -> 150 frames/s
ODF_FPS = SR / ODF_HOP
ODF_GAMMA = 100.0
ODF_SMOOTH = 3                  # frames (20 ms)
PEAK_HALF = 4                   # local-max half window (+-27 ms)
THR_PRE_MS, THR_POST_MS = 100.0, 30.0
THR_DELTA = 0.10                # above the local mean (odf scaled to P99 = 1)
THR_FLOOR = 0.06                # absolute floor
REFRACTORY_MS = 340

BPM_MIN, BPM_MAX = 60.0, 180.0
BPM_PRIOR_CENTRE = 120.0
BPM_PRIOR_SIGMA_OCT = 1.0
DETREND_S = 1.0

NORM_PERCENTILE = 99.0

# Expected tempo per slot, from the scout's manifest (catalogue / artist values).
# A measurement within +-4 % of the value, or of its half or double, passes.
EXPECTED_BPM = {
    1: (70.0, "Dream Catcher: catalogue 70 (half-time trap; 140 pulse)"),
    2: (117.0, "Ethernight Club: catalogue 117"),
    3: (174.0, "Warriors Fall: 174 two-step dnb = 87 half-time"),
    4: (135.0, "The Beach: artist states 135"),
}
ONSET_DENSITY_RANGE = (0.5, 3.0)   # onsets per second on beat-driven tracks


# ---------------------------------------------------------------- decoding
def _require_ffmpeg():
    for tool in ("ffmpeg", "ffprobe"):
        if shutil.which(tool) is None:
            sys.exit(f"error: {tool} not found on PATH")


def decode(path, sr, filters=()):
    """Decode `path` to mono f32 at `sr` Hz through the given ffmpeg filter list."""
    chain = ["aformat=channel_layouts=mono", f"aresample={sr}"]
    chain += [f + BIQUAD_OPTS for f in filters]
    cmd = ["ffmpeg", "-v", "error", "-nostdin", "-i", path,
           "-af", ",".join(chain), "-f", "f32le", "-"]
    r = subprocess.run(cmd, capture_output=True)
    if r.returncode != 0:
        raise RuntimeError("ffmpeg failed: " + r.stderr.decode("utf-8", "replace").strip())
    x = np.frombuffer(r.stdout, dtype=np.float32)
    if len(x) < sr:  # < 1 s of audio: not a usable track
        raise RuntimeError(f"decoded only {len(x)} samples from {path}")
    return x


def probe_duration(path):
    r = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration",
                        "-of", "default=nw=1:nk=1", path], capture_output=True, text=True)
    try:
        return float(r.stdout.strip())
    except ValueError:
        return float("nan")


# ---------------------------------------------------------------- helpers
def block_rms(x, hop, n_hops):
    """RMS over consecutive non-overlapping blocks of `hop` samples (zero-padded)."""
    need = n_hops * hop
    if len(x) < need:
        x = np.concatenate([x, np.zeros(need - len(x), dtype=x.dtype)])
    blocks = x[:need].astype(np.float64).reshape(n_hops, hop)
    return np.sqrt(np.mean(blocks * blocks, axis=1))


def ema(v, alpha):
    out = np.empty(len(v), dtype=np.float64)
    acc = 0.0
    for i in range(len(v)):
        acc += alpha * (float(v[i]) - acc)
        out[i] = acc
    return out


def sliding_mean(v, pre, post):
    """mean(v[i-pre .. i+post]) for every i, edge-padded."""
    n = len(v)
    vp = np.pad(v, (pre, post), mode="edge")
    c = np.concatenate([[0.0], np.cumsum(vp, dtype=np.float64)])
    L = pre + post + 1
    return (c[L:L + n] - c[:n]) / L


def sliding_max(v, half):
    vp = np.pad(v, (half, half), mode="edge")
    return np.lib.stride_tricks.sliding_window_view(vp, 2 * half + 1).max(axis=1)


def to_u8(v):
    """Scale so the 99th percentile -> 255, clip, round to int."""
    ref = float(np.percentile(v, NORM_PERCENTILE))
    if ref <= 0.0:
        return np.zeros(len(v), dtype=np.int64), ref
    return np.clip(np.rint(v / ref * 255.0), 0, 255).astype(np.int64), ref


# ---------------------------------------------------------------- onset strength
def spectral_flux(x):
    """SuperFlux-style onset strength at ODF_FPS frames/s (see module docstring)."""
    w = np.hanning(ODF_WIN + 1)[:-1].astype(np.float32)          # periodic Hann
    xp = np.pad(x, (ODF_WIN // 2, ODF_WIN // 2))
    n_frames = 1 + (len(xp) - ODF_WIN) // ODF_HOP
    flux = np.zeros(n_frames, dtype=np.float64)
    prev = None
    CH = 2048
    offs = np.arange(ODF_WIN)[None, :]
    for start in range(0, n_frames, CH):
        stop = min(n_frames, start + CH)
        idx = np.arange(start, stop)[:, None] * ODF_HOP + offs
        S = np.log1p(ODF_GAMMA * np.abs(np.fft.rfft(xp[idx] * w, axis=1)))
        if prev is None:
            prev = S[0]
        Sprev = np.vstack([prev[None, :], S[:-1]])
        Spad = np.pad(Sprev, ((0, 0), (1, 1)), mode="edge")
        Smax = np.maximum(np.maximum(Spad[:, :-2], Spad[:, 1:-1]), Spad[:, 2:])
        flux[start:stop] = np.maximum(S - Smax, 0.0).sum(axis=1)
        prev = S[-1]
    flux[0] = 0.0
    return flux


def pick_onsets(flux):
    """Adaptive-threshold peak picking + 340 ms refractory. Returns frame indices."""
    o = flux / (np.percentile(flux, 99) + 1e-12)
    o = sliding_mean(o, ODF_SMOOTH // 2, ODF_SMOOTH - ODF_SMOOTH // 2 - 1)
    pre = int(round(THR_PRE_MS / 1000 * ODF_FPS))
    post = int(round(THR_POST_MS / 1000 * ODF_FPS))
    thr = sliding_mean(o, pre, post) + THR_DELTA
    is_peak = o >= sliding_max(o, PEAK_HALF)
    cand = np.where(is_peak & (o >= thr) & (o >= THR_FLOOR))[0]
    # strongest first, then enforce the refractory window
    order = cand[np.argsort(-o[cand], kind="stable")]
    r = int(round(REFRACTORY_MS / 1000 * ODF_FPS))          # 51 frames = 340.0 ms
    taken = np.zeros(len(o), dtype=bool)
    accepted = []
    for n in order:
        if taken[n]:
            continue
        accepted.append(int(n))
        taken[max(0, n - r + 1):n + r] = True
    accepted.sort()
    return accepted, o


def frames_to_ms(frames):
    ms = [int(round(n * 1000.0 / ODF_FPS)) for n in frames]
    # belt and braces: the refractory window guarantees this already
    out = []
    for t in ms:
        if not out or t - out[-1] >= REFRACTORY_MS:
            out.append(t)
    return out


# ---------------------------------------------------------------- tempo
def refine_harmonic(ac, lag, ac_ref):
    """Sharpen a beat period using the autocorrelation peak at 2, 4, ... 32 beats.

    Parabolic interpolation resolves a peak to ~0.1 frame; at m beats the same
    error is m times smaller in the per-beat period (0.1 BPM -> 0.003 BPM at
    m = 32 for a 135 BPM track). Each doubling searches +-4 frames around the
    predicted lag (the accumulated error is < 1 frame), requires the peak to be
    interior and at least 0.3x the coarse peak, and stops at the first failure
    (tempo changes, long intros) keeping the last good estimate.
    Returns (lag_frames, multiple_used)."""
    used = 1
    for m in (2, 4, 8, 16, 32):
        centre = lag * m
        lo, hi = int(math.floor(centre)) - 4, int(math.ceil(centre)) + 4
        if lo < 1 or hi + 1 >= len(ac):
            break
        i = int(np.argmax(ac[lo:hi + 1])) + lo
        if i <= lo or i >= hi or ac[i] < 0.3 * ac_ref:
            break
        y0, y1, y2 = ac[i - 1], ac[i], ac[i + 1]
        den = y0 - 2.0 * y1 + y2
        d = 0.5 * (y0 - y2) / den if abs(den) > 1e-12 else 0.0
        lag = (i + max(-0.5, min(0.5, d))) / m
        used = m
    return lag, used


def grid_fit(onsets_ms, bpm, tol_ms=40.0, window_s=None):
    """Fraction of onsets within +-tol of a bpm grid, phase chosen to maximise it.
    window_s = None: one phase for the whole track; else the phase is re-chosen
    per window (separates tempo drift from off-beat onsets)."""
    if len(onsets_ms) < 2 or bpm <= 0:
        return 0.0
    on = np.asarray(onsets_ms, dtype=np.float64)
    P = 60000.0 / bpm
    phases = np.arange(0.0, P, 4.0)

    def best(seg):
        r = np.abs(((seg[None, :] - phases[:, None] + P / 2) % P) - P / 2)
        return float((r <= tol_ms).mean(axis=1).max())

    if window_s is None:
        return best(on)
    hits = 0.0
    for start in np.arange(0.0, on[-1] + 1.0, window_s * 1000.0):
        seg = on[(on >= start) & (on < start + window_s * 1000.0)]
        if len(seg):
            hits += best(seg) * len(seg)
    return hits / len(on)


def estimate_bpm(flux):
    """Autocorrelation tempo estimate in [BPM_MIN, BPM_MAX]; returns (bpm, report)."""
    k = int(round(DETREND_S * ODF_FPS))
    o = flux - sliding_mean(flux, k // 2, k - k // 2 - 1)
    o = np.maximum(o, 0.0)
    o = o - o.mean()
    n = len(o)
    nfft = 1 << (2 * n - 1).bit_length()
    F = np.fft.rfft(o, nfft)
    ac = np.fft.irfft(F * np.conj(F), nfft)[:n]
    ac = ac / (n - np.arange(n))                     # unbiased
    ac = ac / (ac[0] if ac[0] > 0 else 1.0)

    lag_min = int(math.floor(60.0 * ODF_FPS / BPM_MAX))
    lag_max = int(math.ceil(60.0 * ODF_FPS / BPM_MIN))
    lags = np.arange(lag_min - 1, lag_max + 2)       # one extra each side for peak tests
    a = ac[lags]
    bpms = 60.0 * ODF_FPS / lags
    prior = np.exp(-0.5 * (np.log2(bpms / BPM_PRIOR_CENTRE) / BPM_PRIOR_SIGMA_OCT) ** 2)
    score = a * prior

    peaks = [i for i in range(1, len(a) - 1)
             if a[i] >= a[i - 1] and a[i] >= a[i + 1] and BPM_MIN <= bpms[i] <= BPM_MAX]
    if not peaks:
        return 120.0, {"peaks": [], "note": "no autocorrelation peak in range; fallback 120"}

    def refine(i):
        y0, y1, y2 = a[i - 1], a[i], a[i + 1]
        den = y0 - 2.0 * y1 + y2
        d = 0.5 * (y0 - y2) / den if abs(den) > 1e-12 else 0.0
        d = max(-0.5, min(0.5, d))
        return 60.0 * ODF_FPS / (lags[i] + d)

    ranked = sorted(peaks, key=lambda i: -score[i])
    best = ranked[0]
    bpm_coarse = refine(best)
    lag_fine, m_used = refine_harmonic(ac, 60.0 * ODF_FPS / bpm_coarse, float(a[best]))
    bpm = 60.0 * ODF_FPS / lag_fine
    report = {
        "bpm": bpm,
        "bpmCoarse": bpm_coarse,
        "harmonic": m_used,
        "ac": float(a[best]),
        "peaks": [(refine(i), float(a[i]), float(score[i])) for i in ranked[:5]],
        "octave": [],
    }
    for factor, name in ((0.5, "half-time"), (2.0, "double-time")):
        target = bpm * factor
        if not (BPM_MIN <= target <= BPM_MAX):
            continue
        near = [i for i in peaks if abs(refine(i) / target - 1.0) <= 0.05]
        if near:
            j = max(near, key=lambda i: a[i])
            report["octave"].append((name, refine(j), float(a[j]), float(a[j]) / max(float(a[best]), 1e-12)))
        else:
            report["octave"].append((name, target, float(ac[int(round(60.0 * ODF_FPS / target))]), None))
    return float(bpm), report


# ---------------------------------------------------------------- envelope
def analyse(path):
    full = decode(path, SR)
    bass = decode(path, SR, BASS_FILTERS)
    treb = decode(path, SR_TREBLE, TREBLE_FILTERS)
    n_hops = int(math.ceil(len(full) / HOP))

    energy_raw = ema(block_rms(full, HOP, n_hops), ENERGY_ALPHA)
    bass_raw = block_rms(bass, HOP, n_hops)
    treb_raw = block_rms(treb, HOP_T, n_hops)

    energy, e_ref = to_u8(energy_raw)
    bass_u8, b_ref = to_u8(bass_raw)
    treb_u8, t_ref = to_u8(treb_raw)

    flux = spectral_flux(full)
    onset_frames, odf = pick_onsets(flux)
    onsets = frames_to_ms(onset_frames)
    bpm, bpm_report = estimate_bpm(flux)

    dur = len(full) / SR
    stats = {
        "durationSec": dur,
        "hops": n_hops,
        "p99": {"energy": e_ref, "bass": b_ref, "treble": t_ref},
        "mean": {"energy": float(energy.mean()), "bass": float(bass_u8.mean()), "treble": float(treb_u8.mean())},
        "onsetCount": len(onsets),
        "onsetsPerSec": len(onsets) / dur if dur > 0 else 0.0,
        "medianOnsetGapMs": float(np.median(np.diff(onsets))) if len(onsets) > 1 else float("nan"),
        "gridFitGlobal": grid_fit(onsets, bpm),
        "gridFitLocal": grid_fit(onsets, bpm, window_s=16.0),
        "bpm": bpm_report,
    }
    env = {"hopMs": HOP_MS, "bpm": round(bpm, 2), "energy": energy.tolist(),
           "bass": bass_u8.tolist(), "treble": treb_u8.tolist(), "onsets": onsets}
    return env, stats


def write_env(env, path):
    """Compact JSON, one array per line (still one JSON document)."""
    def arr(v):
        return "[" + ",".join(str(int(i)) for i in v) + "]"
    with open(path, "w", encoding="utf-8") as f:
        f.write('{"hopMs":%d,"bpm":%s,\n' % (env["hopMs"], json.dumps(env["bpm"])))
        f.write('"energy":' + arr(env["energy"]) + ",\n")
        f.write('"bass":' + arr(env["bass"]) + ",\n")
        f.write('"treble":' + arr(env["treble"]) + ",\n")
        f.write('"onsets":' + arr(env["onsets"]) + "}\n")
    with open(path, "r", encoding="utf-8") as f:
        json.load(f)  # must round-trip


def check_env(path, duration_sec=None):
    """Validate an envelope file against the fixed format. Returns a list of problems."""
    problems = []
    try:
        with open(path, "r", encoding="utf-8") as f:
            d = json.load(f)
    except Exception as e:  # noqa: BLE001
        return [f"not valid JSON: {e}"]
    keys = ["hopMs", "bpm", "energy", "bass", "treble", "onsets"]
    for k in keys:
        if k not in d:
            problems.append(f"missing key {k}")
    if problems:
        return problems
    if set(d.keys()) != set(keys):
        problems.append(f"unexpected keys {sorted(set(d.keys()) - set(keys))}")
    if d["hopMs"] != HOP_MS:
        problems.append(f"hopMs {d['hopMs']} != {HOP_MS}")
    if not isinstance(d["bpm"], (int, float)) or not (BPM_MIN <= d["bpm"] <= BPM_MAX):
        problems.append(f"bpm {d['bpm']} outside {BPM_MIN}-{BPM_MAX}")
    lens = {k: len(d[k]) for k in ("energy", "bass", "treble")}
    if len(set(lens.values())) != 1:
        problems.append(f"array lengths differ {lens}")
    for k in ("energy", "bass", "treble"):
        v = d[k]
        if not v:
            problems.append(f"{k} empty")
            continue
        if any((not isinstance(i, int)) or i < 0 or i > 255 for i in v):
            problems.append(f"{k} has values outside 0..255 or non-int")
        if max(v) != 255:
            problems.append(f"{k} max is {max(v)}, expected 255 (P99 normalisation)")
    on = d["onsets"]
    if any(not isinstance(t, int) or t < 0 for t in on):
        problems.append("onsets must be non-negative ints")
    if any(b - a < REFRACTORY_MS for a, b in zip(on, on[1:])):
        problems.append(f"onsets closer than {REFRACTORY_MS} ms")
    if any(b <= a for a, b in zip(on, on[1:])):
        problems.append("onsets not strictly ascending")
    n = lens["energy"]
    if on and on[-1] > n * HOP_MS:
        problems.append("onset beyond the end of the envelope")
    if duration_sec is not None and abs(n * HOP_MS / 1000.0 - duration_sec) > 0.5:
        problems.append(f"envelope covers {n * HOP_MS / 1000.0:.2f} s, track is {duration_sec:.2f} s")
    return problems


# ---------------------------------------------------------------- reporting
def sanity(slot, stats, expect_override=None):
    """Return (ok, lines) for the bpm / onset-density sanity checks."""
    lines, ok = [], True
    bpm = stats["bpm"].get("bpm", 120.0)
    exp = expect_override
    note = ""
    if exp is None and slot in EXPECTED_BPM:
        exp, note = EXPECTED_BPM[slot]
    if exp:
        rel = {"1x": bpm / exp, "2x": bpm / (2 * exp), "0.5x": bpm / (0.5 * exp)}
        match = [k for k, r in rel.items() if abs(r - 1.0) <= 0.04]
        if match:
            tag = "PASS" if "1x" in match else "PASS (octave %s)" % match[0]
            lines.append(f"  bpm {bpm:6.2f} vs expected {exp:g}: {tag}  {note}")
        else:
            ok = False
            lines.append(f"  bpm {bpm:6.2f} vs expected {exp:g}: WARN (no 1x/2x/0.5x match)  {note}")
    dens = stats["onsetsPerSec"]
    lo, hi = ONSET_DENSITY_RANGE
    if lo <= dens <= hi:
        lines.append(f"  onset density {dens:.2f}/s: PASS (range {lo}-{hi})")
    else:
        ok = False
        lines.append(f"  onset density {dens:.2f}/s: WARN (range {lo}-{hi})")
    return ok, lines


def describe(slot, src, out, env, stats):
    r = stats["bpm"]
    print(f"[scene{slot}] {os.path.basename(src)} -> {os.path.relpath(out, ROOT)}")
    print(f"  duration {stats['durationSec']:.2f} s, {stats['hops']} hops of {HOP_MS} ms")
    print(f"  P99 raw: energy {stats['p99']['energy']:.4f}  bass {stats['p99']['bass']:.4f}  treble {stats['p99']['treble']:.4f}")
    print(f"  mean/255: energy {stats['mean']['energy']:.0f}  bass {stats['mean']['bass']:.0f}  treble {stats['mean']['treble']:.0f}")
    print(f"  onsets {stats['onsetCount']} ({stats['onsetsPerSec']:.2f}/s, median gap {stats['medianOnsetGapMs']:.0f} ms)")
    if r.get("peaks"):
        pk = "  ".join(f"{b:6.1f} (ac {a:.3f}, score {s:.3f})" for b, a, s in r["peaks"])
        print(f"  bpm {r['bpm']:.3f} (coarse {r['bpmCoarse']:.2f}, refined at {r['harmonic']} beats)  ac {r['ac']:.3f}")
        print(f"  candidates: {pk}")
        print(f"  onsets on the bpm grid (+-40 ms): {100 * stats['gridFitGlobal']:.0f} % with one phase, "
              f"{100 * stats['gridFitLocal']:.0f} % with a phase per 16 s")
        for name, b, a, ratio in r["octave"]:
            rs = f"{ratio:.2f}x of winner" if ratio is not None else "no distinct peak"
            print(f"    {name:11s} {b:6.1f} BPM  ac {a:.3f}  ({rs})")
    else:
        print(f"  bpm fallback: {r.get('note')}")


# ---------------------------------------------------------------- main
def slot_of(path):
    m = re.search(r"(\d)\D*$", os.path.splitext(os.path.basename(path))[0])
    return int(m.group(1)) if m else None


def main(argv=None):
    ap = argparse.ArgumentParser(description="TapFractal music envelope analyser")
    ap.add_argument("inputs", nargs="*", help="mp3 files (basename must end in the slot digit)")
    ap.add_argument("--all", action="store_true", help=f"analyse scene1..4.mp3 in {os.path.relpath(ASSET_DIR, ROOT)}")
    ap.add_argument("--out-dir", default=None, help="where scene<N>.env.json is written (default: next to the input)")
    ap.add_argument("--expect", type=float, default=None, help="expected BPM for the sanity check (single input)")
    ap.add_argument("--check", nargs="+", metavar="ENV_JSON", help="validate envelope files and exit")
    ap.add_argument("--strict", action="store_true", help="exit 1 if any sanity check warns")
    args = ap.parse_args(argv)

    if args.check:
        bad = 0
        for p in args.check:
            probs = check_env(p)
            print(f"{p}: {'OK' if not probs else 'PROBLEMS'}")
            for q in probs:
                print("   -", q)
            bad += bool(probs)
        return 1 if bad else 0

    _require_ffmpeg()
    inputs = list(args.inputs)
    if args.all:
        inputs += [os.path.join(ASSET_DIR, f"scene{n}.mp3") for n in (1, 2, 3, 4)]
    if not inputs:
        ap.error("no inputs (give mp3 paths or --all)")

    warned = False
    summary = []
    for src in inputs:
        src = os.path.abspath(src)
        if not os.path.isfile(src):
            print(f"error: missing {src}", file=sys.stderr)
            warned = True
            continue
        slot = slot_of(src)
        if slot is None:
            print(f"error: cannot derive the slot digit from {os.path.basename(src)}", file=sys.stderr)
            warned = True
            continue
        out_dir = args.out_dir or os.path.dirname(src)
        os.makedirs(out_dir, exist_ok=True)
        out = os.path.join(out_dir, f"scene{slot}.env.json")

        env, stats = analyse(src)
        write_env(env, out)
        describe(slot, src, out, env, stats)
        ok, lines = sanity(slot, stats, args.expect if len(inputs) == 1 else None)
        print("\n".join(lines))
        probs = check_env(out, probe_duration(src))
        if probs:
            ok = False
            for q in probs:
                print("  format WARN:", q)
        else:
            print(f"  format check: OK ({os.path.getsize(out) // 1024} KiB)")
        warned |= not ok
        summary.append((slot, os.path.basename(src), stats["bpm"].get("bpm", float("nan")),
                        stats["onsetsPerSec"], stats["durationSec"]))
        print()

    if len(summary) > 1:
        print("RESULTS  slot  file         bpm      onsets/s  duration")
        for slot, name, bpm, dens, dur in summary:
            print(f"         {slot}     {name:12s} {bpm:7.2f}  {dens:6.2f}    {dur:7.1f} s")
    return 1 if (args.strict and warned) else 0


if __name__ == "__main__":
    sys.exit(main())
