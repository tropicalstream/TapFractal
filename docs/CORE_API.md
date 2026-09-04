# TapFractal — Core API for scene authors

This is the contract between the core (everything under `com.tapfractal` except the four scene
files) and the four scenes. It describes exactly what the core provides, what it calls, when, and in
which units — plus the places where the implementation had to **deviate** from `DESIGN.md` /
`SHADER_CONTRACT.md` (§7, read it first if you only read one section).

A scene author owns exactly two files and replaces them wholesale:

| Slot | Kotlin (package `com.tapfractal.scenes`) | GLSL | Placeholder today |
|---|---|---|---|
| 1 | `app/src/main/java/com/tapfractal/scenes/Tidepool.kt` (`class Tidepool : Scene`) | `app/src/main/assets/shaders/scene1.frag` | 2D quadratic Julia plane |
| 2 | `scenes/Nave.kt` (`class Nave : Scene`) | `scene2.frag` | raymarched sphere lattice |
| 3 | `scenes/Throat.kt` (`class Throat : Scene`) | `scene3.frag` | sphere lattice |
| 4 | `scenes/Hive.kt` (`class Hive : Scene`) | `scene4.frag` | sphere lattice |

Keep the class names, the constructor call (`Scene(slot, "Tidepool", "shaders/scene1.frag", "music/scene1.mp3")`)
and the file names — `Game` instantiates `listOf(Tidepool(), Nave(), Throat(), Hive())` and the pipeline
loads `fragAsset` by name. Everything else inside the file is yours. Each placeholder is self-contained
(no shared helpers) precisely so you can delete it.

---

## 1. Coordinate frame and units

* World: **x right, y up (gravity-true), z forward at the last recentre**. `cross(right, up) = forward`,
  `det(uCamRot) = +1`. 1 world unit ≈ 1 m of feel.
* `uCamRot` columns = (right, up, forward); the CPU `Mat3` is column-major (`m[col*3+row]`),
  `Mat3.mul(v)` = M·v, `Mat3.mulT(v)` = Mᵀ·v (world → camera).
* `uFov = 2·tan(halfVerticalFov) = 0.35` (CONTRACT C1). `eyeUV()` spans `uv.y ∈ [−0.5, 0.5]`,
  `uv.x ∈ ±0.5·aspect` (aspect = 4/3 at every rung).
* Convergence plane 1.4 u (the anchor). Stereo half-interaxial `s`: Off 0 · Subtle 0.004 · Deep 0.008
  (Deep follows the sluurp distance, clamped 0.7–1.4). Rung ≤ 2 renders mono.
* Time: `uTime` = seconds since the scene became current (resets on every scene switch); `uDt` = frame dt.
* Angles in Kotlin are radians unless the name says `Deg`.

---

## 2. `Scene` base class (`scenes/Scene.kt`, core-owned)

```kotlin
abstract class Scene(val slot: Int, val displayName: String, val fragAsset: String, val musicAsset: String)
```

Read-only identity: `name` (= displayName, menu value), `hudLabel` ("THE TIDEPOOL").

### 2.1 Abstract members you must provide

| Member | Type | Meaning / units |
|---|---|---|
| `is2D` | `Boolean` | true only for the Tidepool. Switches the core to the 2D toy projection (§4) and the pan/dive flight (§3.2). |
| `palette` | `Palette(a, b, c, d0, zshift, coreTint, bloomTint, bloomStrength)` | IQ cosine palette + post tints (DESIGN §8). The core uses `coreTint/bloomTint/bloomStrength` in the post pass and `a,b,c,d0,zshift` to derive the echo tint. Your shader carries its own copy of the constants. |
| `echo` | `EchoParams(softTau, softZoom, softRot, wildTau, wildZoom, wildRot, centre)` | τ in s; zoom/rot are **per-frame amounts at 60 fps** (the core raises them to dt/16.7 ms); centre in eye-uv. |
| `speedScale` | `Float` | multiplier on the global level table HOVER 0 / SLOW 0.30 / CRUISE 1.0 / FAST 1.8 / WARP 3.0 u/s (§11 — rooms should feel like flying, not drifting). |
| `maxRung` | `Int` 0..9 | adaptive-resolution cap. Rung ladder (scale, uQuality): 0(0.30,0.4) 1(0.35,0.5) 2(0.40,0.6) 3(0.45,0.7) 4(0.50,0.8) 5(0.50,1.0) 6(0.55,1.0) 7(0.62,1.0) 8(0.70,1.0) 9(0.75,1.0). |
| `interestConeDeg` | `Float` | half-angle of the interest cone; `uLookAway = smoothstep(cone, cone+20°, angle(gaze, +z))`. 180 → never. |
| `chimeRootHz` | `Float` | root of the hit chime (pentatonic degree by level). |
| `de(p: Vec3, zoom: Float): Float` | | **CPU mirror of the shader DE** at continuous depth `zoom` (= uZoom = hit levels eased + travel dive, §11). Must be a lower bound of the true distance like the shader's; no beat/bass/energy terms (see §5). 2D scene: return a large positive constant. |
| `startPose(): Pose` | `Pose(pos, heading)` | camera position + unit heading on entry. Must be in open space (`de(pos) > skinAt(zoom)`, 0.20 at zoom 0). |
| `autoFlight(t: Float, pos: Vec3): Vec3` | unit heading | used when Head tracking = Off or in attract mode; flown at ≤ 1 u/s. |
| `safeVolume(cam: Pose): Volume` | predicate | `Volume { p -> ... }`; `Volume.ALL`, `Volume.sphere(c, r)` provided. Intersected with the ±25° gaze cone at 1–3 u and `de(p) > 2·r_t + 0.05`. |

### 2.2 Open members with defaults

| Member | Default | Notes |
|---|---|---|
| `current(p, t): Vec3` | zero | current added to the flight; the core clamps to **0.35 u/s** — strong enough to pull the flight into a canyon mouth / gap / vein when the gaze is within ~30° of it; beyond that return zero or a gentle drift (gaze always wins). `zoom` is already written when it is called (§11). |
| `diveRate` | 0 | zoom levels gained per world unit of forward travel (§11). 0 = hits only. |
| `skinAt(zoom): Float` | `max(0.20·0.85^zoom, 0.03)` | flight hover/slide skin (u) at this depth (§11). |
| `ropeScale(zoom): Float` | `0.85^(zoom/2)` | toy scale factor at this depth: target radii and spawn margins (§11). |
| `field(p): Vec3` | zero | extra force on the sluurp in u/s², added to `1.0·uGravity`. Called every 240 Hz substep. |
| `grad(p, zoom): Vec3` | tetrahedron 4-tap of `de`, e = 0.002, unit length | override only with an analytic normal. |
| `onLevelChanged(level)` | no-op | after a hit (+1, golden +2) or a timeout (−1). Good place for the Hive void-finder. |
| `onEnter()` | no-op | called when the scene becomes the incoming side of a crossfade, before `startPose()`. |
| `escapeCount(p: Vec2, depth): Float` (2D) | 8 | smooth escape count at plane point p; ≥ `maxIter(depth)` means interior. |
| `maxIter(depth): Float` (2D) | 96 | the N of your shader at this depth. |
| `planeScale(depth): Float` (2D) | `1.6·0.72^depth` | plane half-height; must match the shader. |

### 2.3 Fields the core writes every frame (read-only for you)

