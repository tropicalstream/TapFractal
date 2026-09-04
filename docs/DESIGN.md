# TapFractal — Unified Design (v1, gaze navigation)

Synthesised from the orchestrator SPEC (v1, GAZE NAVIGATION directive) and three angled designs
(toy · spectacle · glasses). This is the single implementable design. Where the three disagreed the
choice is stated inline as **DECISION** with the reason; the full list is repeated in §14.

Companion: `docs/SHADER_CONTRACT.md` (the spec contract verbatim + the additive uniforms/helpers this
design needs). `docs/SPEC.md` remains the authority on hardware, toolchain, guardrails and slot semantics.

Conventions: 1 world unit ≈ 1 m of feel. Angles in radians unless marked °. Values marked **[tune]** are
starting points with a range. "MUST" = ship-blocking; "SHOULD" = expected in 1.0; "MAY" = phase 2.

---

## 0. The four rooms and the one toy

| # | Slot (SPEC) | Final name | Class | Technique | What the sluurp *is* here | Music brief (unchanged) |
|---|---|---|---|---|---|---|
| 1 | JULIA DRIFT | **The Tidepool** | `scenes/Tidepool.kt`, `scene1.frag` | 2D Phoenix–Julia, orbit traps, kaleidoscope fold at L≥3 | an orbit trap dragging a filament through the set; its swing bends the Julia constant | dreamy ambient / downtempo psychedelic, 70–100 BPM |
| 2 | MANDELBULB CATHEDRAL | **The Nave** | `scenes/Nave.kt`, `scene2.frag` | raymarched Mandelbulb, power breathes with energy | a lantern brushing stained-glass ribs; hits ring the bulb (power kick) | deep hypnotic psybient / progressive trance, 100–128 BPM |
| 3 | MENGER TUNNEL | **The Throat** | `scenes/Throat.kt`, `scene3.frag` | kaleidoscopic-IFS Menger lattice, fold count = level | a comet you tow; flicks leave echo streaks | high-energy drum & bass / breakbeat / electro, 150–175 BPM |
| 4 | APOLLONIAN BLOOM | **The Hive** | `scenes/Hive.kt`, `scene4.frag` | raymarched Apollonian packing | the only lamp; bass extends its reach; every hit is a flashbulb | euphoric psytrance / acid / uplifting electronica, 128–145 BPM |

Menu value strings: `Tidepool · Nave · Throat · Hive` (the article is dropped in the 22 px value column).
HUD scene label (18 px violet, top-left): `THE TIDEPOOL` etc.

The play loop in one paragraph: you look where you want to go and fly there (swipe up/down changes the
speed level; HOVER stops you). A glowing eyeball (the sluurp) hangs on an elastic rope from an anchor
~1.4 u ahead of your head; turning your head swings it, tap flicks it. Glowing "fractal eyes" (targets)
appear ahead of you in open space; touching one with the sluurp is a HIT: burst, chime, the world grows one
level (structure parameter steps, palette steps, rope lengthens), a new eye spawns ahead. Targets you fly
past are quietly recycled. Nothing ever says "game over".

Shared psychedelic grammar (identical in all rooms so the mapping becomes subconscious):

| Signal | Bodily meaning | Always modulates |
|---|---|---|
| `uBass` | mass / breath | *size*: scale, inflate, throat width, lamp reach, sluurp breath |
| `uBeat` | kick / snap (onset-gated ≤ 3/s) | *structure*: power kick, fold snap, hive inflate, scan-ring launch, rope pluck. Never a full-field flash |
| `uTreble` | sparkle | *high-frequency detail*: edge glitter, motes, prism aberration |
| `uEnergy` | heat | *rate*: hue drift speed, glow density, fog lift, aberration base — **never flight speed** (§3) |
| `uZoom` | the world grows | fold count / power / packing / seed, iteration budget, palette phase, rope length |
| `uHue` | slow global drift | palette phase `d += uHue`, +0.05 per hit |

---

## 1. Frames, optics, stereo

### 1.1 Optics and `uFov` (DECISION, ratifies glasses C1)
`eyeUV()` spans `uv.y ∈ [−0.5, +0.5]` and `rayDir()` uses `uv·uFov` as the tangent, so the top-edge tangent is
`0.5·uFov` → **`uFov = 2·tan(halfVerticalFov)`**. The spec comment ("tan(half vertical fov)") is corrected in
`SHADER_CONTRACT.md`; the code is unchanged. Optics-matched default **`uFov = 0.35`** → 19.9° V × 26.3° H
(official 30° DFOV ≈ 18.3° V would be 0.322; "by-feel" 22° V would be 0.389; 0.35 splits it). ~24 px per degree.
Camera gain is 1.0, always — any other value makes the world slide against the real room seen through the
waveguide.

### 1.2 World frame (DECISION: left-handed +z forward, not glasses' −z)
`x` = right, `y` = up (gravity-true), `z` = straight ahead at the last recentre. Then `cross(right, up) = forward`
and `det(uCamRot) = +1`, so scene authors can use either the columns or a cross product without a
handedness trap. Fractal formulas are orientation-agnostic; the mirror image costs nothing. ENU → S is
`(e, n, u) → (e, u, n)`.

### 1.3 Toy layout in the narrow window
With `uFov = 0.35` the visible half-height at 1.4 u is `1.4·0.175 = 0.245 u` (half-width 0.327 u). The toy
design's numbers (anchor 0.28 up, rope 0.42) put the anchor *off the top* of this frame, so they are
re-solved (DECISION) for the frame, keeping the toy's proportions (anchor ~62 % up the frame, sluurp resting
~30 % down, sluurp ≈ 20 % of the frame height):

```
 uv.y   (+0.5 = top edge)                  formulas (uFov 0.35, anchor z 1.4)
 +0.50 ┌───────────────────────┐
 +0.31 │        ◦ anchor       │   y_A  = 0.31·1.4·0.35 = 0.152  → A_local = (0, +0.15, 1.4)
       │        ╎ rope         │
  0.00 │        ╎              │
 -0.15 │        ◉ sluurp (rest) │   L0 = 0.15 + 0.15·1.4·0.35 = 0.224 → L0(0) = 0.23
 -0.50 └───────────────────────┘   sluurp uv radius = 0.045/(1.4·0.35) = 0.092 → 44 px (≈3.7°)
```
If `uFov` is ever changed keep `uv_anchor ∈ [0.27, 0.35]` and `uv_rest ∈ [−0.22, −0.10]` by re-solving
`y_A = 0.31·1.4·uFov`, `L0 = y_A + 0.15·1.4·uFov`.

### 1.4 Stereo parallax (adopted from glasses §2 unchanged)
Half-interaxial `s` along the camera right vector, **convergence at the anchor distance `D_c = 1.4`** by a
per-eye uv shift (asymmetric frustum, no toe-in):

```
uCamPos(eye)  = camCenter ± s·right              (− left, + right)
uUvShift(eye) = ± s / (D_c · uFov)               (− left, + right)  → the anchor lands at uv.x = 0 in both eyes
disparity(z) [px] = 480·(2s/uFov)·(1/D_c − 1/z)  (> 0 uncrossed/behind, < 0 crossed/in front)
```
Comfort limits: uncrossed ≤ 0.7° (17 px), crossed ≤ 0.5° (12 px). Nearest sluurp approach `z_min = 0.55` (head bubble).

| Setting | s | far disparity | at 0.55 u | notes |
|---|---|---|---|---|
| Off | 0 | 0 | 0 | core renders ONE eye and blits it to both (−50 % raymarch cost) |
| **Subtle (default)** | **0.004** | +7.8 px | −12 px | 12.5 % of natural IPD; keeps the two raymarched images ≥ 95 % correlated (fractal sub-pixel sparkle would otherwise refuse to fuse) |
| Deep | 0.008 | +15.7 px | −24 px transient | convergence-follow: `D_conv = lerp(D_conv, clamp(|sluurp − camCenter|, 0.7, 1.4), 1 − exp(−dt/0.4))`, `uUvShift` uses `D_conv` |

Auto-degrade (no user action): rung ≤ 2 → s = 0 (sub-pixel difference); thermal COOL → mono + toast
"stereo parallax paused — cooling"; menu open → unchanged; crossfade → s held. 2D scene: the camera does not
translate but per-eye `uUvShift` and per-eye *projected* toy uniforms are still uploaded (§4.6), so the
Julia plane sits at "infinity" (7.8 px uncrossed) behind toys that carry real depth.

Per-eye hygiene (MUST): bloom/aberration/echo taps clamp to the eye's own rect; echo zoom/rotate is about
each eye's centre; HUD at zero disparity; vertical disparity is zero by construction (shared `up`, same
viewport height/y-origin; `eyeW = floor(640·scale)`, `eyeH = floor(480·scale)`, right origin `(eyeW, 0)`).

Copy rule: "stereo parallax", never "3D".

---

## 2. Head tracking

### 2.1 Pipeline
```
 sensor thread (HandlerThread, zero allocation)                GL thread (per frame)
 ┌──────────────────────────────────────────┐                  ┌──────────────────────────────────┐
 │ GAME_ROTATION_VECTOR @ SENSOR_DELAY_GAME │ qRef (world←dev) │ qCur = slerp(qCur, qRef, α)      │
 │   → quaternion → snap/slerp into qRef    │─────────────────▶│ predict: qCur ⊗ exp(½ ω̄ Δt_pred) │
 │ GYROSCOPE @ FASTEST (≈200 Hz)            │ ω (device frame) │ recentre: W = Rx(−pitch0)·Ry(−yaw0)│
 │   → integrate into qRef between samples  │─────────────────▶│ → right/up/fwd → uCamRot         │
 │   → publish ω̄ (mean of last 2)           │                  │ → yaw/pitch/roll (logic only)    │
 └──────────────────────────────────────────┘                  └──────────────────────────────────┘
```
Publication: `@Volatile` quaternion + ω triple; the GL thread never blocks. GRV = drift-bounded reference
(pitch/roll gravity-true, yaw drifts < 1°/min); gyro integration removes the 50 Hz stair-step.

### 2.2 Sensor → world basis
`SensorManager.getRotationMatrixFromVector(m, values)`: row-major `R`, `world_ENU = R · device`. Device axes in
ENU are the columns; optical forward is device −Z:
```kotlin
fun enuToS(e: Float, n: Float, u: Float, out: FloatArray) { out[0] = e; out[1] = u; out[2] = n }
enuToS( m[0],  m[3],  m[6], rightS)     // ear→ear
enuToS( m[1],  m[4],  m[7], upS)        // top of head
enuToS(-m[2], -m[5], -m[8], fwdS)       // optical forward
yawRaw   = atan2(fwdS.x, fwdS.z)         // 0 = north at rest, + = right
pitchRaw = asin(clamp(fwdS.y, -1f, 1f))  // + = up
rollRaw  = -asin(clamp(rightS.y, -1f, 1f)) // + = right ear down
```
Sanity: facing north `f = (0,1,0)_ENU → (0,0,1)_S`, yaw 0 ✓; turning right (east) `f → (1,0,0)_S`, yaw +90° ✓.

Recentre `W = Rx(−pitch0) · Ry(−yaw0)` with
```
Ry(θ) = [ cosθ  0  sinθ ]        check: Ry(−yaw0)·(sin yaw0, 0, cos yaw0) = (0, 0, 1) ✓
        [  0    1   0   ]
        [ −sinθ 0  cosθ ]
uCamRot = [ W·rightS | W·upS | W·fwdS ]           (columns)
gaze    = W·fwdS                                    (flight direction input, §3)
uGravity = W·(0,−1,0)                               (real-world down in scene coords)
```
Yaw/pitch/roll are used **only** for logic (2D pan, look-away, HUD); the camera basis never passes through
Euler angles (no gimbal issue; logic-yaw frozen when |pitch| > 80°).

### 2.3 Filtering, prediction, roll
| Stage | Value | Why |
|---|---|---|
| GRV → qRef | `angle(qRef, q) > 0.5 rad ? q : slerp(qRef, q, 0.35)` | relocalisation snaps; 0.35 hides 50 Hz quantisation without lag |
| Gyro integration | `qRef = normalize(qRef ⊗ exp(½ ω dt))`, dt ∈ [1e-5, 0.05] | body-frame post-multiply |
| Per-frame ease | `qCur = slerp(qCur, qRef, 1 − exp(−dt/τ))`, **τ = 20 ms** | heavier "swims" against the room |
| Prediction | `Δt = 0.5·framePeriod + 10 ms` (18 ms @60, 27 ms @30, cap 40), `qRender = qCur ⊗ exp(½ ω̄ Δt)` | 20 ms of lag at 100°/s = 2° = 48 px of slip against furniture |
| Jitter | none on the camera; One-Euro (`minCutoff 1 Hz, β 0.4`) on logic yaw/pitch only | dead-bands on the camera produce stick–slip |
| Roll | passed through, same τ as yaw/pitch, no dead-band > 0.15° | lagged roll is the "boat" feeling |
| Gain | 1.0 fixed | see §1.1 |

### 2.4 Drift and recentre
1. **Triple-tap = hard recentre**: `yaw0 = yawRaw`, `pitch0 = clamp(pitchRaw, −20°, +20°)`, toast "re-centred" (1.2 s). In the Tidepool it also zeroes the pan input.
2. Launch/resume: first reference captured silently after 0.4 s of samples.
3. **Soft creep** (yaw only) when ALL hold for ≥ 2 s: `|ω| < 4°/s`, no flick in 3 s, speed level HOVER or head outside the scene's interest cone. Then `yaw0 += (yawRaw − yaw0)(1 − exp(−dt/12 s))`. Stops the instant the head moves. (Creep during play would be a phantom force on the rope and a phantom bank in flight.)
4. **Head tracking = Off**: sensors unregistered; `uCamRot = identity` (roll 0); anchor fixed at `A_local`; flight follows the scene's `autoFlight(t)` path (§3.5); anchor Lissajous drift amplitude 0.18 so the toy still swings; aim assist widened to 30°; no target timeouts.

### 2.5 Axis self-test (MUST on the first device build)
Debug HUD readout (`BuildConfig.DEBUG`): yaw/pitch/roll in degrees. (1) turn right ⇒ yaw increases; (2) look up
⇒ pitch increases; (3) right ear down ⇒ roll increases. If (1)/(2) are swapped, switch
`AxisMap.ENU_MINUS_Z → AxisMap.MOUNT_SWAP` (yaw←pitch, pitch←−yaw) in `HeadTracker.kt`. Keep the enum; never
hard-code. Log `Display.rotation` at start (expect `ROTATION_0`).

---

## 3. Gaze navigation (the SPEC directive; overrides every camera path in the three designs)

**DECISION:** all three designs assumed an autonomous camera (orbit / tunnel steer / void-finder / drift).
The spec v1 directive replaces them: the camera rotation is the head, and the camera flies forward along the
gaze at a user-set speed. The designs' camera paths survive only as (a) the *start pose*, (b) a gentle
*current* that never wins against gaze, and (c) the *auto-flight* path used when Head tracking = Off or in
attract mode. The glasses design's motion caps (≤ 1 u/s path translation) are kept for those involuntary
paths; user-chosen flight may exceed them — WARP is an explicit opt-in and the head-locked toy is the
"virtual nose" that keeps it tolerable.

### 3.1 Speed levels (swipe UP = +1, DOWN = −1; bounded; toast with the level name)
| Level | Name | Base speed (u/s) | Tidepool pan gain (plane-units/rad/s, × plane scale) | Tidepool dive (levels/s) |
|---|---|---|---|---|
| 0 | HOVER | 0 | 0 | 0 |
| 1 | SLOW | 0.30 | 0.45 | 0.03 |
| 2 | **CRUISE** (default) | 0.70 | 0.90 | 0.07 |
| 3 | FAST | 1.30 | 1.50 | 0.13 |
| 4 | WARP | 2.20 | 2.40 | 0.22 |

Per-scene `speedScale`: Nave 1.0 · Throat 1.6 (a rush room; cells are 4 u long) · Hive 0.8 (dark, close walls).
Music **never** changes flight speed (DECISION — glasses comfort rule: no self-motion the player did not choose;
spectacle's `speed·(1+0.9·E)` and toy's beat gusts are dropped). Speed is remembered per session, not persisted.

### 3.2 Flight controller (3D rooms) — Kotlin, once per rendered frame
```kotlin
object Flight {
    val LEVEL = floatArrayOf(0f, 0.30f, 0.70f, 1.30f, 2.20f)
    var pos = Vec3(); var heading = Vec3(0f, 0f, 1f); var speed = 0f
    fun step(dt: Float, gaze: Vec3, scene: Scene, level: Int, zoom: Float) {
        // bank: the velocity direction follows the gaze with a short lag (τ 0.30 s) — turning your head bends the flight
        heading = slerpDir(heading, gaze, 1f - exp(-dt / 0.30f))
        val want = LEVEL[level] * scene.speedScale
        // no clipping: hover as the surface ahead closes in, using the scene's CPU DE mirror
        val ahead = scene.de(pos + heading * 0.35f, zoom)
        val here  = scene.de(pos, zoom)
        val slow  = clamp(ahead / 0.60f, 0f, 1f) * clamp((here - 0.12f) / 0.20f, 0f, 1f)
        speed += (want * slow - speed) * (1f - exp(-dt / 0.40f))        // no jerks on level change
        pos += heading * (speed * dt) + scene.current(pos, uTime) * dt   // current: |v| ≤ 0.15 u/s, gaze always wins
        // slide along the surface: keep a 0.20 u skin between the eye and the fractal
        val d = scene.de(pos, zoom)
        if (d < 0.20f) pos += scene.grad(pos, zoom) * (0.20f - d)         // grad = tetrahedron 4-tap of de()
    }
}
```
~7 DE evaluations per frame (≈ 1 µs each). A level-up tweens the structure parameter over 0.8 s
(`fract(uZoom)`), so the fractal can grow *into* the player; the same push-out handles it (the eye is never
inside a surface). `uSpeed` (u/s) and `uTravel` (distance flown, or dive depth in 2D) are uploaded for scenes.

Spawn cone (SPEC): targets appear only in open space **ahead**, inside ±25° of the gaze, 1–3 u away — see §5.

### 3.3 Gaze in the 2D room (Tidepool)
Yaw/pitch relative to the recentre steer a **pan velocity** across the plane; the speed level sets a slow
continuous **dive** (zoom-in rate). `pan_vel = gain(level)·scale·soft(yaw, pitch)` with
`soft(a) = P·tanh(a/P)`, `P = 20°`, dead-zone 1.5°; `scale = 1.6·0.72^(uZoom + uTravel)`. `uTravel` = dive
depth in levels, integrated at the table rate, **capped at 6.0** (combined magnification ≤ ~1.6·0.72⁻¹³ ≈ 115×
the L0 pool — highp float stays clean); at the cap the dive rate is 0 (you hover; hits still add levels). The
"no clipping" mirror for 2D: `Tidepool.boundary(p)` = smooth escape count at the pan centre; dive rate is
multiplied by `clamp((n − 3)/6, 0, 1)` if the point escapes (flat exterior → nothing to dive into) and by 0.3
if it is interior — you cannot dive into flat colour, you are steered onto the glowing boundary by simply
looking at it.

### 3.4 Recentre and the interest cone
`uLookAway = smoothstep(cone, cone + 20°, angle(gaze, +z))` with per-scene `interestCone`: Tidepool 45°,
Nave 40°, Throat 180°, Hive 180°. Nave draws a faint 2-octave `noise3` nebula backdrop when `uLookAway > 0`
(≤ 0.08 luminance, ≤ 300 ops/px) so the frame is never dead; after 4 s away a 24 px HUD chevron points back
toward the bulb. (Gaze flight already lets the player fly back; no world-yaw recovery is applied.)

### 3.5 Auto-flight (Head tracking = Off, attract mode)
Each scene supplies `autoFlight(t): Vec3` = desired heading; the same controller flies it at ≤ 1.0 u/s
(SLOW/CRUISE only; WARP/FAST are clamped to 1.0). Defaults: Tidepool — Lissajous pan `(0.35 sin 0.05t,
0.35 cos 0.037t)·scale`, dive at the SLOW rate; Nave — a great-circle orbit at radius 2.6 (heading tangent,
yaw ≤ 4°/s); Throat — down the +z axis with a lateral wobble `(0.05 sin 0.7t, 0.05 cos 0.53t)`; Hive — drift
inside the largest void found on scene entry (§9.7).

---

## 4. The toy (physical-toy fidelity)

### 4.1 Bodies and simulation loop
Everything is simulated in **world space** (DECISION — the spec says world space; the toy design's "rig
space" existed only to make an autonomous camera feel like a turning car. With gaze flight, world-space
drag alone produces the kite/tail feel: fly forward and the sluurp streams behind the anchor; bank and it
swings wide). Fixed **240 Hz** substeps.

| Body | Mass | Radius | Notes |
|---|---|---|---|
| Anchor | kinematic | — | `anchor = camCenter + headRot·A_local + drift(t)` (§4.7). `anchorVel` = finite difference over the last 3 frames, EMA α 0.5. 0.15° dead-band on the head quaternion so a still head yields a still anchor. |
| Sluurp | 1.0 | `r_b = 0.045` | point mass, semi-implicit Euler |
| Rope (visual) | — | tube 0.008 + halo | 12 Verlet points, ends pinned (0 = anchor, 11 = sluurp); cosmetic, never pushes the sluurp |
| Targets | kinematic | `r_t(level)` | slow orbit around the spawn point (§5) |

```kotlin
val dt = min(frameDt, 0.050f)          // never integrate a hitch
val n  = ceil(dt / (1f/240f)).toInt()  // 4 substeps @60 Hz, 8 @30 Hz
val h  = dt / n
repeat(n) { substep(h) }
ropeStep(dt)                           // Verlet, once per frame, 8 constraint iterations
```

### 4.2 Rope & sluurp constants (the yo-yo)
Slack-then-elastic rope: zero force while `|sluurp − anchor| ≤ L_eff`; spring–damper along the rope axis when
stretched. Constants re-scaled from the toy design for the shorter rope (§1.3) — DECISION: keep the toy's
underdamped bungee character (ζ ≈ 0.26) rather than the glasses' soft spring (k = 18), because the snap and
overshoot *are* the toy.

| Constant | Value | Rationale |
|---|---|---|
| Rest length `L0(level)` | `0.23·(1 + 0.03·level)`, cap 0.34 | fits the frame; the rope grows with the world |
| Spring `K` | **320** [260–400] | ωn = 17.9 rad/s → 0.35 s period; a 4 u/s anchor swing stretches ≈ 0.22 u (≈ L0) — visibly bungee |
| Damper `C` | **9** [7–12] | ζ = C/(2√(Km)) = 0.25: one visible overshoot, settles in ~2 bounces; applied on `(v_sluurp − v_anchor)·dir` |
| Linear drag (tethered / free) | 0.6 /s / 0.35 /s | half-life 1.15 s tethered; coasts in free flight |
| Quadratic drag | 0.02 | keeps flicks honest above ~6 u/s |
| Gravity | `1.0·uGravity` (real-world down) | DECISION: toy's 1.2 vs glasses' 0.8 → 1.0 along *real* down (strongest "it's in my room" cue). Per-scene fields add to it (§4.8) |
| Beat impulse | `0.25·uBass` u/s on gated onsets | the sluurp dances by itself (§4.7) |
| Hit restitution (targets) | 0.55 | boing |
| Fractal restitution | **0.60** (SPEC) | bounce off the DE surface: when `scene.de(sluurp) < r_b`: `n = grad`, `sluurp += n·(r_b − d)`, `v = reflect(v, n)·0.6` |
| Head bubble | `|sluurp − camCenter| < 0.55` → push out radially | keeps crossed disparity ≤ 12 px (§1.4) |
| Tangential speed cap | 6 u/s on the component ⊥ to (sluurp − camCenter) | 6 u/s at 0.55 u ≈ 250°/s of screen motion — the strobe limit at 30 fps (glasses). Radial speed is uncapped (flicks fly away) |
| Hard leash | `|sluurp − camCenter| > 6` → recall at 8 u/s | safety net |
| Hit cooldown | 250 ms | no double hits / flash stacking |

Substep (Kotlin):
```kotlin
private fun substep(h: Float) {
    var f = scene.field(sluurp) + uGravity * 1.0f
    f -= vel * (if (freeT > 0f) 0.35f else 0.6f)
    f -= vel * (0.02f * vel.length())
    if (freeT > 0f) {
        freeT -= h; tension = max(0f, tension - h / 0.15f)
        if (freeT <= 0f) onRetether()
    } else {
        if (reelLen > restLen) reelLen = max(restLen, reelLen - reelSpeed * h)      // yo-yo return
        val d = sluurp - anchor; val len = d.length()
        if (len > reelLen) {
            val dir = d / len; val stretch = len - reelLen
            val vRel = (vel - anchorVel).dot(dir)
            f -= dir * (K * stretch + C * vRel)
            tension = clamp(stretch / 0.15f, 0f, 1f)                                  // → uRopeTension
        } else tension *= exp(-h / 0.10f)
    }
    vel += f * h; sluurp += vel * h
    capTangential(6f); headBubble(0.55f)
    fractalBounce(0.6f)                                                                // scene.de mirror
    hitTest()
}
```
Rope (Verlet, cosmetic): per-segment rest `max(restLen, |sluurp − anchor|)/11`; forces = gravity + breeze
`0.15·noise3(p·1.5 + t·0.4)` transverse + onset pluck `0.6·uBeat` u/s on point 1 (a wave runs down the rope on
every beat); damping 0.995 tethered / 0.985 free; 8 iterations; uploaded as `uRope[0..11]`, `uRopeN = 12`.

### 4.3 Tap = FLICK → free flight → re-tether snap
```kotlin
fun flick() {                                   // ignored if < 250 ms since the last flick (makes triple-tap taps 2–3 harmless)
    val sp  = vel.length()
    val dir = if (sp > 0.6f) vel / sp else gaze  // still head: throw it forward along the gaze (SPEC)
    val v   = clamp(1.5f * sp + 3.0f, 4.0f, 8.0f) // floor 4, cap 8 (DECISION: toy's 9 trimmed toward glasses' 6)
    vel = aimAssist(dir, maxDeg = if (headTracking) 8f else 30f) * v
    freeT = 0.6f + 0.06f * (v - 4f)             // 0.60 … 0.84 s of slack
    reelLen = restLen; glow = max(glow, 0.8f); squash = 0.35f
    sfx.twang(pitch = 180f + 240f * tension)
}
```
The 4 u/s floor means a tap always does something (from rest ≈ 2 u out); the 1.5× multiplier rewards
tapping at the peak of a head swing (8 u/s, out of the frame and back) — the original toy's "release at the right
moment". Because the anchor is ahead of the head, a flick throws the sluurp forward along the gaze — into the
target you are flying toward.