`zoom` (continuous uZoom = hit levels eased + travel dive, §11 — written BEFORE every de()/field()/current()
call of the frame), `time` (uTime), `level` (integer HIT level — the target of the hit ease; the dive is
not counted; drives target count / rest length / chime degree). `de(p)` / `grad(p)` without a zoom argument
use `zoom`. If your DE depends on time (Throat spin) read `time`. **Never derive shader parameters from
`level`** — the shader gets `uZoom`, and with the dive the two differ by up to several levels.

### 2.4 When the core calls what

| Call | Thread | Frequency |
|---|---|---|
| `de(p, zoom)` | GL | flight: ~7/frame (ahead, here, push-out + 4-tap grad). Toy: 1/substep (240 Hz) + a 4-tap grad on contact. Spawning: ≤ 40 per spawn attempt. Budget ≈ 1 µs each; ~20 µs/frame total. |
| `field(p)` | GL | 1/substep (4–8 per frame). |
| `current(p, t)` | GL | 1/frame (3D only). |
| `autoFlight(t, pos)` | GL | 1/frame while auto-flying. |
| `safeVolume(cam)` | GL | once per spawn attempt. |
| `escapeCount` / `maxIter` / `planeScale` (2D) | GL | dive gate 1/frame, sluurp plane bounce 1/substep, spawn rejection. |
| `startPose()`, `onEnter()` | GL | scene switch. |
| `onLevelChanged(level)` | GL | hit / timeout. |

All calls are on the GL thread, so no locking is needed; never block.

---

## 3. What the core does with your numbers

### 3.1 Flight (3D rooms) — `engine/Flight.kt`
DESIGN §3.2 with the skin from `skinAt(zoom)` (= `k`) and every term smoothed (the 2026-09-03 motion pass):
heading eases to the gaze (τ 0.30 s); `want = LEVEL[level]·speedScale`;
`slowRaw = clamp(de(pos + heading·1.75k)/(3k)) · clamp((de(pos) − 0.6k)/k)` (identical to the designed
0.35/0.60/0.12/0.20 at k = 0.20), **EMA τ 150 ms** → hover factor; speed eases (τ 0.40 s);
the skin is held by a **critically damped push** along `grad` (ω = 18 rad/s) instead of a per-frame clamp
(only an intrusion below ¼ k is snapped, as a safety net); `current` clamped to 0.35 u/s; the resulting
velocity (flight + current + push) is **acceleration-capped at 4 u/s²**; `pos += vel·dt`.
`uSpeed` = the nominal speed (u/s), `uTravel` = net forward distance (∫ max(0, vel·heading) dt — a camera
pinned against a wall does not travel). Auto-flight (tracking Off / attract) is capped at 1 u/s and goes
through the same acceleration cap, so the ring pursuit's heading changes no longer kick.