Free flight: no rope force; `uRopeFree = 1`; rope dims to violet 35 %; a hit during free flight is a
**clean hit** (bigger burst, +0.02 hue, shimmer tail), the sluurp bounces and continues with
`freeT = max(freeT, 0.35)`. A tap arriving with `freeT > 0.10` (≥ 350 ms after the flick, past the system's
double-tap window) = **recall**: immediate re-tether with `reelSpeed = 6`.

Re-tether at `freeT → 0`: `reelLen = max(restLen, |sluurp − anchor|)` (re-attaches at the current length — no
yank), then reels in at 3 u/s; if stretched > 0.05 beyond rest: `tension = 1` for a frame (rope flashes
white-hot), `glow ≥ 0.5`, a 0.5 u/s "caught" tug, snap sound scaled by `clamp((len − restLen)/2, 0, 1)`;
`uRopeFree` decays with τ 0.2 s.

Timeline of one flick from rest: 0 s tap → 4 u/s, twang · 0.25 s ~0.9 u out, rope whipping · 0.60 s re-tether,
snap · ~1.0 s back inside L0, overshoots ~15 % · settled by ~2.5 s.

### 4.4 HIT → the world grows (same frame unless noted)
1. Sluurp `vel = reflect(vel, n)·0.55`, `glow = 1`, `squash = 1` (0.62 along the contact axis for 120 ms, springs back at 12 Hz).
2. Target `uTargetPulse[i] = 1`, decays `exp(−t/0.45)`; shader draws an expanding shell `r_t·(1 + 3(1 − pulse))`, thickness `0.15·r_t`, brightness `4·pulse`, 24 hash-placed sparks. Slot frees at pulse < 0.05; replacement spawns 0.8 s after the hit.
3. **Zoom**: `zoomTarget += 1` (golden +2). **DECISION — no scale overshoot**: `uZoom` eases monotonically to the target over 0.8 s (smoothstep). The toy design's 13 % overshooting spring is a scale lunge, which on a see-through display reads as the world jumping at you (glasses §3.2); the "breathe outward" is delivered instead by the post ripple (4), the structure kicks (§9), the rope growing (5) and the HUD numeral (9).
4. **Growth ripple** `uHitPulse = 1`, τ 0.18 s (post): bloom `×(1 + 0.6p)`, aberration `+0.008p`, echo mix `+0.06p` (cap 0.92), echo zoom bumped one notch while `p > 0.3`, vignette −10 %. `uHitFlash = 1`, τ 0.7 s (scene-side: Hive flashbulb, Nave bell, Throat ring). No screen shake, ever.
5. Rope: `restLen = L0(level)`, lengthens over the reel-in (~0.1 s).
6. Hue: `uHue += 0.05` (golden +0.10, clean +0.02), eased 0.4 s. (DECISION: toy 0.07 / spectacle 0.03 → 0.05, because ZSHIFT already steps the palette per level.)
7. Chime ≤ 20 ms: FM bell 600 ms, pentatonic degree `[0,2,4,7,9][level mod 5]`, octave `(level/5) mod 3`, scene root (§4.8), velocity `0.5 + 0.5·min(speed/6, 1)`.
8. Growth "whoomp" (80 → 40 Hz sine, 500 ms) **beat-quantised** to the next beat from `sceneN.env.json` (half-beat if > 350 ms away; now if < 30 ms).
9. Stats `totalSluurps++`, `bestDepth[scene] = max`; HUD "+1" floats up from the projected target; DEPTH numeral scales `×(1 + 0.5·(1 − smoothstep(0, 0.8, t)))`.

Per-level growth (continuous `z = uZoom`, integer `level`): see each scene's zoom table (§9); global: `L0`,
target count/radius, safe volume scale `1 + 0.08·level`.

### 4.5 Gentle failure (DECISION: kept from toy, adapted to flight)
A target's 25 s lifetime counts **only while it is within 60° of the gaze**; targets that fall behind
(`dot(target − cam, gaze) < 0`) or drift > 4 u away are recycled silently (respawn ahead, no penalty) — flying
past is navigation, not failure. Last 4 s: `uTargetLife` 1 → 0, iris dims and flickers at 2 Hz ±30 %, radius
→ 60 %. Timeout: implode 250 ms, "sigh" (descending minor third, −12 dB), `zoomTarget −= 1` (floor 0, plain 1.2 s
ease), `uHue −= 0.03`. **Mercy**: never two losses within 15 s; replacement spawns easy (dead ahead, 1.2–1.6 u,
+10 s life); after 30 s without a hit the next spawn is an **assist target** at `1.4 + 0.9·L0` in the gaze
direction (a nod hits it). No timeouts in attract or with head tracking off. No copy, no red.

### 4.6 The 2D room runs the same toy (DECISION)
In the Tidepool the camera **position** is frozen at the origin and `uCamRot` = head; the toy sim is
identical (world-space anchor `headRot·A_local`, sluurp bouncing, targets spawned in the ±25° cone at 1–3 u).
The core then **projects** sluurp, rope points, anchor and targets through each eye's camera and uploads
`xy` = eye-uv, `z` = depth, `w` = projected radius, so `toys2D()` draws discs and the toys carry real
stereo depth in front of the plane (§1.4). The Julia constant offset uses the projected offset
`(uSluurp.xy − uAnchor.xy)` (§9.1). Fractal "collision" in 2D: the sluurp's plane point (its uv mapped through the
scene's pan/scale) entering the set interior reflects the in-plane velocity about the boundary normal
(2-tap gradient of the smooth escape count) with restitution 0.6.

### 4.7 Always-on show, idle, attract
Still head: anchor micro-drift Lissajous `(0.03 sin 2πt/5.3, 0.02 sin 2πt/7.9, 0.015 sin 2πt/6.1)`; beat dance
(on gated onsets with `uEnergy > 0.5`: `vel += 0.25·uBass·d_k`, `d_k` rotating by the golden angle about the
anchor→head axis, scaled by `1 − 0.5·activity`); rope breeze + plucks; sluurp breath
`×(1 + 0.04 sin(2π·0.25t) + 0.06·uBass)`; blinks; iris tracks the nearest target within 4 u else the camera;
targets breathe ±8 % with bass and orbit; hue drift `0.004 + 0.02·uEnergy` per s + `0.01·uBeat`.

Attract after 45 s with no input and `|ω| < 0.08 rad/s`: `uAttract` ramps 0 → 1 over 2 s; auto-flight at
SLOW; anchor Lissajous amplitude 0.35; auto-flick every 8–14 s toward the nearest target (aim solved to hit
≈ 80 %, ±8° jitter); six target slots; hits cosmetic (stats not recorded; player's `zoomTarget` restored on
exit over 1.5 s); HUD "look to fly · swing your head · tap to flick" pulsing 0.5 Hz at 60 %. Exit on any
input or `|ω| > 0.25 rad/s` for 150 ms; ramp down 0.5 s.

### 4.8 Per-scene toy feel
| Scene | Field added to gravity | Chime root | Feel |
|---|---|---|---|
| Tidepool | soft leash `−2.0·(p − anchor)` beyond 2.5 u | D3 146.8 Hz | a yo-yo on a wall; the swing morphs the set |
| Nave | `0.6·normalize(−p)` (it hangs toward the cathedral) | G2 98 Hz | a satellite on a string; swinging into the bulb bounces off the ribs (DE mirror, "bong") |
| Throat | turbulence `0.25·noise3(p + t)` | E3 164.8 Hz | a kite: streams behind the anchor at CRUISE+, tugs on the beat, swings wide when you bank |
| Hive | zero extra; centre pull `−0.4·(p − gazeLine(p))` | A2 110 Hz | a space yo-yo, floaty; **the sluurp is the light** |

### 4.9 The sluurp as a creature (shader; core owns `toysDE/toyColor/toys2D`)
Squash & stretch from `uSluurpVel` (stretch `1 + 0.35·clamp(|v|/6, 0, 1)` along velocity, impact 0.62 via `w`,
`s⊥ = 1/√s∥`); iris faces `uSluurpEye`; iris cos-radius `0.55 + 0.05·uBass`, pupil `0.90 − 0.10·uEnergy +
0.06·uSluurpGlow`; sclera deep violet `(0.10, 0.02, 0.22)` with cyan veins — **never white**; pupil black =
transparent hole (nice on the waveguide); blink 45 % of 3.7 s slots for 140 ms; rim glow `(0.3,0.6,1.0)·0.6·uSluurpGlow`.
Glow envelope `glow = max(glow·e^{−dt/0.45}, impulse)`: flick 0.8, hit 1.0, snap 0.5, onset `0.15·uBass`,
crossfade → 1.0. Rope: `mix(cyan, hot magenta, uRopeTension)`, brightness `0.6 + 1.2·uRopeTension`, capsule
0.008 + additive halo (3× radius, exp falloff); free flight violet 35 %. Targets: eye-like discs in the scene
palette, iris looks at the sluurp; pulse ring + sparks; life flicker; gold variant in slot 5. GLSL for
`sluurpDE`/`sluurpColor` is in the toy design and is adopted verbatim (`sdEllipsoid`, `sluurpColor` → `toyColor` id 1).
`toysDE` starts with the `uToyBounds` early-out (§7.4).

### 4.10 Sound cues (SoundPool, pre-baked WAVs generated by `tools/make_sfx.py`)
| Cue | Trigger | Synthesis | Level |
|---|---|---|---|
| twang | flick | Karplus-Strong 80 ms, `180 + 240·tension` Hz | −6 dB |
| snap | re-tether with stretch | 30 ms noise BP 2 kHz + 90 Hz thump 120 ms | −8 dB |
| chime | hit | FM bell 1:3.01, index 2→0 over 600 ms, pentatonic by level, scene root | −3 dB |
| shimmer | clean / golden hit | 4-note arpeggio 60 ms apart, +1 octave | −6 dB |
| whoomp | growth (beat-quantised) | sine 80→40 Hz 500 ms, light saturation | −4 dB |
| whiff | near miss (`< 1.6·(r_b + r_t)`, once per pass; target winks `pulse 0.3`) | BP noise 800→300 Hz 120 ms | −14 dB |
| bloom | target spawn (scale 0→1 over 0.5 s, overshoot 1.15 at 0.3 s) | sine chord swell 300 ms | −14 dB |
| sigh | timeout | two sines a minor third down, 400 ms | −12 dB |
| bong / clang | fractal bounce | low bell (Nave, Hive) / metallic burst (Throat) / soft "plip" (Tidepool) | −8 dB |
| hum | continuous | 2 s loop, `rate 0.5 + 0.75·min(speed/2, 1)`, vol `0.15 + 0.25·uSluurpGlow`, 0 in attract | −18 dB |
| tick / select | menu | suite sounds | — |
Toy SFX bus 0.8 × Volume; music at Volume; SFX stay on when Music = Off (only the hum follows Music).

---

## 5. Targets ("fractal eyes")

| Kind | Radius | Lifetime (gaze-gated) | Motion | Reward |
|---|---|---|---|---|
| Fractal eye | `r_t = 0.08·0.97^level`, floor 0.055 | 25 s (+10 s if replacing a timeout) | orbit around the spawn point, radius 0.06, 0.6 rad/s | zoom +1 |
| Golden eye (every 5th hit, slot 5) | 0.07 | 12 s | figure-8, amplitude 0.20, 1.2 rad/s | zoom +2, shimmer, hue +0.10 |

Hit test each substep: `|sluurp − target| < r_b + r_t + 0.02`. Active count `clamp(2 + level/3, 2, 4)` + golden
slot; six only in attract.

Spawn (world space, relative to the current camera and gaze; rejection sampling, 40 attempts):
```
direction: uniform in the cone ±25° about the gaze (SPEC)      — navigation IS the hunt
distance : 55 % "swing range" ∈ [1.0, 1.4 + L0]   (reachable by a head swing while flying)
           45 % "flick range" ∈ (1.4 + L0, 3.0]   (levels 0–1: 80/20)
reject if: scene.de(p, zoom) < 2·r_t + 0.05        (open space only; 2D: plane point must be exterior with escape count ∈ [4, N))
        or outside scene.safeVolume(cam)·(1 + 0.08·level)
        or within 15° / 0.5 u of another active target
fallback : dead ahead, 1.3–1.7 u, first p with de(p) > 2·r_t along the gaze
```
Onboarding (fresh install only): #1 dead ahead at `1.4 + 0.9·L0` (a nod hits it); #2 20° to one side, same
radius (teaches swinging); #3 straight ahead at 2.4 u (teaches the flick; HUD whispers "tap to flick" if not
hit within 15 s, once per install). Edge-of-frame eye-shine (`toysEdge`): off-screen targets are projected,
clamped to the frame edge and drawn as a soft blob (uv radius 0.05, brightness `0.35 + 0.35·uBass`) with a
6-segment arc pointing inward; golden = gold, pulsing. With the ±25° cone most targets are on screen; the
eye-shine covers the ones you banked away from.

---

## 6. Music reactivity (core)
Build-time `tools/analyze_music.py` (ffmpeg → PCM → numpy) writes `assets/music/sceneN.env.json`: 20 ms hops of
`energy`, `bass` (< 150 Hz), `treble` (> 4 kHz), onset list, BPM, beat grid. Runtime indexes it with
`MediaPlayer.currentPosition` (pacing-independent): `uEnergy` = EMA τ 0.25 s; `uBass`/`uTreble` = EMA τ 0.12 s,
normalised to the track's 95th percentile; `uBeat` = onset pulse `1 → exp(−t/0.18)` with a **340 ms refractory**
(≤ 3 pulses/s at any BPM — the photosensitivity gate; 175 BPM eighth-notes would otherwise be 5.8 Hz).
Scene change: 1 s equal-power crossfade of two `MediaPlayer`s; looping; Music = Off pauses playback and
freezes the envelope at zero (the rooms breathe on `uTime` alone). Attribution lines come from
`assets/music/ATTRIBUTION.md` (mirrored into `res/values/strings.xml` by the build).

---

## 7. Rendering pipeline

### 7.1 Passes
```
[scene]  one FBO (RGBA16F colour + R16F depth attachment), allocated once at max scale 0.75 → 960×360;
         lower rungs render into sub-viewports (glViewport + uEyeOrigin/uEyeRes) — no reallocation, no hitch.
         Two eye rects per frame (or one when mono). Scene writes colour (footer × uFade) and gDepth.
[bloom]  2-pass separable 5-tap at FBO res (threshold 0.55 on lum, tinted uBloomTint, strength uBloomStrength)
[post]   per eye at 640×480, order:
   0 breath        uv = c + (uv − c)(1 + uBreath)                       (0.012·sin(2π·0.08t), sub-perceptual)
   1 echo          prev = echoFBO(reprojectPrev(c + rot2(uEchoRot)(uv − c)/uEchoZoom)), c = uEchoCentre + (uUvShift, 0)
                   prev = min(prev, 2.0)·uEchoTint; hdr = scene + uEchoMix·prev; write hdr → next echo FBO (ping-pong, RGBA16F 640×240 per eye rect)
   2 bloom add     hdr += bloomTex (bilinear upsample)
   3 aberration    radial about c; mag = uAberr = 0.002 + 0.01·uEnergy + 0.004·uBeat + 0.008·uHitPulse; Wild: prism (R,G,B along 3 directions 120° apart)
   4 vignette      × (1 − 0.35·smoothstep(0.55, 1.25, |uv|))              → periphery dissolves into the room
   5 tone-map      luminance Reinhard + core tint (below) × exposure
   6 gamma 1/2.2
   7 HUD composite (Canvas texture, alpha) — never fed back into the echo
```
**DECISION — hue-preserving tone-map** (spectacle §3.5) replaces per-channel `c/(1+c)`: per-channel Reinhard
desaturates every bright pixel toward white, manufacturing exactly the pale fields the waveguide punishes.
Both map 0 → 0 (black stays transparent). `post.frag` is core-owned, so this is not a scene-contract change;
it is recorded as a post-pass note in `SHADER_CONTRACT.md`.
```glsl
vec3 tonemap(vec3 c, vec3 coreTint) {
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    vec3 m = c / (1.0 + l);                                   // Reinhard on luminance → hue kept
    float hot = smoothstep(1.2, 3.0, l);                      // only true cores
    m = mix(m, coreTint * min(l / (1.0 + l), 1.0), hot * 0.6); // cores go warm/ice, never pure white
    return clamp(m, 0.0, 1.0);
}
```

### 7.2 Echo trails (setting Off / Soft / Wild)
Frame-rate normalised: `uEchoMix = min(0.90, exp(−dt/τ))` (+0.06·uHitPulse, cap 0.92); zoom/rotate are
per-frame amounts at 60 fps, raised to `dt/16.7 ms`; **world-locked** by sampling the previous frame through
`reprojectPrev()` (head rotation exact; translation approximated) so trails hang in the room instead of
smearing across it; speed gate `mix *= 1 − smoothstep(60°/s, 180°/s, |ω|)`; beat kick `zoom += 0.01·uBeat`
(inward in the Hive). The echo buffer survives scene changes (rooms bleed into each other). Menu open → echo off.

| Scene | Soft τ / zoom / rot | Wild τ / zoom / rot | centre | tint | feel |
|---|---|---|---|---|---|
| Tidepool | 0.10 s / 1.010 / +0.004 | 0.30 s / 1.030 / +0.012·sign(sin 0.05t) | (0,0) | pal hue +0.04 → +0.10 | ripples spiral outward |
| Nave | 0.08 / 1.006 / 0 | 0.22 / 1.020 / +0.006 | (0,0) | (0.95,1,0.9) → hue +0.06 | gold haze lingers |
| Throat | 0.12 / 1.015 / 0 | 0.35 / 1.045 / 0 | (0,0) vanishing point | (0.8,1,1) → hue +0.08 | hyperspace streaks |
| Hive | 0.10 / 1.000 / +0.003 | 0.28 / **0.985** / +0.010 | (0,0) | (1,0.85,1) → hue +0.05 | inward suction |
"hue-rotated tint" = `pal(uHue + Δ)` of the scene palette normalised to max 1 — trails drift through the
room's own colours (rainbow arcs, never grey smears).

### 7.3 Adaptive resolution ("rungs") — MUST
Rung ladder (scale, `uQuality`), each ≈ 0.75× the cost of the next; satisfies the spec clamp 0.3–0.75 and
default 0.5:
```
rung: 0(0.30,0.4) 1(0.35,0.5) 2(0.40,0.6) 3(0.45,0.7) 4(0.50,0.8) 5(0.50,1.0) 6(0.55,1.0) 7(0.62,1.0) 8(0.70,1.0) 9(0.75,1.0)
start 5 · per-scene caps: Tidepool 9 · Throat 7 · Hive 6 · Nave 5
Quality Low: cap 3 · Auto: scene cap · High: scene cap, thermal loop only reacts to SEVERE
```
Below rung 5 steps are traded before pixels (banding beats blur); below rung 2 stereo goes mono (§1.4).
Measurement: `GL_EXT_disjoint_timer_query` around scene+bloom+post; fallback missed-vsync ratio over 1 s.
Thresholds (GPU ms): 60 fps pacing hi 15 / lo 9 (SPEC); 30 fps pacing hi 26 / lo 16.
```kotlin
class RungController(private val sceneCap: Int, private val userCap: Int) {
    var rung = 5; private var ema = 12f; private var over = 0; private var under = 0; private var lastChangeNs = 0L
    fun onFrame(gpuMs: Float, hi: Float, lo: Float, nowNs: Long) {
        ema += (gpuMs - ema) * 0.15f
        over = if (gpuMs > hi) over + 1 else 0
        under = if (ema < lo) under + 1 else 0
        val cap = minOf(sceneCap, userCap)
        when {
            over >= 3 && nowNs - lastChangeNs > 250_000_000L && rung > 0  -> { rung--; lastChangeNs = nowNs; under = 0 }
            under >= 45 && nowNs - lastChangeNs > 1_500_000_000L && rung < cap -> { rung++; lastChangeNs = nowNs; over = 0 }
        }
    }
}
```
Down fast (3 bad frames, 250 ms dwell), up slow (45 good frames, 1.5 s dwell). Scene switch → `min(5, cap)`.
Menu open → rung 2, `uFade 0.35`, echo off.

### 7.4 Performance levers (ranked; 1–4 MUST, 5 SHOULD)
1. **Mono render when s = 0** (Stereo Off, rung ≤ 2, COOL): −50 %.
2. **Toy bounding sphere** `uToyBounds` (CPU: sphere enclosing sluurp + rope + active targets): `toysDE` begins
   `float db = length(p − uToyBounds.xyz) − uToyBounds.w; if (db > 0.0) { id = 0; return db; }` — a valid lower
   bound; skips the 19-primitive union for ~85 % of pixels (toys otherwise cost as much as a Menger DE per step).
3. **Rotation-exact depth reprojection**: scenes set `gDepth` (hit distance, −1 sky); the footer writes it to the
   R16F attachment; next frame `startT()` gives a conservative ray start (~40 → ~14 average steps on Nave/Hive).
   The scene MUST still verify `DE(ro + t·rd) > eps` at the start, else restart at 0.
4. **Bloom at FBO res**, upsampled (softer and cheaper than full-res taps).
5. **Coarse pre-pass for the Nave** (160×60 total, 32 steps, then ≤ 12 fine steps from `max(startT, 0.9·t_coarse)`) — the Nave is the budget-breaker; without 3+5 it is a 30 fps scene at rung 5.

Cost model (calibrate on build 1): E ≈ 100 G ops/s effective; P = 153 600 px at scale 0.5.
| Scene | ops/DE | steps @Q 1→0.3 | ops/px plain → with levers | est. ms @0.5 |
|---|---|---|---|---|
| Tidepool | 24/iter, ≤ 96 iters | — | ~1.1 k | 1.7 (3.8 @ 0.75) |
| Nave | ~560 (7 iters, trig) | 40→16 | 25 k → 9.5 k | 38 → 14.6 |
| Throat | ~155 (6 folds) | 64→32 | 11.5 k → 5 k | 17.7 → 7.4 |
| Hive | ~200 (8 iters) | 48→24 | 7.5 k → 4.5 k | 11.5 → 6.9 |
Frame budget (both eyes): scene ≤ 8 ms @60 / ≤ 15 ms @30; bloom 0.3; post 2.5–3 (bandwidth); echo write 0.2;
HUD upload ≤ 1 ms CPU (dirty only, two textures ping-pong). Totals ≤ 12 ms @60, ≤ 19 ms @30.

### 7.5 Thermal governor (slow loop) — SHOULD
Inputs: `PowerManager.currentThermalStatus` (+ listener), `getThermalHeadroom(10)` (NaN-safe), **battery
temperature** (`ACTION_BATTERY_CHANGED`, tenths °C — the input that actually moves on this device; the thermal
status has been observed stuck at 0 near 60 °C SoC).
```
BURST (60 fps) → CRUISE (30 fps) : timeInBurst > 120 s || headroom ≥ 0.80 || batt ≥ 37.0 °C || status ≥ LIGHT
CRUISE → COOL (30 fps, mono, rung cap 2) : headroom ≥ 0.95 || batt ≥ 40.0 °C || status ≥ SEVERE
COOL → CRUISE : batt ≤ 38.5 °C && status < SEVERE && headroom < 0.85, dwell ≥ 60 s
CRUISE → BURST : Quality = High (always burst, obeys only SEVERE), or a 20 s showcase burst on scene change if batt < 35 °C
```
30 fps = `RENDERMODE_WHEN_DIRTY` + `Choreographer` callback rendering on even vsyncs; physics substeps stay at
240 Hz; the prediction horizon follows the pacing (§2.3). Calibrate 37/40/38.5 °C on the first 15-minute session.
MAY: APL exposure servo (16×12 probe every 8th frame, `exposure *= clamp(0.12/meanLuma, 0.97, 1.03)`, bounded
[0.5, 1.5]) to hold mean luminance ≤ 13 % (vendor rule + flash margin).

### 7.6 Comfort invariants (never break)
Camera rotation = head, gain 1.0, latency-compensated · `uFov` matches the optics · nothing rolls except the
camera basis (HUD is screen-locked) · no FOV pulses, no camera-position modulation by music · zoom-level
change is a monotonic 0.8 s ease · involuntary translation ≤ 1.0 u/s, involuntary rotation ≤ 4°/s, roll 0 ·
tangential sluurp speed ≤ 6 u/s · full-frame brightness pulses ≤ ×1.6 and above ×1.3 for ≤ 150 ms; beat-driven
whole-field luminance ≤ 6 % p-p; local flashes < 10 % of the field; `uBeat` ≤ 3/s; target expiry flicker 2 Hz
±30 % local only · echo mix ≤ 0.90 (0.92 during the hit bump), feedback clamped at 2.0 · scene crossfade 1 s.

---

## 8. Waveguide colour rules and palettes (spectacle §1, verified with `design/palcheck.py`)
1. Background is exactly 0 (fog → black, exteriors `pow(u, 1.6)`, crevices via step-AO).
2. No pale fields: pixels with `sat < 0.25 && lum > 0.6` < 2 % of the frame, cores only; scenes run `boost()`
   (saturation ×1.35 about luminance) before returning; post tints cores toward `uCoreTint`.
3. Hue exclusion: 12°–43° (orange), 10°–48° at value < 0.55 (brown), reds below value 0.5. Gold 46°–58° allowed.
4. Green is the loud channel: lime `(0.6,1,0.2)` = hit/ring/kick accent in Nave/Throat; magenta `(1,0.27,0.85)`
   the accent in Tidepool/Hive; cyan/electric blue `(0,0.7,1)` = edges.
5. Glow everything: nothing thinner than ~2.5 FBO px, always with an `exp(−d·k)` halo.