### 3.2 Flight (2D room)
Pan velocity = `PAN_GAIN[level] · planeScale(zoom) · soft(yaw, pitch)` (`soft = 20°·tanh(a/20°)`, dead-zone 1.5°)
plus the structure nudge of §11; the dive is the §11 travel dive: forward "travel" = `LEVEL[level] · speedScale · gate`
per second, `gate = 0.3` inside the set, `clamp((escapeCount − 3)/6)` outside (EMA 150 ms), and `diveRate · travel`
is folded into uZoom (no cap below the room's own depth clamp). `uCamPos = (pan.x, pan.y, planeRot + headRoll)`;
`uSpeed = 0`; `uTravel` = the gated dive distance (u). Depth for your shader = `uZoom` alone.

### 3.3 Toy — `engine/Toy.kt`
Anchor `A_local = (0, 0.15, 1.4)` in the head frame; rest length `min(0.34, 0.23·(1 + 0.03·level)) · ropeScale(zoom)`;
K 320, C 9, r_b `0.045 · ropeScale(zoom)` (never below 0.018 — `Toy.R_B_MIN`; the bounce, the hit test, the bounds
and the drawn radius all use the scaled `Toy.rB`; fixer 2026-09-03, §11 — verified in the Tidepool dive on the table: the sluurp
disc at zoom 1.10 vs 4.81 shrinks by ≈ 0.7, the expected 0.85^((4.81−1.10)/2) = 0.73); head bubble 0.55; tangential cap 6 u/s; fractal bounce: when `de(sluurp) < r_b`
the sluurp is pushed out along `grad` and reflected with restitution 0.6 (`onBounce` → the "bong" cue, per slot).
2D: the sluurp's plane point (through the centre camera, same mapping as your shader) entering the interior
(`escapeCount ≥ maxIter`) reflects the in-plane velocity about the direction back to the last exterior point.

### 3.4 Targets — `engine/Targets.kt`
Radius `max(0.055, 0.08·0.97^level)`; count `clamp(2 + level/3, 2, 4)` (6 in attract) + golden slot 5 every
5th hit. Spawn: uniform direction in the ±25° gaze cone; distance 55 % in `[1.0, 1.4 + L0]`, 45 % in
`(1.4 + L0, 3.0]` (80/20 at levels 0–1); rejected if `de(p, zoom) < 2·r + 0.05`, outside `safeVolume`,
or within 15° / 0.5 u of another target; 40 attempts, then a fallback along the gaze at 1.3–1.7 u.
2D: the plane point must be exterior with escape count in `[4, maxIter)`.

---

## 4. Shader side

Your `sceneN.frag` is compiled as `common.glsl + "#line 1 1" + sceneN.frag + "#line 1 2" + footer.glsl`
(GLSL ES 3.00; compile errors are logged in full with `1:<line>` = your file's line numbers, and the
built-in error scene is used instead so the app never black-screens). Define **only**
`vec3 render(vec2 uv)` plus your helpers; do not declare outputs or `main`.

Every uniform and helper of `SHADER_CONTRACT.md` Part A and Part B is declared in `common.glsl`
with the documented semantics, plus two core additions you may ignore:

* `uniform int uIs2D;` — 1 when the toy uniforms are per-eye projected (§4.2).
* `uniform vec4 uRopeGroups[3];` — bounding spheres of rope segments 0–3 / 4–7 / 8–10 (used inside `toysDE`).

### 4.1 Uniform upload order (per scene draw, per eye)
Part A: `uEyeRes, uEyeOrigin, uEye, uTime, uDt, uCamPos, uCamRot, uUvShift, uFov, uSluurp, uAnchor, uSluurpGlow,
uRope[12], uRopeN, uTargets[6], uTargetPulse[6], uZoom, uBeat, uEnergy, uBass, uTreble, uHue, uQuality, uFade` →
B.1: `uSluurpVel, uSluurpEye, uRopeTension, uRopeFree, uTargetLife[6], uToyBounds` →
B.2: `uHitPulse, uHitFlash, uTransition, uAttract, uLookAway` →
B.3: `uSpeed, uTravel, uGravity, uHeadRate, uEyeSep` →
B.4: `uPrevDepth` (unit 0), `uPrevCamRotT, uPrevCamPos, uPrevEyeOrigin, uPrevEyeRes` →
core: `uIs2D, uRopeGroups[3]`.
`uFade` is multiplied by 0.35 while the menu is open and set to 0.15 during the photosensitivity notice.
During a crossfade both scenes are drawn (outgoing first, additive blend, `uTransition` −(1−f) / +f).

### 4.2 2D projected toys
For `is2D` scenes the core uploads **per eye**: `uSluurp.xy / uAnchor.xy / uRope[i].xy / uTargets[i].xy` =
eye-uv in the same space as `eyeUV()` (i.e. including `uUvShift`), `.z` = view depth (u; ≤ 0 = behind),
`.w` = projected radius in uv. `toys2D(uv, base)` draws them; `toysEdge(uv)` handles off-screen targets.
The 2D camera for that projection sits at the head (position frozen at the origin, rotation = head).

### 4.3 Depth output
Set `gDepth = t` on a hit (−1 for sky / 2D). `footer.glsl` writes it to the R16F attachment; next frame
`startT()` reads it. Always re-verify (`map(ro + rd·t) > eps` else `t = 0`) as the placeholders do.

### 4.4 Post pass (core-owned, for your information)
Bloom (5-tap separable, half FBO res, threshold 0.55, tinted `bloomTint × bloomStrength`), echo trails
(decaying-max feedback, see §7), radial chromatic aberration (`0.002 + 0.01·E + 0.004·beat + 0.008·hitPulse`,
prism when Echo = Wild), vignette, luminance-Reinhard tone-map with core tint, gamma 1/2.2, then the HUD.

---

## 5. Keeping `de()` and the shader in sync

* Same formula, same per-level parameter tables, same world scale. Use `zoom` continuous exactly as the
  shader uses `uZoom` (a hit eases over 1.2 s, ease-in-out; the travel dive is continuous; the fractal grows
  *into* the player and the flight's skin spring handles it, but only if the Kotlin DE grows at the same rate).
* Drop the music terms (`uBass`, `uBeat`, `uEnergy`, `uHitFlash`) from the mirror — they are visual
  only. If a bass "breath" scales the object by up to 2.5 %, keep the CPU skin (0.20 u) as the margin.
* Time-dependent DEs (Throat spin) use `time`; the core keeps it equal to `uTime`.
* Iteration counts: use the table value, not `uQuality`.
* Validate offline: `cat common.glsl sceneN.frag footer.glsl > /tmp/s.frag && glslangValidator -S frag /tmp/s.frag`
  (installed at `/opt/homebrew/bin/glslangValidator`).

---

## 6. Measured budget on the X3 Pro (Adreno 621) — plan for it

With the sphere-lattice placeholders (≈ 25 ops/DE, ≤ 96 steps, toysDE inside the march):

| Rung (eye px) | Placeholder Throat | Notes |
|---|---|---|
| 2 (256×192) | 60 fps, GPU ~77 % busy | |
| 5 (320×240) | ~47 fps (21 ms) | |
| 8 (448×336) | ~27 fps (37 ms) | |
| Tidepool 2D at 9 | 60 fps | |

Fixed cost of the non-scene passes (echo + bloom + post + HUD at 1280×480) ≈ 3.7 ms as measured with
`noscene` at the governor's idle clock (36 %), i.e. ≈ 1.3–1.5 ms at the full clock. The effective
throughput is closer to **40–50 G ops/s** than the 100 G ops/s assumed in DESIGN §7.4 — budget your
step counts accordingly. `uQuality` already scales step budgets below rung 5 (steps are traded before pixels).
**Stereo doubles the scene cost** from rung 3 up (two eye rects) — the biggest single step of the ladder.
Real GPU time IS available: the generic `GLES30.glBeginQuery/glEndQuery/glGetQueryObjectuiv` pass
`GL_TIME_ELAPSED_EXT` (0x88BF) straight to the driver (`gl/GpuTimer.kt`), and the controller in §10 runs
on it. Beware DVFS: whenever a frame fits its period with room to spare the governor lowers the GPU clock
until it is ~65–70 % busy, so a raw frame time reads ~21 ms at 30 fps at *every* rung; §10 explains the
normalisation. `gpubusy` (`/sys/class/kgsl/kgsl-3d0/gpubusy`) is readable from the adb shell for real
utilisation; the clock nodes are not.

Debug flags (read from settings, any build): `adb shell settings put global tapfractal_debug "rung=5,noecho"` —
`noscene, noecho, nobloom, nopost, nohud, notoys, mono, noapl, nowarp, rung=N`, plus the timed hooks
`pulse=<epoch ms>` (visual-only hit pulse + flash at that wall-clock instant) and `flick=<epoch ms>` (a real
tap) used by `tools/capture.sh`; `settings delete global tapfractal_debug` to clear. Any non-empty value also
shows the "yaw/pitch/roll · rung · fps · ms · apl" HUD line (it is never shown otherwise, so the debug APK ships clean).
Log line every 5 s (tag `TapFractal`, always on):
`frame <ms> / rung <n>[ mono] / <fps> / pacing <60|30> (<k>-slice, present <Hz>, scene <Hz>, timewarp on|off, warp max <°>) / gpu <ms> (max, clock %, true ema/peak ms/vsync, calib) (period max, misses, cpu…, apl <mean> exp <servo>, eye WxH, accel max <u/s²> over-cap <n>, rung changes after settle <n>, scene)`
followed by `cam=… de=… skin=… spd=… zoom=… (hits + dive, travel) sluurp=…`; every room entry, room decision, rung change and stereo recovery is logged with its reason. With any debug flag set, a 1 Hz `motion 1s:` line adds the flight's window maxima (acceleration, safety snaps, hover jumps, current) and `motion SPIKE` lines flag frames over the acceleration cap.

---

## 7. Deviations from DESIGN.md / SHADER_CONTRACT.md (read this)

1. **`uUvShift` sign.** With Part A's `eyeUV() = g + (uUvShift, 0)` and `rayDir(uv·uFov)`, the convergence
   invariant "anchor at geometric uv.x = 0 in both eyes" requires `uUvShift = +s/(D·uFov)` for the LEFT eye
   and `−` for the right — the opposite of CONTRACT C2's "(− left, + right)". `uCamPos` is still
   `camCenter − s·right` for the left eye. Consequently `reprojectPrev()` returns the previous frame's
   **geometric** uv (`dp.xy/(dp.z·uFov) − (uUvShift,0)`, and it uses the full ray including the shift),
   and `startT()` rebuilds the previous direction with `(uvp + uUvShift)`. Scene code is unaffected as long
   as you call them exactly as Part B shows (`uvGeom = uv − vec2(uUvShift, 0)`).
2. **Part A helper prototypes** are written in compilable form in `common.glsl` (`mat3 rotX(float a); mat3 rotY(...)`
   etc.); the uniform block is verbatim with the C1 comment fix.
3. **`uToyBounds` encloses the sluurp + rope only**, not the targets. The contract's early-out
   (`if (db > 0) return db`) (a) made the bounds sphere itself a raymarch hit (distance reaches 0 on its
   surface) and (b) with targets 1–3 u away the sphere covered most of the view, so every step paid the
   19-primitive union (~12 ms at rung 2). Now: outside the padded sphere `toysDE` returns
   `db + TOY_PAD (0.03)` for the sluurp/rope group, the rope is further split into three sub-spheres
   (`uRopeGroups`), and the six target spheres are always tested (cheap). Call sites are unchanged.
4. **Echo feedback is `max(scene, mix·prev·tint)`** (decaying max), not `scene + mix·prev`. The additive
   form is an IIR accumulator that converges to `scene/(1 − mix)` ≈ 7× and produced exactly the pale
   fields the waveguide rules forbid. Trails still fade with τ; the buffer never exceeds its brightest source.
5. **Adaptive resolution is driven by GPU timer queries** (§10), not the period-only controller of
   DESIGN §7.3, and the scene rate is FIXED per room (3D rooms 30 fps + timewarp, Tidepool 60) instead of
   the design's 60↔30 pacing switches. Rung ≤ 2 → mono, as designed; stereo/mono is otherwise decided once
   per room entry (§10.3) — the old mid-room mono lever is gone.
6. **Bloom runs at half FBO resolution** (480×180), sampled bilinearly — softer and cheaper than FBO-res taps.
7. **Head tracking**: the gyro is registered at 200 Hz (5000 µs), the fastest rate allowed without the
   `HIGH_SAMPLING_RATE_SENSORS` permission (`SENSOR_DELAY_FASTEST` crashes on Android 12). The recentre basis
   is built directly from the reference forward (rows r0/u0/f0) rather than Euler matrices; identical result.
   The 0.15° anchor dead-band is not applied (the 20 ms ease is enough). The prediction horizon is one
   vsync's worth (0.5·16.7 + 10 ≈ 18 ms) in every room — the timewarp presents every vsync with a fresh
   basis, so the 27 ms horizon of the old 30 fps pacing is gone. Axis signs are **not yet verified on a
   head** (glasses were driven off-head); `AxisMap.MOUNT_SWAP` exists for the §2.5 self-test.
8. **2D plane bounce** uses the direction back to the last exterior plane point instead of the escape-count
   gradient (the gradient is zero deep inside the set and pushed the sluurp up to the anchor).
9. **Scene shaders compile lazily** (current scene first, then one per frame) so the first frame shows in
   ~0.6 s instead of ~6 s; the first draw of a not-yet-compiled scene stalls once (~1.3 s). The three
   non-current programs are only compiled while a ~1.3 s freeze is invisible — the notice or the menu is
   open, or the head has been still (< 0.15 rad/s) for more than 1 s — so a launch straight into play with
   a moving head no longer stutters through frames 3–5; a room visited before its compile stalls once on the switch.
10. **Not implemented (phase 2 / SHOULD-MAY)**: thermal governor (BURST/CRUISE/COOL, battery temperature),
    the continuous hum, the Nave coarse pre-pass (scene-side anyway),
    per-scene transition specials of DESIGN §9.5 beyond the generic 1 s crossfade (`uTransition` is uploaded;
    scenes may implement their own tilt/fog/ring effects from it), and `zoomParams(level)` (each scene keeps
    its own tables). The APL exposure servo IS implemented (§10.4) — a room's authored brightness is what the
    servo sees at exposure 1.0; anything with a mean above ~0.17 gets pulled down.
11. `credits.json` is read from `assets/music/credits.json` (array of `{title, artist, licence, sourceUrl}` or an
    object with `tracks`/`scenes`/`sceneN` keys); About shows every credit on two lines — `title · artist`
    (20 px) over `licence · source` (15 px, host + path, or host + ISRC for incompetech's query URLs) — never
    elided; an overlong line is drawn smaller (≥ 12 px) instead. An optional `samples: [{title, artist, licence,
    sourceUrl}]` array (ccMixter's "Uses samples from") adds a third 15 px line `samples: "title" · artist · licence · source`.
12. **Input timing** (`input/Gestures.kt`): a single tap is deferred 300 ms and cancelled by a second tap 40–320 ms
    later — that pair *is* the double-tap (the system's `KEYCODE_BACK` that follows is absorbed), because on the X3
    both clicks of a double-tap are delivered as their own KEY UP before the BACK. A lone BACK still opens/closes the
    menu (`input keyevent 4`), and a tap inside the 350 ms double-tap window is the triple-tap (`input keyevent 4 96`).
    A KEY release after a hold ≥ 450 ms, a repeat or a cancelled event (the system quick-settings long-press) is not a tap.
13. The fractal-bounce "bong" fires only for an impact ≥ **0.6 u/s** and at most every **1.0 s** (`Toy.BOUNCE_MIN_V /
    BOUNCE_COOLDOWN`, raised from 0.3 / 0.6 on 2026-09-03: a sluurp dangling against the Throat's lattice with the camera
    parked bonged 25× a minute); the reflection itself still happens every substep, and a sluurp pinned against a wall by
    the rope, gravity or a scene `field()` is silent.
14. **Settings menu has 11 rows** (an `Effects` row — Off / Quiet / Full, default Quiet — sits after Volume; the SFX bus
    is −9 dB at Quiet, hit cues a further −6 dB, Off silences everything including the menu ticks). DESIGN §11's "24 px
    rows, pitch 34" is drawn at **pitch 31** (font sizes unchanged) so the eleventh row clears the footer by 16 px.
15. **SFX in idle cruise (2026-09-03 integration)**: the replacement of an eye the player simply flew past (the silent
    fly-past recycle of §3.4) spawns WITHOUT the bloom cue (at CRUISE that was a bloom every second in every 3D room);
    the timeout "sigh" only plays for a player who flicked in the last 20 s; and no eye spawns within 0.35 u of the sluurp
    (`Targets.SLUURP_CLEAR` — with the head pitched down the gaze ray runs through the hanging yo-yo, and an eye spawned
    onto it was hit without a flick: a chime + whoomp nobody earned). The bloom still announces entry fills, the eye after
    a hit / a timeout and the golden eye. Fallback spawns (nothing clear in the cone) are bent 10° off the gaze ray,
    alternating sides (`Targets.FALLBACK_OFF_DEG`): an eye ON the ray was flown straight through at CRUISE and swelled to
    a half-frame disc in the face every 1.3 s in open space — and there is no fallback at all when a 3D camera is more
    than 3 DE units from any structure (`Targets.VOID_DE`): nothing to hunt in the void, the chevron leads back.
16. **3D dive proximity gate** (`Flight.DIVE_NEAR/FAR` = 0.6 / 2.5 DE units, EMA τ 0.5 s): forward travel counts fully
    toward `diveZoom` within 0.6 of the structure and not at all beyond 2.5 — flying off into the void is not "travelling
    INTO the fractal", so the world stops zooming out there (`uTravel` stays the raw distance; `Flight.diveDist` is the
    gated one). Inside a canyon / the throat / the packing the gate is 1.

17. **A HIT needs a swung or flicked sluurp** (`Toy.HIT_MIN_REL` = 0.6 u/s relative to the head-locked anchor, or free
    flight after a tap). DESIGN §4.4's bare "sluurp–target overlap" let the yo-yo hanging still 1.4 u ahead of a cruising
    head plow through every eye the flight passed: measured 22 unearned hits, 15 whoomps, 30 whiffs and zoom 0 → 24 in
    one minute of CRUISE in the Nave without a single tap (also the SFX flood the owner heard). An eye the resting sluurp
    overlaps is passed through and later recycled by the flight, silently; whiffs need the swing too.
18. **GPU-timer plausibility guard** (`GLRenderer.GPU_IMPLAUSIBLE_MS/N` = 2 vsyncs / 20 frames): one session reported a raw
    GPU time of ~55 ms per vsync at every rung while presenting a steady 60 fps with no misses (calibration 1.15 ms vs the
    0.42 ms reference), and the §10.3 controller sank the Tidepool to rung 0 (192×144) for good; a force-stop cured it.
    A sustained raw time above twice the vsync on frames that presented on time now switches the controller to the
    period-based estimate (logged once) until the readings become plausible again — and (fixer 2026-09-03) calls
    `GpuTimer.resync()`, which deletes and regenerates both query rings inside the current context and restarts the
    calibration, so a stuck query stream cannot outlive the trip. The guard is the fallback; `gl/GpuTimer.kt` itself
    is now correct across contexts and boots: every `init()` (= every EGL context, `contextGen` counts them) regenerates
    the query names and drops every in-flight slot (a name is never reused across contexts); `GL_GPU_DISJOINT_EXT` is read
    ONCE per frame, after that frame's results were collected (reading it clears it — the old per-result reads raced the
    frame and calibration rings), and a disjoint event discards the results collected in that frame, taints everything
    still in flight and restarts the calibration reading; a result above 500 ms is dropped as garbage; the full-clock
    reference is the SECOND-smallest calibration reading of the session (one garbage-low reading cannot poison it); and
    the persisted reference (`files/gpu-calib.txt`, now `v2 <bootTimeMs> <ms>`) is only restored when it was measured in
    the current boot (`System.currentTimeMillis() − SystemClock.elapsedRealtime()` within 5 s) — an older boot's file or a
    v1 file is ignored and the calibration re-runs (logged: `GPU calibration reference … is from a previous boot — ignored`).
    **Measured** (3 consecutive `install -r` + relaunch cycles, Tidepool, glasses on the table): the reference is restored for
    this boot, true ema 3.9–5.8 ms/vsync at rungs 5–9 (raw ~21 ms at the 36 % idle clock), no implausible/disjoint/resync events;
    the four lazy compiles end 10.9 s after `game init`, and with the stall-aware settle window (§10.3) the room climbs
    5 → 6 → 7 → 8 → 9 at +0.5 / +2.0 / +3.5 / +5.0 s after the last compile in every cycle (rung 8 at 14.3 s after launch).
    A 2-minute soak (two passes over the four rooms, flicks, a HOME + resume = new EGL context + a second `GpuTimer.init`)
    logged no shader error, no `GL_INVALID_*`, no `AndroidRuntime`.
---

## 8. Input reference (for testing your scene)

Tap = flick (KEY BUTTON_A/DPAD_CENTER/ENTER/SPACE; `adb shell input keyevent 96`), double-tap = system
`KEYCODE_BACK` = settings menu (deferred 350 ms), a tap inside that window = triple-tap = recentre
(`input keyevent 4 96`). Swipes: one discrete gesture per finger-up, threshold `max(48, 0.09·width)`;
raw horizontal `dx < 0` = FORWARD (next scene), `dx > 0` = BACK; `dy < 0` = UP (speed level +1), `dy > 0` = DOWN.
Injected: `input swipe 800 240 400 240 200` = FORWARD, `input swipe 640 350 640 150 200` = UP.

---

## 9. Integration notes (on-device verification, 2026-09-03)

Found and fixed while verifying the four real scenes on the X3 Pro (see `docs/screens/`):

1. **`Targets.fallback()` spawned targets inside the fractal.** When all 40 cone samples were rejected
   (e.g. hovering against the Nave's bulb, where everything 1–3 u ahead is inside the set) it returned
   `cam + gaze·1.5` unconditionally — a target inside the bulb (`de = −0.18`), invisible and, when the
   sluurp was pinned against the surface, instantly hit. Now it walks the gaze ray from 0.3 u to 3 u, stops
   at the first wall (`de < 0.02`), returns the first point ≥ 0.8 u out with `de > 2r + 0.05` (plane test in
   2D), and returns null (retry in 0.3 s) when nothing is clear.
2. **Nave shading retuned on the waveguide**: the gold haze (`glow`) was a near-uniform full-frame field and
   the base/rim/lantern terms summed several stops over 1.0, which the luminance tone-map flattened into a
   pale yellow-green wash. Haze `exp(−40d)·min(d, 0.05)`, weight 1.3 → 0.4; base `0.15 + 0.85·diff` →
   `0.06 + 0.45·diff`; rim 0.8 → 0.35; lantern 0.4/1.2 → 0.25/0.8. The gold ribs on dark stone are unchanged.
3. **Nave start pose** `(0, 0.3, −3)` → `(−1.5, 0.3, −3)`: with gaze flight the design pose flew the player
   into the bulb within 2 s at CRUISE (camera hovering at the 0.20 u skin, anchor and sluurp inside the set,
   every spawn rejected). From the flank the default gaze glides past the cathedral with ≥ 0.3 u clearance.
4. **Tidepool interior** `0.55×` → `0.35×` (the author's flagged tune item: the interior read as a pale field).
5. Log lines added: `spawn slot=<i> mode=<scripted|sample|fallback> p=… de=…`, `hit slot=<i> …`, and the 5 s
   diagnostic line now lists the active targets `T[i:(pos) r=… pulse=…]`.
6. **Integration round of 2026-09-03 (owner feedback build)** — room numbers touched by the integrator, on-device evidence:
   Tidepool `coreTint` ice (0.85, 0.95, 1.0) → cyan (0.40, 0.90, 1.0): the hot toy cores bloomed white-blue and held the
   room at meanSat 0.54–0.58 (bar 0.60) with the eyes in the frame. Hive `scene4.frag`: lamp reach 1.3 → 1.6, gold-ring
   self-emission floor 0.12 → 0.30, hot-core floor 0.06 → 0.12, magenta Fresnel floor 0.30 → 0.42 — from the void finder's
   open spot (1.6 u from every wall) the room captured at meanLuma 0.004–0.009 (black with toys); offline the gap view
   went from luma 0.012 / colorfulness 35 to 0.031 / 57 with the cave still a dark violet ground under gold arcs.

7. **Hive, fixer round 2026-09-03 ("the Hive leaves the foam")** — `scene4.frag` + `Hive.kt`, no core hook needed:
   (a) *No outside.* The packing's sphere layer sits on the planes y = 2k (cell) and its tallest bubbles reach |y| = s/2;
   the 4 u band between the layers was black sky the flight cruised into (measured on the CPU mirror: 26 % of the cell
   more than 2.5 u from any surface, max 4 u). Both DEs now mirror y with period s after the twist
   (`p.y = |fract(p.y/s + 0.5) − 0.5|·s`, 1-Lipschitz), stacking the layers so the big bubbles kiss their mirror images:
   max clearance 2.0 u anywhere, nothing beyond 2.5 u. `VOID_DE` 0.40 → 0.30 (the start spot is ~1.2 u from the packing,
   well inside lamp reach). (b) *Shader march.* Never-inverted points (scale = 1) use the exact plane distance up to the
   inversion sphere (`max(0.25·|y|, min(|y|, |p| − √s))` — every bubble lives inside that sphere), because the 0.25× bound
   crept along a grazing floor at 7 %/step and a floor 0.5 u below the eye was never hit within the 45-step budget of
   rung 2–3 (a black floor on the device); a soft hit shades a ray whose budget ran out within 0.02·t of a surface.
   **The CPU mirror deliberately keeps the uniform 0.25× bound** (a documented §5 deviation): it is ≤ the shader's DE
   everywhere, so nothing clips, and the navigation needs a bound that scales uniformly — with the tight bound the camera
   hovered 0.09 u over a bare floor and the gap finder's reach probes ran into the 4× drop at the inversion-sphere
   boundary and saw walls in all six directions (parked 30 s). Consequence: the sluurp bounces ≤ 0.13 u above a bare floor.
   (c) *Flight.* `current()` — when pinned (hovering at the skin with nothing open within 0.8 u along the gaze) the ring
   widens 20° → 45° and the pull SLIDES the camera along the surface (the gaze's tangential lean, else the widest ring
   direction), gated to the skin band and by a reach lookahead along the slide (the first cut rammed the camera into a
   crevice narrower than the skin: 15 s of safety snaps at 4–18 u/s²); a pinned camera that produced no pull for 2 s
   leaves along the most open of the ring ∪ 6 axis directions; inside half the skin it escapes the same way at the full
   pull instead of drifting to the void centre; from an open pocket (de ≥ 0.26) a 0.2 u/s drift leads to the nearest
   bubble cluster (the cell centre of the twisted, y-stacked frame). A `hive foam:` log line every 5 s reports de, the
   6-axis clearance, the gaze reach, the height above the layer plane, pinned/escape seconds and the pull.
   (d) *Shading.* Lamp falloff 16d²/R² → 6d²/R² with a 1.4 floor (a wall 1.5 u away is visible, point-blank is not a
   flashbulb); body 0.22 → 0.30 and rim 5 → 3, both × the ring structure `str` (bands + iso-lines of the orbit trap's
   min r², i.e. contact rings around every inversion centre); spec power 80 → 160 × structure (the 80 lobe spread into a
   pale beige blob on a flat floor and sank meanSat); pink-gold glints on the iso-lines; the gold contact ring carries the
   bands and caps at 1.2 (pinned 0.1 u from a contact it filled the frame as one flat yellow field at luma 0.30); faint
   self-lit ring structure everywhere (`base·(0.08 + 0.35·iso)`); bloomTint (1, 0.6, 0.9) → (1, 0.35, 0.9).
   **Measured** (glasses on the table, gaze pitched −20°, head tracking ON, CRUISE, 90 s from the room entry, HUD hidden,
   `apl.py --vibrant` on the left eye at +15/30/45/60/75/90 s): meanLuma 0.067–0.140, pale ≤ 0.02 %, meanSat 0.68–0.86,
   colorfulness 77–122 — all six pass (the pre-fix room read meanLuma 0.005–0.009 / colorfulness 21–33). `hive foam:`
   over the run: de 0.05–0.38, 6-axis clearance 0.12–1.37 u, height above the layer plane 0.20–1.52 u (never outside),
   pinned 0–5 s per 5 s (the pitched gaze keeps the camera sliding along the floor bubbles at the 0.35 u/s pull), zoom
   0.45 → 2.44 by travel alone, 0 acceleration spikes / 0 safety snaps in 91 one-second windows. Cost: the room settles at
   rung 2–3 mono (true ema ~16 ms/vsync at rung 2 in the near-surface views; it was rung 2–3 mono before the round too).
   Screens: `docs/screens/v4b-hive.png` / `v4b-hive-left.png` (HUD on).

Measured on device (debug build, glasses off-head, gaze fixed at +z): Tidepool 60 fps at rung 9;
Nave 43–60 fps at rungs 2–5 (43 fps at rung 3 with the bulb flank filling the frame, 60 fps in open space); Throat 58–60 fps at rung 2 (the
period-driven controller does not climb because of sporadic missed frames); Hive 57–60 fps at rungs 0–3.
No shader compile errors, no GL errors, music plays (MediaPlayer players in `started` state).

---

## 10. Frame pacing, the timewarp, the rung controller and the picture-level tools (core polish, 2026-09-03)

### 10.1 Pacing modes and the rotation-only TIMEWARP
Rendering is driven from a `Choreographer` callback in `MainActivity` (`RENDERMODE_WHEN_DIRTY`) that calls
`requestRender()` on **every vsync**. What a vsync renders depends on the room's fixed slice count
(`RungController.slices`, decided once on entry — there are no 60↔30 flips mid-room):

| Room | Slices | Scene rate | Present rate |
|---|---|---|---|
| Tidepool (2D), the menu, the notice | 1 | 60 fps | 60 fps (no warp needed: the render basis is the present basis) |
| Nave / Throat / Hive | 2 | 30 fps | **60 fps through the timewarp** |

With 2 slices the scene PAIR (both eye rects) is rendered over two vsyncs — the bottom rows of every eye
rect on one vsync, the top rows on the next (a scissor on the eye rects; the tiler skips the other bins), so
each vsync's GPU work fits one 16.7 ms period. The game, physics (240 Hz substeps of the accumulated dt),
flight, targets, echo mix and HUD dirtiness run **once per pair** (slice 0) with dt ≈ 33 ms, and the
uniforms of both slices are identical (`rs` is not touched between them). When the pair completes (its last
slice) the bloom and the echo compose run, the depth/echo reprojection state is remembered, and the pair
becomes the "present descriptor".

The **present pass runs every vsync**: `Pipeline.render` resamples the latest complete pair — hdr and bloom
— through a rotation-only reprojection from the pair's render-time `uCamRot` to the newest head basis
(`Game.presentRot()` = `head.camRot`, sampled every vsync with an 18 ms prediction), using the same math as
`reprojectPrev()` (an embedded `timewarp` program; the eye-rect layout in and out is identical; the strip a fast turn
uncovers fades to black over 3 px — transparent on the waveguide, unlike a smeared edge), then the post pass reads
the warped textures and the HUD composites at zero disparity. Head rotation is therefore presented at 60 Hz
with no 33 ms stair-step, while translation, the toys and the zoom update at 30 Hz (the rope/sluurp lag a
vsync — accepted). `uDt` stays the pair dt; the APL probe uses the vsync dt. The log says
`pacing 30 (2-slice, present 60.0 Hz, scene 30.0 Hz, timewarp on, warp max N°)`; the HUD debug line shows
`rung 5@30tw`; `nowarp` in the debug flags presents without the reprojection (for A/B on the head).

| Quality | Slices | Rung |
|---|---|---|
| **Auto** | fixed per room (above) | controller (§10.3) toward the scene cap |
| **High** | fixed per room | the scene cap, no controller |
| **Low** | fixed per room | controller, cap 3 |
| menu / notice | 1 | forced rung 2 (unchanged) |

### 10.2 What the controller measures (`gl/GpuTimer.kt`)
* **Raw GPU time per vsync** from `GL_TIME_ELAPSED_EXT` queries through the generic GLES30 query binding
  (ring of 4, polled without stalling). Falls back to the frame period if the extension is missing. With 2
  slices a vsync's time is one slice + the present (+ bloom/echo on the completing vsync).
* **Clock normalisation.** The Adreno governor lowers the clock whenever the frame fits with room to spare
  (target ≈ 65–70 % busy), so raw time alone cannot say whether the next rung fits. Every third frame a fixed
  ALU workload (96×96 px × 300 iterations, ≈ 0.42 ms at the full clock) is drawn inside its own query; its
  time scales with 1/clock, the session minimum is the full-clock reference, and
  `trueMs = rawMs × calibMin / calibNow` is the cost the frame would have at the full clock. The log line
  prints `clock NN%`.
* **Peak-hold** (`peak`, decaying max, τ ≈ 6 s): climbs are judged on the recent peak, because a
  room's cost swings 2–3× with the view (grazing the Nave's bulb; the Hive's `R += 4·uHitFlash` flash costs
  ~2× for 0.7 s after every hit).

### 10.3 The rules (`RungController` in `gl/Pipeline.kt`)
* **Per-vsync budget, both pacings.** `hi` = 15.5 ms, `budget` = 13.5 ms per vsync (a slice must fit one
  vsync or the warp presents late). Cost model: `cost(rung, mono) = scale² × stepBudget × (2 if rung ≥ 3 and
  stereo) / slices`; predicted cost of a configuration = `2.5 ms + (peak − 2.5) × cost(to)/cost(from)`
  (2.5 ms = timewarp + post + HUD + probe per vsync at the full clock).
* **Stereo vs. mono is decided ONCE per room entry**, 2 s after entry, from the measured cost: let
  `sr` = the highest rung ≥ 3 at which stereo is predicted to fit and `mr` = the highest mono rung; stereo
  wins when `sr ≥ mr − 1` ("stereo Subtle at one rung lower beats mono at a higher rung"), otherwise the
  room renders mono for the visit. A stereo room never drops below rung 3 (rung ≤ 2 would be mono by the
  ladder — a flip). The decision is judged on the current-rung EMA (the entry peak still carries the
  settle-phase rungs), logged (`room decision: stereo at rung 5 (...)`) and remembered per slot; changing the
  Stereo setting re-decides. A mono decision gets exactly **one recovery**: when, ≥ 10 s after entry, the
  peak cost predicts stereo **one rung below** the mono rung (≥ 3) ≤ budget for 5 s straight, the room switches
  to stereo once (`stereo recovered at rung N`) and stays — so a start pinned in a crevice (the Nave start pose with the
  gaze pitched into the bulb costs ~30 ms/vsync at stereo rung 3) does not cost the visit its parallax.
  The old mid-room mono lever is gone.
* **Settling window**: the first 6 s after entry — running time: a period > 100 ms (a lazy shader compile, a resume) shifts the
  entry / gap / dwell clocks by the stall (`RungController.stall`), and settling also needs 600 fed vsyncs (fixer 2026-09-03: on a
  fresh `install -r` launch the four compiles, ~8.5 s of stalls, consumed the whole window before a frame was measured and the
  Tidepool climbed 5 → 6 → 7 → 8 at the settled 20 s gap) — keep the fast rules (down every 600 ms on
  `rawEma > hi` or ≥ 4 missed vsyncs in 45, up after 45 fitting frames with a 1.5 s dwell that doubles after
  a climb that fell straight back). **After settling: a drop at most every 5 s, a climb at most every 20 s
  (`CLIMB_GAP_SETTLED_NS`, 2026-09-03 integration — a resolution step is the one visible "pop" left), never in the 2 s
  after a hit, always ±1.** The 5 s log line counts `rung changes after settle`.
* **Per-slot memory**: a room restarts at its last settled (rung, mono) so a revisit does not judder through
  the ladder; a never-visited room starts stereo at min(5, cap). The controller is not fed during the menu,
  a crossfade or a shader-compile frame.

### 10.4 APL flash guard (`Pipeline.aplProbe`)
The post pass is rendered a third time at 32×24 into an RGBA8 FBO (left eye, same uniforms) and read back
through a ping-pong PBO one frame late (no stall); the mean sRGB-decoded luma `aplMean` drives
`aplExposure` ≤ 1, multiplied into `uExposure`. It is a **flash guard only** (loosened 2026-09-03 — the old
steady-state servo toward 0.17 washed the rooms out): fast attack (×≥ 0.7 per frame) while the mean is above
**0.30**, floor **0.55**, and otherwise a release back to 1.0 with τ **0.6 s**. A room's authored brightness
is what the player sees. Disable with the `noapl` flag.

### 10.5 Measured on the X3 Pro (timewarp build, 2026-09-03 09:00, glasses off-head on the table — gaze pitched −20° — head tracking on, CRUISE from the start pose, 65 s per room)
Per-vsync GPU times (one slice + the present pass) at the full clock; "fixed" = `noscene` = 1.1 ms/vsync.

| Room | Slices / present | Entry → decision → settled | True ms/vsync ema / peak | Present / scene Hz | Motion (accel max, over-cap, rung changes after the 6 s settle) |
|---|---|---|---|---|---|
| Tidepool | 1 / 60 fps, no warp | 5 → climbs 6, 7, 8, 9 in 25 s | 7–11 / 8–12 at rung 9 | 60 / 60 | 0.0 u/s², 0, 2 (climbs) |
| Nave | 2 / 60 fps warp | stereo 5 → dives into the bulb within 3 s (pitched gaze) → 4, 3 → decision mono 2 while pinned in a crevice (ema 29 at stereo 3) → recovery when the view opens | pinned in a crevice: 25–35 at stereo 3 (≈ 70 ms/pair at stereo 5); open flank: fits stereo 5 | 60 / 30 | ≤ 4.00 u/s², 0, 1 |
| Throat | 2 / 60 fps warp | stereo 5 → 4, 3 → decision mono 4 (ema 17 at stereo 3) → 5 mono → one stereo recovery | 9–14 / 15–23 at mono 4–5; stereo 7 was 19 (too far — the recovery now targets one rung below) | 60 / 30 | 0.0–1.1 u/s² after entry, 0, ≤ 1 per 5 s |
| Hive | 2 / 60 fps warp | stereo 5 → 4 → decision mono 3 (ema 24 at stereo 3) → 2 mono after settle | 8–14 / 16–23 at mono 3; 16 at mono 3 near walls | 60 / 30 | ≤ 4.00 u/s², 0, 1 |

Worst views: the Nave pinned inside a crevice with the shrunken skin (skin 0.10 u at zoom 0.3) costs ≈ 70 ms/pair
at stereo rung 5 — no controller fits that at 30 fps except at rung ≤ 2 — and a room looking into empty space
(every ray runs its full step budget) costs about as much as its worst surface view; the flank view of the bulb
(the designed start view with a level head) is the cheap one where stereo rung 5 fits. The rooms' current
shaders (the 08:24 room edits) run ≈ 200–300 ns/px in those views — the §6 budget still applies.
Slicing scales linearly with the eye-rect pixels (rung 0 mono → rung 5 stereo = 5.7× px = 5.7× cost), the
timewarp itself is free (`nowarp` changes nothing measurable), and no slice seam is visible in any capture.

### 10.6 Picture-level acceptance: `tools/apl.py` and `tools/capture.sh`
`tools/.venv/bin/python tools/apl.py <png>` measures the LEFT-EYE crop of a 1280×480 screenshot (a ≤ 640 px
wide file is measured whole) and prints `{meanLuma, brightFraction, paleFraction, maxLuma, pass}` with
luma = 0.2126 R + 0.7152 G + 0.0722 B on sRGB-decoded values. Acceptance: meanLuma ≤ 0.20,
brightFraction (luma > 0.55) ≤ 10 %, paleFraction (min(r,g,b) > 0.45) ≤ 2 %, on three captures per room —
idle cruise, 0.3 s after a flick, 0.3 s after a hit — with the HUD hidden (Show HUD = Off or the `nohud` flag).
`tools/capture.sh <out.png> idle|flick|pulse [ms]` takes those captures: it schedules the flick/pulse on the
glasses' own clock via the debug flag and screencaps at +ms on the device (the app logs `debug pulse fired
(+N ms)`). Baseline numbers for the pre-polish `docs/screens/scene*-left.png` (HUD on, exposure 1): Tidepool
0.256 **fail**, Nave 0.049, Throat 0.125, Hive 0.126 (the last three pass the numeric rule as captured —
the Throat/Hive frames are mid-luma fields rather than glowing structure, which the numbers do not catch).

---

## 11. The DIVE contract (motion + dive core, 2026-09-03) — "travelling INTO the fractal"

The owner wants to fly *into* the design. The core implements it; rooms code against these exact names:

| Member (`Scene`) | Meaning |
|---|---|
| `zoom: Float` | **The continuous uZoom** = hit levels eased (1.2 s ease-in-out) **+ the travel dive**, written by the core into the scene every frame BEFORE any `de()` / `field()` / `current()` call. Use `zoom` — not the integer `level` — in the CPU DE exactly as the shader uses `uZoom`. |
| `open val diveRate: Float = 0f` | Zoom levels gained per world unit of forward travel, at any speed level (0 = hits only). The core adds `diveRate · travel` to uZoom, eased (τ 0.25 s) so it never jumps; `travel` is the net forward distance (∫ max(0, vel·heading) dt), so a camera pinned against a wall does not keep diving. Restarts at 0 with the flight on every room entry. |
| `open fun skinAt(zoom: Float): Float = max(0.20 · 0.85^zoom, 0.03)` | The flight hover/slide skin at this depth: it shrinks as you dive so the camera can enter the crevices the detail opens up. The hover slowdown scales with it (§3.1). Rooms may override (e.g. a tunnel room with a fixed cell size). |
| `open fun ropeScale(zoom: Float): Float = 0.85^(zoom · 0.5)` | Toy scale as you dive — the toy shrinks with the detail. The core applies it to the **target radii and spawn margins** (`Targets`, never below 0.03 u, 3D rooms) AND, since the 2026-09-03 fixer round, to the **sluurp physics/draw radius and the rope rest length** (`ToyCtx.toyScale = scene.ropeScale(zoom)` in every room, 2D included — the Tidepool's own `ropeScale` floors at 0.5; `Toy.rB = max(0.018, 0.045·scale)`, `restLen = restLenFor(level)·scale`, the reel eases a shrinking rest length in). The 2D projection (§4.2) is unchanged: it projects `rs.sluurpRadius = toy.drawRadius`, so the drawn disc shrinks with the physics radius. Target radii stay unscaled in 2D (`TargetCtx.toyScale` = 1 there, as before). |
| `current(p, t)` | Clamp raised from 0.15 to **0.35 u/s** so a room can PULL the flight into a canyon mouth / gap / vein when the gaze is within ~30° of it — beyond that return zero or a gentle drift (gaze always wins). The acceleration cap (4 u/s²) smooths a pull that switches on. |
| Speed table | `Flight.LEVEL` = HOVER 0 · SLOW 0.30 · **CRUISE 1.0 · FAST 1.8 · WARP 3.0** u/s (× `speedScale`). Auto-flight stays ≤ 1 u/s. |

**The 2D room dives the same way** (integration fix, 2026-09-03 — the Tidepool's `diveRate = 1/6` had no effect
because `Flight.step2D` still ran the old DESIGN §3.3 table (0.07 levels/s at CRUISE, capped at 6, uploaded as
`uTravel`, which the Tidepool shader does not read)): its "forward travel" is `LEVEL[level] · speedScale` per second
(auto-flight ≤ 1 u/s, attract SLOW) × the boundary gate of §3.2 (0.3 inside the set, `clamp((escapeCount − 3)/6)`
outside, EMA τ 150 ms), accumulated into `travel` (= `uTravel`, u) and folded into uZoom as `diveRate · travel`
(eased τ 0.25 s) exactly like a 3D room — CRUISE descends one level per 6 s on the filament, unbounded up to the
room's own depth clamp (26). `planeScale / escapeCount / maxIter` are called with `depth = zoom` (there is no
separate 2D dive any more). Over flat exterior a **structure nudge** drifts the pan centre back toward the set along
the escape-count gradient at ≤ 0.15·scale per second × (1 − gate) — weaker than a deliberate gaze (0.24·scale/s at
20°, so the gaze always wins) but enough that a few degrees of pitch bias no longer walk the view into black water
and stall the dive; the pan is bounded to |pan| ≤ 2.2 (the set lives inside |z| < 2). Auto-flight applies the nudge
to the centre of its Lissajous. On the table (gaze pitched −20°) the gate closes after ~10 s — measure the 2D dive
with Head tracking = Off or on a head.

Order of a 3D frame (`Game.update`): head → `zoom = zoomHits + flight.diveZoom`, `scene.zoom = zoom` →
`Flight.step3D` (reads `skinAt`, `current`, `de`, `grad` at that zoom; advances `travel` → `diveZoom`) →
`zoom` re-derived with this frame's dive and written again → toy substeps / targets / render use that value
(`uZoom` = the same number). `scene.level` stays the integer HIT level (target count, rest length, chime).

Motion guarantees the rooms can rely on (measured on the X3 Pro, Nave, CRUISE, 60 s, head still):
camera acceleration ≤ 4 u/s² per frame in every term (flight, current onset, skin push, auto-flight heading
changes), hover factor EMA 150 ms, skin held by a critically damped spring (no per-frame clamp), no
pacing/stereo flips mid-room, ≤ 1 rung change per 5 s after the first 6 s, and head rotation presented at
60 Hz through the timewarp in every 3D room.


## 12. Readout auto-hide and the interior pull (2026-09-03, owner request)

**HUD auto-hide.** `Game.hudVisible` is now `store.showHud && time - lastInputTime < HUD_HOLD_S`
(10 s). Every temple input refreshes `lastInputTime`, so a tap — which is also a flick — brings the
readout back for another 10 s. The visibility edge sets `hudDirty` in both directions so the texture
never keeps a stale frame, and `Hud.hasContent` then goes false while it is hidden, which skips the
composite pass entirely. Toasts (speed level, "re-centred") and the look-away chevron are drawn
OUTSIDE the `hudVisible` gate: a speed change must be readable even while the readout is resting.
The Show HUD setting still hard-disables it.

**Leading the player inside the fractal.** The scene `current()` clamp `Flight.CURRENT_MAX` is
0.35 → **0.60 u/s**, and the Throat's and Hive's own `PULL_MAX` follow it, so a room can actually
steer the flight into a canyon mouth / tunnel axis / sphere gap rather than merely leaning on it
(gaze still wins: at CRUISE the Nave's pull bends the path ≤ ~32°). The Nave starts at
`(-0.9, 0.25, -2.0)` — 2.2 u out instead of 3.4, one canyon mouth about two seconds away at CRUISE —
holds the cathedral from 4 u at 0.30 u/s, and its canyon gate opens on a weaker probe score
(`smoothstep(0.08, 0.30)`). `Targets.sample()` now weighs up to `NOOK_CANDIDATES` (4) clear
candidates against each other and takes the one with the SMALLEST clearance that still fits the eye,
so the hunt puts targets in crevice mouths instead of open space. 2D rooms are unaffected (their
`de()` is a constant).