IQ cosine `col = a + b·cos(2π(c·t + d))`, `d = D0 + ZSHIFT·uZoom + vec3(uHue)` (per-channel ZSHIFT changes the
colour *set* per level, not just the phase):

| Scene | a | b | c | D0 | ZSHIFT | coreTint | bloomTint | bloomStrength |
|---|---|---|---|---|---|---|---|---|
| Tidepool | (0.55, 0.40, 0.78) | (0.45, 0.40, 0.22) | (1, 1, **2**) | (0.00, 0.50, 0.50) | (0.02, 0.02, 0.04) | (0.80, 0.95, 1.00) ice | (0.6, 0.9, 1.0) | 0.35 |
| Nave | (0.50, 0.85, 0.40) + `a.b += min(0.02·L, 0.15)` | (0.50, 0.15, 0.40) | (1, 1, 1) | (0.00, 0.28, 0.55) | (0.04, 0.03, 0.02) | (1.00, 0.92, 0.55) gold | (1.0, 0.9, 0.5) | 0.45 |
| Throat | (0.30, 0.75, 0.60) | (0.30, 0.25, 0.40) | (1, 1, 1) | (0.30, 0.00, 0.60) | (0.00, 0.035, −0.025) | (0.80, 1.00, 0.70) | (0.4, 1.0, 0.8) | 0.35 |
| Hive | (0.85, 0.25, 0.80) | (0.15, 0.25, 0.20) | (1, 1, 1) | (0.00, 0.55, 0.35) | (0.00, 0.035, −0.025) | (1.00, 0.90, 0.75) | (1.0, 0.6, 0.9) | 0.45 |

Notes: Tidepool's `c.b = 2` keeps both the magenta and cyan poles saturated (min sat 0.55). Nave's gold is
built as `G ≥ 0.8R when B < 0.1` and the green→gold crossing passes through lime, never orange. Throat never
samples its dusty point: `t = 0.70 + 0.85·fract(t)`. The Hive palette has **no gold**; its gold/white-hot look
is emissive (specular + contact glow), which is how magenta and gold coexist without crossing orange.

Shared scene-side helpers (each scene file carries its own copy):
```glsl
vec3 boost(vec3 c) { float l = dot(c, vec3(0.2126, 0.7152, 0.0722)); return max(l + (c - l) * 1.35, 0.0); }
vec3 normalDE(vec3 p, float e) { const vec2 k = vec2(1.0, -1.0); int id;
    return normalize(k.xyy * map(p + k.xyy * e, id) + k.yyx * map(p + k.yyx * e, id)
                   + k.yxy * map(p + k.yxy * e, id) + k.xxx * map(p + k.xxx * e, id)); }
float edgeGlow(vec3 p) { return clamp(1.0 - dot(normalDE(p, 0.0008), normalDE(p, 0.02)), 0.0, 1.0); }
```

---

## 9. Scenes

Common `Scene` API (Kotlin, `scenes/Scene.kt`, core-owned):
```kotlin
abstract class Scene(val slot: Int, val displayName: String, val fragAsset: String, val musicAsset: String) {
    abstract val is2D: Boolean
    abstract val palette: Palette                 // a, b, c, d0, zshift, coreTint, bloomTint, bloomStrength
    abstract val echo: EchoParams                 // soft/wild τ, zoom, rot, centre
    abstract val speedScale: Float
    abstract val maxRung: Int
    abstract val interestConeDeg: Float
    abstract val chimeRootHz: Float
    abstract fun de(p: Vec3, zoom: Float): Float  // CPU mirror of the shader DE (2D: see Tidepool.boundary)
    fun grad(p: Vec3, zoom: Float): Vec3          // tetrahedron 4-tap of de(), e = 0.002
    abstract fun startPose(): Pose                // position + heading on entry
    open fun current(p: Vec3, t: Float): Vec3 = Vec3.ZERO   // |v| ≤ 0.15
    abstract fun autoFlight(t: Float, pos: Vec3): Vec3      // heading for Head tracking Off / attract
    open fun field(p: Vec3): Vec3 = Vec3.ZERO     // extra force on the sluurp (§4.8)
    abstract fun safeVolume(cam: Pose): Volume    // spawn volume (intersected with the ±25° cone, 1–3 u)
    abstract fun zoomParams(level: Int): ZoomParams
    open fun onLevelChanged(level: Int) {}
}
```
Uniform ownership: the core sets everything in the contract; scenes read. The four GLSL sketches below are
the spectacle design's, re-based on gaze flight (camera always `uCamPos/uCamRot`), with `gDepth`, `toysEdge`
and `startT` added.

### 9.1 Scene 1 — The Tidepool (JULIA DRIFT, 2D)
**Room.** You look down into a phosphorescent rock-pool. The water is a Phoenix–Julia set (plumes and feathers,
not the familiar spirals). It breathes; rings ripple out on the beat. Your gaze slides the pool under you and
the speed level sinks you slowly into its edge; when you swing the sluurp a bright filament follows it through
the set and the whole set morphs, because the sluurp's offset bends the Julia constant.

**Formula.** `z_{n+1} = z_n² + c + p·z_{n−1}` (Phoenix coupling, real `p = 0.30 + 0.20 sin(0.07t) + 0.18·uBass`);
`c = mix(SEED[L mod 8], SEED[(L+1) mod 8], smoothstep(fract(uZoom))) + b`, seeds
`(-0.7269,0.1889) (-0.4,0.6) (0.285,0.01) (-0.8,0.156) (-0.835,-0.2321) (0.355,0.355) (-0.54,0.54) (-0.1,0.651)`
(dendrite → spiral → feather → island; a hit melts the set into the next seed over 0.8 s).
**Sluurp coupling.** `b = clamp(0.34·(uSluurp.xy − uAnchor.xy), −0.3, 0.3)` (projected uv offset; a full tethered
swing of L0 = 0.23 u is Δuv ≈ 0.47 → |b| ≈ 0.16; free flight pushes to the clamp — dendrite to dust and back)
**[tune 0.25–0.45]**. The sluurp is also an orbit trap (`min |z − 2.2b|`) → its filament through the set.

Orbit traps: point `min|z|²` (cores/phase) · line `min(|Re|, |Im|)` (veins, ×1.4 on beat) · ring
`min||z| − r(t)|`, `r = 0.55 + 0.25 sin 0.21t + 0.15·uBeat` (breathing halos; hit: +0.3) · sluurp filament.
Colouring: `u = smoothIter/N`; base `pal(1.4u + 0.15√trapPt)`; exterior `pow(u, 1.6)`, interior `0.55×` trap
colour; veins `exp(−22·trapLine)` at palette phase +0.5; rings teal `(0.25,0.9,0.85)`; filament hot magenta
`(1,0.45,0.95)·(0.5 + 0.5·uSluurpGlow)`; treble sparkle on veins.

Zoom levels (`depth = uZoom + uTravel` drives scale and iterations; `L = floor(uZoom)` drives seed and fold):
| L | plane half-height (`1.6·0.72^depth`) at uTravel 0 | seed | kaleido wedges | iters (×uQuality, cap 96) |
|---|---|---|---|---|
| 0 | 1.60 | s0 | – | 40 |
| 1 | 1.15 | s1 | – | 48 |
| 2 | 0.83 | s2 | – | 56 |
| 3 | 0.60 | s3 | 3 | 64 |
| 4 | 0.43 | s4 | 4 | 72 |
| 5 | 0.31 | s5 | 5 | 80 |
| 6 | 0.22 | s6 | 6 | 88 |
| 7+ | 0.16·0.72^(L−7) | wrap | min(8, L) | 96 |
Music: beat → ring +0.15, veins ×(1+0.4b), echo kick · bass → Phoenix p +0.18, scale ×(1+0.06) breath · treble
→ vein sparkle · energy → ring brightness `0.6 + 0.4E`, plane rotation `0.02 + 0.04E` rad/s, hue rate.
Gaze: §3.3 (pan = `uCamPos.xy`, plane rotation = `uCamPos.z` = slow drift + head roll; dive = `uTravel`).
Recentre zeroes the pan input. Interest cone 45°: pan is tanh-soft-limited so the set never leaves the frame.
Safe volume: the ±25° cone at 1–3 u; the target's plane point must be exterior with escape count in `[4, N)`.
Stereo: s = 0.004 for the toys; plane at `eyeUV()` → 7.8 px behind the convergence plane. Rung cap 9.
Echo: §7.2. Interaxial: Subtle 0.004 / Deep 0.008. Chime root D3.

```glsl
const vec3 PA = vec3(0.55, 0.40, 0.78), PB = vec3(0.45, 0.40, 0.22), PC = vec3(1.0, 1.0, 2.0), PD = vec3(0.00, 0.50, 0.50);
const vec3 ZSH = vec3(0.02, 0.02, 0.04);
const vec2 SEEDS[8] = vec2[8](vec2(-0.7269, 0.1889), vec2(-0.4, 0.6), vec2(0.285, 0.01), vec2(-0.8, 0.156),
                              vec2(-0.835, -0.2321), vec2(0.355, 0.355), vec2(-0.54, 0.54), vec2(-0.1, 0.651));
vec3 boost(vec3 c) { float l = dot(c, vec3(0.2126, 0.7152, 0.0722)); return max(l + (c - l) * 1.35, 0.0); }
vec2 kfold(vec2 p, float k) { float w = 6.2831853 / k; float a = atan(p.y, p.x);
    a = abs(mod(a + 0.5 * w, w) - 0.5 * w); return length(p) * vec2(cos(a), sin(a)); }

vec3 render(vec2 uv) {
    int   L = int(floor(uZoom)); float f = smoothstep(0.0, 1.0, fract(uZoom));
    float depth = uZoom + uTravel;                                  // level growth + gaze dive
    float N = min(96.0, (40.0 + 8.0 * depth) * uQuality);
    float scale = 1.6 * pow(0.72, depth) * (1.0 + 0.06 * uBass);
    vec2 p = rot2(uCamPos.z) * uv * scale;                          // plane sampled at eyeUV() → sits behind the toys
    float k = (uZoom < 3.0) ? 0.0 : min(8.0, 3.0 + floor(uZoom - 3.0));
    if (k > 0.0) p = kfold(p, k);
    p += uCamPos.xy;                                                // gaze pan
    vec2 b = clamp(0.34 * (uSluurp.xy - uAnchor.xy), -0.3, 0.3);     // swing → Julia constant
    vec2 c = mix(SEEDS[L & 7], SEEDS[(L + 1) & 7], f) + b;
    float ph = 0.30 + 0.20 * sin(uTime * 0.07) + 0.18 * uBass;
    float ringR = 0.55 + 0.25 * sin(uTime * 0.21) + 0.15 * uBeat + 0.3 * uHitFlash;
    float trapPt = 1e9, trapLine = 1e9, trapRing = 1e9, trapSluurp = 1e9;
    vec2 z = p, zp = vec2(0.0); float n = 0.0;
    for (int i = 0; i < 96; i++) {
        if (float(i) >= N) break;
        vec2 zn = vec2(z.x * z.x - z.y * z.y, 2.0 * z.x * z.y) + c + ph * zp;   // Phoenix
        zp = z; z = zn;
        float r2 = dot(z, z);
        trapPt = min(trapPt, r2); trapLine = min(trapLine, min(abs(z.x), abs(z.y)));
        trapRing = min(trapRing, abs(sqrt(r2) - ringR)); trapSluurp = min(trapSluurp, length(z - 2.2 * b));
        n = float(i); if (r2 > 64.0) break;
    }
    bool inside = dot(z, z) <= 64.0;
    float sn = inside ? N : (n + 4.0 - log2(max(log2(dot(z, z)), 1.0)));
    float u = clamp(sn / N, 0.0, 1.0);
    vec3 d3 = PD + ZSH * uZoom + vec3(uHue);
    vec3 col = pal(1.4 * u + 0.15 * sqrt(trapPt), PA, PB, PC, d3) * (inside ? 0.55 : pow(u, 1.6));
    float veins = exp(-trapLine * 22.0) * (0.8 + 0.4 * uBeat);
    col += pal(2.0 * u + 0.5, PA, PB, PC, d3) * veins * 1.2;
    col += vec3(0.25, 0.90, 0.85) * exp(-trapRing * 16.0) * 0.6 * (0.6 + 0.4 * uEnergy);
    col += vec3(1.00, 0.45, 0.95) * exp(-trapSluurp * 9.0) * 1.5 * (0.5 + 0.5 * uSluurpGlow);
    col += veins * uTreble * 0.5 * noise3(vec3(p * 9.0, uTime * 2.5));
    col = boost(col) * (1.0 - 0.25 * smoothstep(0.6, 1.3, length(uv)));   // the pool "cups"
    gDepth = -1.0;                                                        // 2D: no reprojection depth
    return toys2D(uv, col) + toysEdge(uv);
}
```
Kotlin mirror: `Tidepool.boundary(p: Vec2, depth): Float` = the same loop returning the smooth escape count
(≈ 96 iterations, called ≤ 8×/frame for the dive gate, the sluurp bounce and spawn rejection).
Budget 0.35 GFLOP/frame worst case — the 60 fps showcase scene.

### 9.2 Scene 2 — The Nave (MANDELBULB CATHEDRAL)
**Room.** An object the size of a cathedral hangs in your room. You fly around it and into its canyons; the
DE mirror slows you to a hover as a rib approaches and lets you slide along it. Its surface is dark teal
stone; the seams glow gold like lead-lit stained glass (plane orbit trap); a gold haze hangs in the recesses
(distance-field glow); every kick makes the whole object flex (power kick). Hits ring it like a bell.

**Formula.** y-up Mandelbulb, `power = 8 + 0.25·uZoom + 0.35·uBeat + 0.5·uHitFlash + 0.15 sin(0.11t)`;
`twist = uZoom ≥ 5 ? 0.15(uZoom − 4) : 0` (rotY per iteration → spiral shells); `iters = clamp(6 + 0.5·uZoom, 6, 9)`;
`DE = 0.5·ln r·r/dr`; bass breath `p /= 1 + 0.025·uBass`. Low-quality fallback (`uQuality < 0.45`): fixed power 8 via
the trig-free algebraic form, modulation off. Bounding sphere r = 1.25 → ray/sphere entry skip.
Orbit traps: radius (phase), plane `min|z.y|` (gold ribs `exp(−18·trapPlane)`), axis `min|z.xz|` (0.3· hue).
Colouring: `base = pal(2.2·trapR + 0.3·trapAxis)` × `(0.15 + 0.85·diffuse)` from a fixed key `(0.4,0.8,−0.3)` × AO
`pow(1 − steps/max, 1.5)`; teal Fresnel rim; gold ribs `(0.6 + 0.6E)`; treble motes; sluurp lantern
`(0.4 + 1.2·uSluurpGlow)` quadratic falloff; fog `exp(−t(0.16 + 0.008·uZoom))`; gold god-rays
`Σ exp(−28d)·0.012·(0.7 + 0.6E)(1 + 0.3·uBeat)`. Nebula backdrop × `uLookAway`.

Zoom levels (DECISION: the spectacle's shrinking orbit radius is gone — the player chooses distance; growth is
power, twist, iterations, fog and palette):
| L | power base | iters | twist/iter | fog k | note |
|---|---|---|---|---|---|
| 0 | 8.00 | 6 | 0 | 0.16 | full object |
| 1 | 8.25 | 6 | 0 | 0.16 | |
| 2 | 8.50 | 7 | 0 | 0.17 | |
| 3 | 8.75 | 7 | 0 | 0.18 | |
| 4 | 9.00 | 8 | 0 | 0.19 | |
| 5 | 9.25 | 8 | 0.15 | 0.20 | shells spiral |
| 6 | 9.50 | 9 | 0.30 | 0.21 | |
| 7+ | 9.75+ | 9 | 0.45+ | 0.22 | `a.b` lifts (teal) |
Music: beat → power +0.35, god-rays ×(1+0.3b), trail kick · bass → object scale ×(1+0.025b), rib width ·
treble → motes · energy → glow density, rib brightness, hue rate.
Flight: `speedScale 1.0`; start pose `(0, 0.3, −3.0)` heading `+z` (toward the bulb at the origin); current:
if `|pos| > 5` a 0.15 u/s pull toward the origin (you cannot lose the cathedral); auto-flight = orbit at r 2.6.
Sluurp field `0.6·normalize(−p)`. Safe volume: cone ∩ `{ |p| < 4.5 }` ∩ `de(p) > 2r_t + 0.05`. Interest cone 40°.
Rung cap 5 (30 fps room). Interaxial Subtle 0.004 / Deep 0.008. Chime root G2.

```glsl
const vec3 PA = vec3(0.50, 0.85, 0.40), PB = vec3(0.50, 0.15, 0.40), PC = vec3(1.0), PD = vec3(0.00, 0.28, 0.55);
const vec3 ZSH = vec3(0.04, 0.03, 0.02);
float gPower, gTwist; int gIter;
float bulbDE(vec3 p, out float trapR, out float trapPlane, out float trapAxis) {
    p /= (1.0 + 0.025 * uBass);
    vec3 z = p; float dr = 1.0, r = 0.0; trapR = 1e9; trapPlane = 1e9; trapAxis = 1e9;
    for (int i = 0; i < 9; i++) {
        if (i >= gIter) break;
        r = length(z); if (r > 4.0) break;
        float theta = acos(z.y / r) * gPower, phi = atan(z.z, z.x) * gPower;
        dr = pow(r, gPower - 1.0) * gPower * dr + 1.0;
        float zr = pow(r, gPower);
        z = zr * vec3(sin(theta) * cos(phi), cos(theta), sin(theta) * sin(phi));
        if (gTwist > 0.0) z = rotY(gTwist) * z;
        z += p;
        trapR = min(trapR, r); trapPlane = min(trapPlane, abs(z.y)); trapAxis = min(trapAxis, length(z.xz));
    }
    return 0.5 * log(r) * r / dr * (1.0 + 0.025 * uBass);
}
float map(vec3 p, out int id) { float a, b, c; float d = bulbDE(p, a, b, c); id = 0;
    int tid; float dt = toysDE(p, tid); if (dt < d) { d = dt; id = tid; } return d; }
// normalDE / boost / edgeGlow as §8
vec3 render(vec2 uv) {
    gPower = 8.0 + 0.25 * uZoom + 0.35 * uBeat + 0.5 * uHitFlash + 0.15 * sin(uTime * 0.11);
    gTwist = (uZoom >= 5.0) ? 0.15 * (uZoom - 4.0) : 0.0;
    gIter  = int(clamp(6.0 + 0.5 * uZoom, 6.0, 9.0));
    vec3 ro = uCamPos, rd = rayDir(uv);
    vec2 uvGeom = uv - vec2(uUvShift, 0.0);
    float t = startT(uvGeom, ro, rd);                                  // reprojected start (0 if invalid)
    { int id0; if (map(ro + rd * t, id0) < 0.002) t = 0.0; }           // conservative check
    float maxSteps = floor(mix(40.0, 80.0, uQuality));
    float glow = 0.0, steps = 0.0; int id = 0; bool hit = false;
    for (int i = 0; i < 80; i++) {
        if (float(i) >= maxSteps) break;
        float d = map(ro + rd * t, id);
        glow += exp(-d * 28.0) * 0.012 * (0.7 + 0.6 * uEnergy) * (1.0 + 0.3 * uBeat);
        if (d < 0.0012 * t + 0.0006) { hit = true; break; }
        t += d * 0.85; steps = float(i); if (t > 12.0) break;
    }
    vec3 d3 = PD + ZSH * uZoom + vec3(uHue);
    vec3 col = vec3(0.0);
    if (hit) {
        gDepth = t;
        vec3 p = ro + rd * t; vec3 n = normalDE(p, 0.0008);
        if (id != 0) col = toyColor(id, p, n);
        else {
            float a, b, c; bulbDE(p, a, b, c);
            float ao = pow(1.0 - steps / maxSteps, 1.5);
            vec3 PAz = PA + vec3(0.0, 0.0, min(0.02 * uZoom, 0.15));
            vec3 base = pal(2.2 * a + 0.3 * c, PAz, PB, PC, d3);
            float diff = max(dot(n, normalize(vec3(0.4, 0.8, -0.3))), 0.0);
            float rim  = pow(1.0 - max(dot(n, -rd), 0.0), 3.0);
            col  = base * (0.15 + 0.85 * diff) * ao;
            col += vec3(1.0, 0.85, 0.35) * exp(-b * 18.0) * (0.6 + 0.6 * uEnergy) * ao;      // gold ribs
            col += rim * vec3(0.2, 1.0, 0.75) * 0.8;                                           // teal fresnel
            col += vec3(1.0, 0.9, 0.5) * step(0.985, hash21(floor(p.xy * 40.0) + floor(p.z * 40.0))) * uTreble * 2.0;
            vec3 lb = uSluurp.xyz - p; float lr = length(lb);
            col += base * max(dot(n, lb / lr), 0.0) / (1.0 + 2.0 * lr * lr) * (0.4 + 1.2 * uSluurpGlow) * vec3(0.9, 1.0, 0.9);
        }
        col *= exp(-t * (0.16 + 0.008 * uZoom));
    } else {
        gDepth = -1.0;
        col += uLookAway * 0.08 * pal(0.5 * noise3(rd * 2.0 + uTime * 0.02) + 0.3 * noise3(rd * 5.0), PA, PB, PC, d3);
    }
    col += glow * vec3(1.0, 0.85, 0.35);
    return boost(col) + toysEdge(uv);
}
```
Kotlin `Nave.de(p, zoom)` mirrors `bulbDE` with `power = 8 + 0.25·zoom`, `iters` per table, no beat terms
(≈ 1 µs). Budget ≈ 3.4 GFLOP/frame plain; with `startT` + coarse pre-pass ≈ 14.6 ms at scale 0.5.

### 9.3 Scene 3 — The Throat (MENGER TUNNEL)
**Room.** You fly through an infinite Menger sponge, cell after cell, lattice lit only at its edges (electric
blue), with lime scan-rings launched by every kick that race toward you and pass through you. Look where you
want to go; the lattice gets finer the deeper you are; bass widens the throat; the beat snaps the lattice.
World scale 2: cells are 4 u long, the central hole is 0.667 half-wide, and the DE mirror keeps you in the voids.

**Formula.** Cell `p.z = mod(p.z, 4) − 2; p /= 2; p.xy /= (1 + 0.08·uBass); p.xy = rot2(spin)·p.xy`; per fold
`p = |p|; sort xyz desc; [i ≥ 1 && L ≥ 4: octahedral plane fold about (1,1,0)/√2]; p.xy = rot2(τ)·p.xy` with
`τ = min(0.35, 0.05·uZoom) + 0.15·uBeat²`; `p = p·s − (s − 1)`, `s = 3 + 0.5·uBeat²`; `if p.z < −1: p.z += 2`;
`folds = clamp(3 + L, 3, 7)`; `DE = box(|p| − 1)/Π s`. Hole safety: a point at radius 0.22 (cell units) maps to
|y| = 1.34 after the first fold and still escapes after a twist ≤ 0.5 rad (1.34·cos 28.6° = 1.18 > 1).
Orbit traps: point `min|p|` (hue with per-cell step `+0.08·floor(z/4)`), axis (vein lines), edge glow.
Colouring: `t = 0.70 + 0.85·fract(1.3·trapPt + 0.08·cell)`; faces `base·0.25·AO`; edges `(0,0.7,1)·edge·2.2`;
veins lime `exp(−30·trapAxis)`; scan-ring `exp(−(6(z − ringZ))²)·uBeat` lime ×2.5 with `ringZ = cam.z + 6·uBeat`;
hit rings at each target's z (`uTargetPulse`); fog `exp(−t(0.32 − 0.08E))`; blue glow `Σ exp(−40d)·0.006`;
speed streaks: edges brightened `×(1 + 0.4·uSpeed/3.5)` (the faster you fly, the hotter the lattice).

Zoom levels (DECISION: the spectacle's post-pass 4-fold kaleido at L≥5 is dropped — mirroring the frame about
the centre breaks "look where you want to go"; the in-fractal octahedral fold at L≥4 delivers the crystalline step):
| L | folds | twist/fold | spin (rad/s) | note |
|---|---|---|---|---|
| 0 | 3 | 0.00 | 0.10 | chunky lattice |
| 1 | 4 | 0.05 | 0.15 | |
| 2 | 5 | 0.10 | 0.20 | |
| 3 | 6 | 0.15 | 0.25 | |
| 4 | 7 | 0.20 | 0.30 | crystalline (octahedral fold) |
| 5 | 7 | 0.25 | 0.35 | |
| 6 | 7 | 0.30 | 0.40 | |
| 7+ | 7 | 0.35 cap | 0.45 | palette keeps stepping |
Music: beat → fold snap, twist +0.15b², scan-ring, echo zoom +0.01 · bass → throat widens · treble → edge
flicker, prism · energy → fog lift, hue rate. (No speed modulation.)
Flight: `speedScale 1.6`; start pose `(0, 0, 0.5)` heading `+z`; current: none; auto-flight down +z with wobble.
Sluurp field: turbulence. Safe volume: cone ∩ `de(p) > 2r_t + 0.05` (targets sit in the voids; recycled when
behind). Interest cone 180°. Rung cap 7. Interaxial Subtle 0.004 / Deep 0.008. Chime root E3.

```glsl
const vec3 PA = vec3(0.30, 0.75, 0.60), PB = vec3(0.30, 0.25, 0.40), PC = vec3(1.0), PD = vec3(0.30, 0.00, 0.60);
const vec3 ZSH = vec3(0.0, 0.035, -0.025); const float WS = 2.0;
int gFolds; float gKick, gSpin, gTwist;
float mengerDE(vec3 p, out float trapPt, out float trapAxis) {
    p.z = mod(p.z, 2.0 * WS) - WS; p /= WS; p.xy /= (1.0 + 0.08 * uBass); p.xy = rot2(gSpin) * p.xy;
    float s = 1.0; trapPt = 1e9; trapAxis = 1e9;
    for (int i = 0; i < 7; i++) {
        if (i >= gFolds) break;
        p = abs(p);
        if (p.x < p.y) p.xy = p.yx; if (p.x < p.z) p.xz = p.zx; if (p.y < p.z) p.yz = p.zy;
        if (i >= 1 && uZoom >= 4.0) { const vec3 nn = vec3(0.70710678, 0.70710678, 0.0); p -= 2.0 * min(0.0, dot(p, nn)) * nn; }
        p.xy = rot2(gTwist) * p.xy;
        float sc = 3.0 + 0.5 * gKick; p = p * sc - (sc - 1.0); if (p.z < -1.0) p.z += 2.0; s *= sc;
        trapPt = min(trapPt, length(p)); trapAxis = min(trapAxis, min(p.x, min(p.y, p.z)));
    }
    vec3 q = abs(p) - 1.0;
    return (length(max(q, 0.0)) + min(max(q.x, max(q.y, q.z)), 0.0)) / s * WS * (1.0 + 0.08 * uBass);
}
float map(vec3 p, out int id) { float a, b; float d = mengerDE(p, a, b); id = 0; int tid; float dt = toysDE(p, tid); if (dt < d) { d = dt; id = tid; } return d; }
vec3 render(vec2 uv) {
    gFolds = int(clamp(3.0 + floor(uZoom), 3.0, 7.0)); gKick = uBeat * uBeat;
    gSpin  = (0.10 + 0.05 * min(uZoom, 7.0)) * uTime; gTwist = min(0.35, 0.05 * uZoom) + 0.15 * gKick;
    vec3 ro = uCamPos, rd = rayDir(uv);
    float t = startT(uv - vec2(uUvShift, 0.0), ro, rd) * 0.85;       // translation → extra 15 % margin
    { int id0; if (map(ro + rd * t, id0) < 0.002) t = 0.0; }
    float maxSteps = floor(mix(48.0, 96.0, uQuality));
    float glow = 0.0, steps = 0.0; int id = 0; bool hit = false;
    for (int i = 0; i < 96; i++) {
        if (float(i) >= maxSteps) break;
        float d = map(ro + rd * t, id);
        glow += exp(-d * 40.0) * 0.006;
        if (d < 0.0009 * t + 0.0004) { hit = true; break; }
        t += d * 0.9; steps = float(i); if (t > 14.0) break;
    }
    vec3 d3 = PD + ZSH * uZoom + vec3(uHue);
    vec3 col = vec3(0.0); gDepth = hit ? t : -1.0;
    if (hit) {
        vec3 p = ro + rd * t; vec3 n = normalDE(p, 0.0008);
        if (id != 0) col = toyColor(id, p, n);
        else {
            float a, b; mengerDE(p, a, b);
            float cell = floor(p.z / (2.0 * WS));
            vec3 base = pal(0.70 + 0.85 * fract(1.3 * a + 0.08 * cell), PA, PB, PC, d3);
            float ao = pow(1.0 - steps / maxSteps, 1.4);
            float edge = edgeGlow(p) * (0.8 + 0.4 * uTreble * hash21(floor(p.xy * 30.0) + floor(uTime * 20.0)));
            col  = base * 0.25 * ao;
            col += vec3(0.0, 0.7, 1.0) * edge * 2.2 * (1.0 + 0.4 * min(uSpeed / 3.5, 1.0));
            col += vec3(0.6, 1.0, 0.2) * exp(-b * 30.0) * 0.5 * ao;
        }
        float ringZ = uCamPos.z + 6.0 * uBeat;
        col += vec3(0.6, 1.0, 0.2) * exp(-pow((p.z - ringZ) * 6.0, 2.0)) * uBeat * 2.5;
        for (int i = 0; i < 6; i++)
            if (uTargets[i].w > 0.0) col += vec3(0.6, 1.0, 0.2) * exp(-pow((p.z - uTargets[i].z) * 8.0, 2.0)) * uTargetPulse[i] * 3.0;
        col *= exp(-t * (0.32 - 0.08 * uEnergy));
    }
    col += glow * vec3(0.1, 0.6, 1.0) * (0.6 + 0.4 * uEnergy);
    return boost(col) + toysEdge(uv);
}
```
Kotlin `Throat.de(p, zoom)` mirrors `mengerDE` with `gKick = 0`, `gSpin` from `uTime` (the core passes time),
folds per table. Budget ≈ 1 GFLOP/frame; the 14 u far clip and fog bound the long rays.

### 9.4 Scene 4 — The Hive (APOLLONIAN BLOOM)
**Room.** Pitch dark. You drift through the cavities of a sphere packing the size of a hangar, and the only
light is the sluurp. Swing it and magenta walls of kissing spheres appear and vanish; where spheres touch,
gold contact points glow; bass makes the lamp reach farther; every hit is a flashbulb that reveals the cave
for 0.7 s. The cave breathes toward you on the kick and the echo trail is inverted (zoom 0.985) — trails are
sucked inward. World scale 4 (unit cell 8 u; largest voids ≥ 1.4 u — room for the tether).

**Formula.** `p /= 4; p = rotY(0.2·uZoom)·rotX(0.11·uZoom)·p; [L ≥ 4: p.xz = |p.xz|]`; iterate
`p = −1 + 2·fract(0.5p + 0.5); r² = p·p; k = max(s/r², 1); p *= k; scale *= k`; `s = 1.02 + 0.09·uZoom` (1.02 → 1.65:
foam → bubbles → crystal), `iters = clamp(5 + 0.5·uZoom, 5, 9)`; `DE = 4·(0.25|p.y|/scale) − bloom`,
`bloom = 0.004·uEnergy + 0.012·uBeat` (walls pulse toward you). Orbit traps: inversion `min|k − 1|` (gold contact
points `exp(−30·trapK)`), radius `min r²` (phase).
Lighting (the point of the room): key = sluurp, `att = 1/(1 + 9d²/R²)`, `R = 1.6 + 0.1·min(uZoom,7) + 1.4·uBass +
1.0·uSluurpGlow + 0.3·uBeat`; diffuse × base; Blinn spec power 60 in `(1,0.9,0.7)·2.5` (the only white-hot pixels,
single-pixel highlights). Target lamps pink radius 0.9; on hit swell to 3.9 and shift to gold ×3.1 (flashbulb);
`uHitFlash` adds a global `R += 4·uHitFlash` so the whole cave shows for 0.7 s. Contact glow (faint
self-emission so the cave is never entirely invisible); treble specks; AO; fog `exp(−0.35t)`, far clip 6.

Zoom levels:
| L | s | iters | twist (rotY, rotX) | mirror | lamp R base | inflate base |
|---|---|---|---|---|---|---|
| 0 | 1.02 | 5 | 0, 0 | – | 1.6 | 0.000 |
| 1 | 1.11 | 5 | 0.2, 0.11 | – | 1.7 | 0.001 |
| 2 | 1.20 | 6 | 0.4, 0.22 | – | 1.8 | 0.002 |
| 3 | 1.29 | 6 | 0.6, 0.33 | – | 1.9 | 0.003 |
| 4 | 1.38 | 7 | 0.8, 0.44 | xz | 2.0 | 0.004 |
| 5 | 1.47 | 7 | 1.0, 0.55 | xz | 2.1 | 0.004 |
| 6 | 1.56 | 8 | 1.2, 0.66 | xz | 2.2 | 0.004 |
| 7+ | 1.65 cap | 9 | +0.2/+0.11 per L | xz | 2.3 | 0.004 |
Music: beat → inflate +0.012, lamp +0.3, echo zoom −0.01 · bass → lamp reach +1.4 (bass lets you see further) ·
treble → gold specks, prism · energy → inflate baseline, fog lift, hue rate.
Flight: `speedScale 0.8`; start pose = centre of the largest void of the L0 packing (void finder, below),
heading toward the nearest contact-glint; current: none; auto-flight = Lissajous inside the void. A level-up
changes `s` around the player; the 0.8 s tween plus the push-out (§3.2) keep the eye out of the surface.
Sluurp field: centre pull toward the gaze line. Safe volume: cone ∩ `de(p) > 2r_t + 0.06`. Interest cone 180°.
Rung cap 6. Interaxial Subtle 0.004 / Deep 0.008. Chime root A2.

Void finder (Kotlin, `onLevelChanged` and scene entry): 24³ coarse `de` probes over one unit cell (~14 k × 9
iters ≈ 1 ms) → largest `de`, then 3 gradient-ascent nudges. Used for the start pose, the auto-flight centre
and, when `de(pos) < 0.12` after a level change, as the direction of the push-out.

```glsl
const vec3 PA = vec3(0.85, 0.25, 0.80), PB = vec3(0.15, 0.25, 0.20), PC = vec3(1.0), PD = vec3(0.00, 0.55, 0.35);
const vec3 ZSH = vec3(0.0, 0.035, -0.025); const float WS = 4.0;
float gS, gBloom; int gIter; mat3 gTwist;
float apollonianDE(vec3 p, out float trapK, out float trapR) {
    p = gTwist * (p / WS); if (uZoom >= 4.0) p.xz = abs(p.xz);
    float scale = 1.0; trapK = 1e9; trapR = 1e9;
    for (int i = 0; i < 9; i++) {
        if (i >= gIter) break;
        p = -1.0 + 2.0 * fract(0.5 * p + 0.5);
        float r2 = dot(p, p); float k = max(gS / r2, 1.0);
        p *= k; scale *= k; trapK = min(trapK, abs(k - 1.0)); trapR = min(trapR, r2);
    }
    return WS * 0.25 * abs(p.y) / scale - gBloom;
}
float map(vec3 p, out int id) { float a, b; float d = apollonianDE(p, a, b); id = 0; int tid; float dt = toysDE(p, tid); if (dt < d) { d = dt; id = tid; } return d; }
vec3 lamp(vec3 p, vec3 n, vec3 rd, vec3 lp, float R, vec3 tint, vec3 base, float specW) {
    vec3 L = lp - p; float d = length(L); L /= d;
    float att = 1.0 / (1.0 + 9.0 * d * d / (R * R));
    float diff = max(dot(n, L), 0.0); float spec = pow(max(dot(n, normalize(L - rd)), 0.0), 60.0);
    return (base * diff * 1.4 + vec3(1.0, 0.9, 0.7) * spec * specW) * att * tint;
}
vec3 render(vec2 uv) {
    gS = 1.02 + 0.09 * min(uZoom, 7.0); gIter = int(clamp(5.0 + 0.5 * uZoom, 5.0, 9.0));
    gBloom = 0.004 * uEnergy + 0.012 * uBeat; gTwist = rotY(0.2 * uZoom) * rotX(0.11 * uZoom);
    vec3 ro = uCamPos, rd = rayDir(uv);
    float t = startT(uv - vec2(uUvShift, 0.0), ro, rd) * 0.85;
    { int id0; if (map(ro + rd * t, id0) < 0.002) t = 0.0; }
    float maxSteps = floor(mix(40.0, 72.0, uQuality));
    float steps = 0.0; int id = 0; bool hit = false;
    for (int i = 0; i < 72; i++) {
        if (float(i) >= maxSteps) break;
        float d = map(ro + rd * t, id);
        if (d < 0.001 * t + 0.0005) { hit = true; break; }
        t += d * 0.9; steps = float(i); if (t > 6.0) break;
    }
    vec3 col = vec3(0.0); gDepth = hit ? t : -1.0;
    if (hit) {
        vec3 p = ro + rd * t; vec3 n = normalDE(p, 0.0008);
        if (id != 0) col = toyColor(id, p, n);
        else {
            float a, b; apollonianDE(p, a, b);
            vec3 d3 = PD + ZSH * uZoom + vec3(uHue);
            vec3 base = pal(3.0 * b + 0.5 * a, PA, PB, PC, d3);
            float ao = pow(1.0 - steps / maxSteps, 1.3);
            float R = 1.6 + 0.1 * min(uZoom, 7.0) + 1.4 * uBass + 1.0 * uSluurpGlow + 0.3 * uBeat + 4.0 * uHitFlash;
            col = lamp(p, n, rd, uSluurp.xyz, R, vec3(1.0), base, 2.5) * ao;                     // the sluurp lamp
            for (int i = 0; i < 6; i++) if (uTargets[i].w > 0.0) {
                float pulse = uTargetPulse[i];
                vec3 tint = mix(vec3(1.0, 0.3, 0.8), vec3(1.0, 0.85, 0.5), pulse) * (0.6 + 2.5 * pulse);
                col += lamp(p, n, rd, uTargets[i].xyz, 0.9 + 3.0 * pulse, tint, base, 0.8) * ao;  // lamps / flashbulb
            }
            col += vec3(1.0, 0.8, 0.3) * exp(-a * 30.0) * 0.35 * (0.5 + 0.5 / (1.0 + length(uSluurp.xyz - p)));
            col += vec3(1.0, 0.95, 0.8) * exp(-a * 60.0) * uTreble * step(0.97, hash21(floor(p.xy * 60.0) + floor(p.z * 60.0)));
        }
        col *= exp(-t * 0.35);
    }
    return boost(col) + toysEdge(uv);
}
```
Kotlin `Hive.de(p, zoom)` mirrors `apollonianDE` with `gBloom = 0`. Budget ≈ 0.75 GFLOP/frame; most of the
frame is black (transparent) — the darkest and cheapest room, deliberately placed after the most expensive.

### 9.5 Transitions (swipe forward/back, 1 s crossfade of picture + music; `uTransition` −1→0 out, 0→1 in)
Echo persists across the fade; `uSluurpGlow → 1` during it (the constant element blazes while the world changes).
| From → To | Outgoing (last 1 s) | Incoming (first 1 s) | echo |
|---|---|---|---|
| Tidepool → Nave "surface" | plane tilts into perspective `uv.y *= 1 + 2(1−uFade)²` | Nave fades in from 0.4 u further back (start pose −0.4 z) | mix 0.92, zoom 1.02 |
| Nave → Throat "dive" | fog k ×2 (the bulb closes in) | Throat begins with `uSpeed` streak boost ×2.5 decaying 1.5 s (visual only) | zoom 1.06 |
| Throat → Hive "burst" | scan-ring on the final beat, echo zoom 1.08 | Hive opens with `uHitFlash = 1` (0.7 s ramp down, not a strobe) | 1.08 → 0.985 |
| Hive → Tidepool "drain" | lamp R ×2 then 0; echo zoom 0.96 | rings start at `ringR = 0` and expand over 1 s | 0.96 → 1.01 |
| reverse pairs | inverse of the above | | |

---

## 10. HUD (Android Canvas → 640×480 RGBA texture, dirty-upload, composited per eye at zero disparity)

Legibility at ~24–26 px/°: 18 px micro · 22 px body · 24 px menu item (36 px pitch) · 28 px depth · 34 px title
· 54 px hero. `Typeface.SANS_SERIF` bold; +0.04 em tracking ≤ 22 px. Glow everything: each element drawn twice
(`BlurMaskFilter(6f)` at 55 % in the same hue, then the crisp core); min stroke 2 px. Palette: cyan `#40E0FF`
text, magenta `#FF3CC8` selection/alerts, lime `#B8FF3C` positive/armed, gold `#FFD24A` score, violet `#9A6BFF`
secondary. No white fields, no filled panels (largest fill = the 12 % cyan selection bar, 560×36). Safe area 40 px
→ content box 560×400. Head-locked, never rolls, never trails.

Play HUD (Show HUD = On):
```
 ┌──────────────────────────────────────────────────────────┐
 │ (40,52)  THE NAVE · 18 px violet                          │
 │                                                          │
 │            [ fractal + sluurp + rope live in the scene ]  │
 │                  ▲ 24 px chevron (Nave look-away > 4 s)  │
 │                                                          │
 │ (40,428) DEPTH 7 · 28 px gold    ●●●○ targets 18 px      │
 │          SLUURPS 132 · 18 px cyan   › › › ‹ ‹  (320,456)   │
 └──────────────────────────────────────────────────────────┘
 speed pips: five 18 px chevrons centred at (320,456); lit = level (HOVER = a single 18 px "◦"); lime.
 toasts: centred (320,120), 22 px magenta, 1.2 s: "CRUISE", "WARP", "HOVER", "re-centred",
         "stereo parallax paused — cooling"; onboarding whisper "tap to flick" (once per install).
 "+1": 22 px gold at the projected target (px = 320 + 480·d.x/(d.z·uFov), py = 240 − 480·d.y/(d.z·uFov),
       d = uCamRotᵀ(target − camCenter), only if d.z > 0), rises 20 px over 0.6 s.
```
Show HUD = Off hides the readouts, pips and "+1" only; toasts, the notice, the menu and the look-away
chevron always show. Debug build adds yaw/pitch/roll, rung, GPU ms, batt °C at (40, 90) in 18 px.

---

## 11. Settings menu (double-tap opens/closes, deferred 350 ms for triple-tap disambiguation)

Final order (SPEC; the glasses' optional "Motion: Calm" row is **rejected** — with five speed levels, Calm =
SLOW/HOVER + Echo Off, and the suite convention prefers the fixed list):
```
 SETTINGS                                        34 px cyan, (40,80)
 ▸ Scene              Nave  ◂ ▸                  rows 24 px, pitch 34, from y=118 (last baseline 424, bar to 434); labels x=90, values right-aligned x=560
   Music              On                         On / Off
   Volume             70 %                       0–100 in steps of 10
   Stereo             Subtle                     Off / Subtle / Deep
   Echo trails        Soft                       Off / Soft / Wild
   Quality            Auto                       Auto / Low / High
   Head tracking      On                         On / Off
   Show HUD           On                         On / Off
   About              ▸                          page (below)
   Reset Settings                                1st tap → value "tap again!" (magenta); 2nd tap → reset + "done"
 double-tap closes · swipe up/down = move · swipe forward/back = adjust · tap = toggle/activate   (16 px, y=462, clear of the last row)
```
Behaviour: selection wraps; one swipe = one step (classified on finger-up, threshold `max(48, 0.09·1280) = 115 px`,
raw dx sign inverted → forward/back semantics); moving the selection cancels a pending "tap again!"; selected row
= 12 % cyan bar + magenta chevron; TICK on move, TICK ×1.3 on adjust, SELECT on activate. Scene change from the
menu = the same 1 s crossfade. While open: physics frozen, targets paused, echo off, `uFade 0.35`, rung 2.
Persisted in `SettingsStore` (SharedPreferences): every row above + `photoAck` (not cleared by Reset) +
`bestDepth[4]`, `totalSluurps`, `onboarded`. Speed level is not persisted (CRUISE on launch).

Input timing: tap = KEY `BUTTON_A`/`DPAD_CENTER`/`ENTER`/`SPACE` (ACTION_UP, hold < 450 ms, not cancelled) or a short
touch on `cyttsp5*`, KEY+touch de-duped within 60 ms; a single tap is deferred 300 ms (suite convention) and cancelled
by a second tap 40–320 ms later — that pair is the double-tap, and the system `KEYCODE_BACK` it produces is absorbed;
a lone `KEYCODE_BACK` is also a double-tap. The double-tap is deferred 350 ms — a third tap in that window makes it a
triple-tap (recentre, no menu); flick lockout 250 ms; left pad `cyttsp6*` ignored; no long-press ever.

**About** (tap → page; BACK or back-swipe returns; fits 560×400 at 22 px, no scrolling; overflowing licence lines
are elided with "…"):
```
 TAPFRACTAL 1.0                                  34 px cyan
 A head-swung eyeball in a live fractal.         22 px violet
 Music:                                          22 px gold
   ‹title› · ‹artist› · ‹licence›                22 px cyan   × 4 (from assets/music/ATTRIBUTION.md)
 Contains flashing lights and moving patterns.   22 px magenta (the notice, verbatim, 2 lines)
 Stop and rest if you feel dizzy or unwell.
 Stereo parallax draws each eye from a slightly  22 px cyan
 different point; Off is easiest on the eyes.
 tropicalstream · no network, no permissions     18 px violet
```

---

## 12. First-launch photosensitivity notice
Shown before anything animates (scene at `uFade 0.15`, static camera, no music, HOVER). Full-HUD card:
```
 ┌────────────── 480×220 outline, 2 px cyan glow stroke, no fill, centred ──────────────┐
 │  PHOTOSENSITIVITY NOTICE                                   34 px magenta               │
 │  Contains flashing lights and moving patterns.             22 px cyan                  │
 │  Stop and rest if you feel dizzy or unwell.                22 px cyan                  │
 │  Echo trails and stereo parallax can be turned off         22 px cyan                  │
 │  in Settings (double-tap).                                                             │
 │                        TAP TO CONTINUE                     18 px lime, pulsing 0.5 Hz  │
 └────────────────────────────────────────────────────────────────────────────────────────┘
```
Final copy (strings.xml): title `PHOTOSENSITIVITY NOTICE`; body `Contains flashing lights and moving patterns.
Stop and rest if you feel dizzy or unwell. Echo trails and stereo parallax can be turned off in Settings
(double-tap).`; action `TAP TO CONTINUE`. The tap is armed after 1.5 s (a stray pad tap on launch must not
dismiss it); dismiss persists `photoAck = true`; never cleared by Reset Settings; repeated verbatim in About.

---

## 13. Feel budget and on-device playtest
| Timing | Target |
|---|---|
| head → camera | ≤ 1 frame + prediction (no visible slip against furniture at 100°/s) |
| tap → impulse / twang | next substep (≤ 1 frame) / ≤ 30 ms |
| hit → burst + chime | same frame / ≤ 20 ms |
| hit → whoomp | next beat, ≤ 350 ms |
| level ease | 0.8 s monotonic |
| flick free flight | 0.60–0.84 s; reel-in from 3 u ≈ 0.9 s |
| speed-level change | 0.4 s ramp, toast |
| hover as a wall approaches | speed → 0 within 0.6 u, never a contact |

Checklist (glasses on a head): (1) yaw 30° in 0.3 s → sluurp lags, snaps, overshoots once; (2) swing + tap at
peak → 8 u/s, out of frame, back with a snap in ~1.5 s; (3) tap from rest → 2 u punch forward along the gaze;
(4) fly at CRUISE straight at a Nave rib → slow to a hover, slide, never inside; (5) bank hard at FAST in the
Throat → the sluurp swings wide on the tail; (6) hold still 30 s → sluurp circles to the beat, rope shimmers, the
eye blinks and looks at a target; (7) let a target expire → soft sigh, world eases back one, no text; (8) glasses
on the desk 60 s → attract self-plays, resumes the player's depth on pickup; (9) axis self-test §2.5; (10)
calibration §7.4/7.5 (gpubusy ≤ 60 % at CRUISE; battery thresholds keep skin < 42 °C); (11) screencap: no bloom
leaking across the eye seam. Tune `K`, `C`, `L0`, the 0.34 Julia gain and the beat impulse first.

After driving the glasses off-head: `settings put global deep_suspend_disabled_persist 0` and
`deep_suspend_disabled_tmporary 0`.

---

## 14. Decisions (conflicts resolved)
1. **Gaze navigation replaces all autonomous camera paths** (spec directive). Designs' paths survive as start pose, gentle current (≤ 0.15 u/s), and auto-flight for Head tracking Off / attract. Swipe up/down = speed level, not zoom.
2. **Music never changes flight speed** (glasses comfort rule beats spectacle/toy speed modulation); it modulates structure and light only.
3. **`uFov = 2·tan(halfV)`, default 0.35** (glasses C1); the contract comment is corrected, code unchanged.
4. **World frame is left-handed +z forward** (x right, y up gravity-true): `det(uCamRot) = +1`, `cross(right, up) = forward` — safer for scene authors than glasses' −z/det −1.
5. **Toy layout re-solved for the real frame**: anchor `(0, +0.15, 1.4)`, `L0 = 0.23`, `r_b = 0.045`, `r_t = 0.08·0.97^L` (the toy design's 0.28/0.42/0.09 put the anchor off-screen at this FOV).
6. **Toy physics = toy design's slack-then-elastic bungee** (K 320, C 9, ζ 0.25) in **world space** (spec), not rig space; glasses' soft spring rejected; glasses' head bubble 0.55 and 6 u/s *tangential* cap adopted; flick cap 8 u/s (between toy's 9 and glasses' 6); gravity 1.0 along real-world down (`uGravity`).
7. **Fractal collision via the DE mirror** (spec): bounce restitution 0.6; the toy design's analytic hull sphere and AABB walls are dropped.
8. **No zoom overshoot**: `uZoom` eases monotonically over 0.8 s (glasses); the "breathe" comes from the post ripple, structure kicks, rope growth and the HUD numeral (toy's intent preserved without a scale lunge).
9. **Hue step per hit 0.05** (toy 0.07 / spectacle 0.03).
10. **Targets in world space, spawned in the ±25° gaze cone at 1–3 u** (spec); toy's wide yaw ranges and anchor-exclusion dropped; gentle-failure timeouts kept but gaze-gated, with silent recycling of targets you fly past.
11. **The 2D room runs the same 3D toy sim** with a frozen camera position; the core projects the toys per eye into uv for `toys2D`. One physics path for all four rooms.
12. **Hue-preserving luminance tone-map + core tint** replaces per-channel `c/(1+c)` in the core-owned post pass (waveguide pale-field rule).
13. **Echo trails frame-rate normalised, world-locked via reprojection, speed-gated, mix ≤ 0.90**, with spectacle's per-scene direction/tint tables.
14. **Post-pass kaleido (`uKaleido`) dropped**; the Tidepool folds in-scene at L ≥ 3; the Throat's crystalline step is the in-fractal octahedral fold at L ≥ 4.
15. **Rung ladder + timer-query controller** implements the spec's adaptive resolution (0.3–0.75, EMA thresholds 15/9 ms at 60 fps, 26/16 at 30); thermal governor (BURST/CRUISE/COOL) on battery temperature is SHOULD; APL servo MAY.
16. **Mono render when s = 0**, toy bounding sphere, depth reprojection and FBO-res bloom are MUST performance levers; the Nave coarse pre-pass SHOULD.
17. **Double-tap menu deferred 350 ms** so triple-tap recentre is reliable; flick lockout 250 ms.
18. **Settings list is exactly the spec's ten rows**; "Motion: Calm" rejected (covered by speed levels + Echo Off).
19. **`uBeat` onset refractory 340 ms** (both spectacle and glasses asked for it).
20. **Final names**: The Tidepool · The Nave · The Throat · The Hive (spectacle's room names; they describe the flight vocabulary of each room: dive, glide, rush, drift).
21. **`uHitPulse` (τ 0.18 s, post ripple) and `uHitFlash` (τ 0.7 s, scene reactions) are both kept** — different decays, different consumers.
22. **`uTravel` and `uSpeed` added** so the 2D dive and the Throat's speed streaks need no per-scene hacks.
