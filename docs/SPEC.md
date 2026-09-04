# TapFractal — Orchestrator Baseline Spec (v1 — adds GAZE NAVIGATION directive)

New RayNeo X3 Pro app. Inspired by SLUURP! (Isaac Cohen, HTC Vive, 2016): a psychedelic
eyeball on a rope — swing it at targets, the space grows on each hit, trippy visuals and
sound. TapFractal re-imagines that toy for glasses with NO hand controllers: the tether is
your HEAD, and the world is a live GPU fractal.

## Identity
- Path: `~/Projects/TapFractal` (fresh git repo — `git init` only; do NOT commit unless told)
- package/applicationId `com.tapfractal`, label "TapFractal", versionName 1.0, apk name `TapFractal.apk`
- Git identity for any future commit: tropicalstream <tropicalstream@users.noreply.github.com>, no Co-Authored-By trailers.

## Hardware ground truth (queried on the connected device today)
- 1280×480 physical surface = two 640×480 eyes side-by-side (left eye x∈[0,640), right x∈[640,1280)). density 160 → dp == px.
- GPU Adreno 621, OpenGL ES 3.2. 60 Hz. ~3.8 GB RAM. Android 12. Build.MODEL=ARGF20, MANUFACTURER=RayNeo, PRODUCT=RayNeoX3Pro (detect RayNeo by manufacturer/brand/product lowercase contains "rayneo"/"leiniao"/"ffalcon"; NEVER Build.MODEL).
- Waveguide display: BLACK IS TRANSPARENT (the real world shows through). Bright saturated colour on black reads best. Large white/pale fields glare and rainbow. MicroLED: green is brightest; deep red / orange / brown read dim and muddy (an earlier suite app had to replace orange with hot magenta). Preferred hues: cyan, electric blue, hot magenta, violet, lime/green, gold. Thin 1-px lines vanish — glow everything.
- Vendor thermal guidance: target ~30 fps sustained UI, bursts of 60 fine; keep sustained draw modest → adaptive internal resolution is mandatory.
- Input (proven across the suite):
  - Right temple trackpad = MotionEvents (device name contains "cyttsp5"). LEFT pad (name contains "cyttsp6") is the system volume pad — ignore it entirely.
  - A physical tap arrives as a KEY event: KEYCODE_BUTTON_A / KEYCODE_DPAD_CENTER (also accept ENTER/SPACE). De-dupe KEY+touch of the same tap within ~60 ms.
  - Double-tap arrives as KEYCODE_BACK → open/close the settings menu. NEVER design long-press (system quick-settings shade steals it).
  - Swipes: classify ONE discrete gesture per finger-up on the dominant axis. Threshold = max(48, 0.09*viewWidthPx). Horizontal raw dx sign is INVERTED vs the physical gesture on this hardware; vertical is not. Semantics must be forward/backward (never assume left/right), vertical = up/down.
  - Menus: one swipe = one step, always (no accumulators).
- Manifest: `<meta-data android:name="com.rayneo.mercury.app" android:value="true"/>`, extra intent-filter category `com.rayneo.intent.category.AR_APP`, landscape, singleTask, `uses-feature glEsVersion 0x00030000`. Do NOT declare `ar_mode` (restricts to left lens). Hide system bars, FLAG_KEEP_SCREEN_ON.
- Zero vendor AARs, zero permissions (MediaPlayer on assets, sensors, SharedPreferences need none).

## Toolchain (copy from ~/Projects/x3dflappy: gradle wrapper, root build.gradle.kts pattern, gradle.properties)
gradle 8.9 / AGP 8.7.3 / Kotlin 2.0.21 / JDK 17 (`/usr/libexec/java_home -v 17`), `sdk.dir=/opt/homebrew/share/android-commandlinetools`, compileSdk 35, minSdk 29, targetSdk 35. Build: `./gradlew assembleDebug`. Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`; launch `adb shell am start -n com.tapfractal/.MainActivity`; screenshot `adb exec-out screencap -p > x.png` (1280×480; crop the left 640 for one eye).

## Graphics methodology — MUST be new to the suite
Every previous X3 app is Canvas-2D neon vectors or GL additive line/point batches (wireframe Minter style, isometric synthwave, painted sprites). TapFractal uses NONE of that:
- Full-screen **fragment-shader fractals**: raymarched distance estimators (Mandelbulb, kaleidoscopic IFS, Apollonian packing) and escape-time (Julia) — no geometry, no vertex batches, no stroke font.
- **Internal low-res render target** (FBO) at an adaptive scale of the 1280×480 surface (default 0.5 → 640×240, clamp 0.3–0.75; adjust by EMA frame time: >15 ms lower, <9 ms raise) then a **post pass** upscales to the real surface with soft bloom, chromatic aberration (grows with music energy), vignette, and optional **video-feedback echo** (ping-pong previous frame, slight zoom+rotate, ~0.85 mix) for psychedelic trails.
- **Per-eye rendering**: two glViewports (left/right 640×480 at full res; half that in the FBO). Optional real stereo parallax: camera offset ±s along the camera right vector per eye, converged at the sluurp anchor distance (uv shift). Setting Stereo: Off / Subtle / Deep, default Subtle. In any user-facing copy call it "stereo parallax", never "3D".
- **HUD/menu**: Android Canvas → Bitmap → GL texture (640×480 RGBA, re-uploaded only when dirty), alpha-composited into each eye last. New to the suite (no StrokeFont).
- Colour: IQ cosine palettes per scene, orbit-trap colouring, hue drift with time + beat, black backgrounds, HDR-ish accumulation with soft tone-map so cores are bright but never large white fields.

## The toy loop (rope toy → head)
- The **sluurp** is a glowing eyeball-orb tethered by an elastic rope to an **anchor** ~1.4 units in front of the head. Head rotation (TYPE_GAME_ROTATION_VECTOR, 3-DoF) moves the anchor; the sluurp follows with spring-damper physics in world space → moving your head swings it like a yo-yo. Rope rendered in-shader as a glowing catenary-ish polyline of N segments (uniform array) or an SDF capsule chain.
- **Tap = FLICK**: impulse along the sluurp's current velocity (min speed floor); rope goes slack for ~0.6 s (free flight), then re-tethers. Flick pulses the sluurp glow.
- **Targets** ("fractal eyes"): up to 6 glowing spheres placed by the CPU inside each scene's declared safe volume. Sluurp–target sphere overlap = HIT: burst + chime + score + **the world grows**: zoom level +1 (scene zooms deeper / fold count rises / palette shifts a step), and a new target spawns. Persistent stats: best depth per scene, total sluurps.
- **GAZE NAVIGATION (owner directive 2026-09-02 — overrides anything else here or in the designs): the player MOVES through every scene by where they look.** Camera rotation = head rotation, and the camera continuously flies FORWARD along the gaze direction at a cruise speed: look where you want to go and the fractal flows toward you; turn your head and the flight banks. There is no independent camera path that ignores the head — a scene may add a gentle current/drift but gaze always wins.
  - **Swipe UP / DOWN = speed level ±1** (5 levels: HOVER 0 · SLOW · CRUISE (default) · FAST · WARP; HOVER lets you stop and look around). Manual zoom swipes are removed — zoom/depth grows only by hitting targets.
  - **Collision / no clipping through the fractal:** each scene's Kotlin class MIRRORS its GLSL distance estimator (`fun de(p: Vec3): Float`, same formula, same zoom-level params; for the 2D scene an escape-time/inside test). Cost is negligible (a handful of evaluations per frame). Use it to: (a) slow the camera to a hover as the surface ahead gets close (speed × clamp(deAhead/0.6, 0, 1)) and slide along it — never pass through; (b) let the SLUURP physically bounce off the fractal (restitution ≈0.6) so it feels like a real toy in a real space; (c) spawn targets only in open space (rejection sample until de(p) > 2×radius) **ahead of the player inside the view cone (±25°, 1–3 units away)**, so navigation IS the hunt: see it, fly to it, fling the sluurp into it.
  - Because the anchor sits ahead of the head, flying pulls the sluurp along behind like a kite/tail; a flick throws it forward along the gaze.
  - **Triple-tap = recenter** yaw (drift correction). Setting "Head tracking: Off" falls back to an auto-flight path (accessibility / sensor trouble).
  - 2D scene (Julia): gaze steers a PAN velocity across the plane (yaw → x, pitch → y) and the speed level sets a slow continuous dive (zoom-in rate); the sluurp offset still morphs c.
- Music-reactive: no mic, no Visualizer permission. A build-time tool analyses each track (energy per 20 ms hop, onsets, BPM) into `assets/music/sceneN.env.json`; at runtime `MediaPlayer.currentPosition` indexes it → uniforms `uBeat` (decaying onset pulse), `uEnergy` (smoothed loudness), `uBass`/`uTreble` (band energies).
- First-launch **photosensitivity notice** ("Contains flashing lights and moving patterns — tap to continue"), persisted once acknowledged.

## Scenes — four slots (design may rename/enrich; keep slot semantics + music brief)
| # | Slot | Technique | Head/sluurp coupling | Palette lean | Music brief |
|---|------|-----------|---------------------|--------------|-------------|
| 1 | JULIA DRIFT | 2D escape-time Julia with orbit traps, optional kaleidoscope fold | gaze pans/dives across the plane; sluurp offset from anchor sets the Julia constant c → swinging morphs the fractal | cyan / magenta / violet | dreamy ambient / downtempo psychedelic, 70–100 BPM |
| 2 | MANDELBULB CATHEDRAL | raymarched Mandelbulb, power slowly oscillates with energy | fly by gaze around/into the bulb's canyons (DE mirror keeps you out of the surface); targets ahead in open space | gold / green / teal | deep hypnotic psybient / progressive trance, 100–128 BPM |
| 3 | MENGER TUNNEL | kaleidoscopic IFS / Menger-fold infinite tunnel, flying forward | gaze steers the flight through the lattice; fold count = zoom level; beat kicks the fold | lime / green / electric blue | high-energy drum & bass / breakbeat / electro, 150–175 BPM |
| 4 | APOLLONIAN BLOOM | raymarched Apollonian sphere packing / Kleinian limit set | fly by gaze between the spheres (DE mirror = no clipping); sluurp lights the packing (point light at sluurp) | hot magenta / pink / gold / white-hot cores | euphoric psytrance / acid / uplifting electronica, 128–145 BPM |

Swipe FORWARD/BACK on the temple pad = next/previous scene (with a 1 s crossfade of both picture and music). Up/down swipe = speed level ±1 (see GAZE NAVIGATION).

## Shader contract (fixed so scene authors and the core can work in parallel)
The app prepends `assets/shaders/common.glsl` to every `assets/shaders/sceneN.frag` and appends `assets/shaders/footer.glsl`. Scene files define ONLY `vec3 render(vec2 uv)` (linear RGB, may exceed 1.0) plus their own helpers. Provided by common.glsl:
```
#version 300 es
precision highp float; precision highp int;
uniform vec2  uEyeRes;      // per-eye viewport size in the FBO (e.g. 320×240)
uniform vec2  uEyeOrigin;   // viewport origin in the FBO
uniform int   uEye;         // 0 left, 1 right
uniform float uTime, uDt;   // seconds since scene start, frame dt
uniform vec3  uCamPos;      // per-eye camera position (stereo offset already applied)
uniform mat3  uCamRot;      // columns: right, up, forward
uniform float uUvShift;     // per-eye horizontal uv shift for convergence
uniform float uFov;         // tan(half vertical fov)
uniform vec4  uSluurp;       // xyz pos, w radius
uniform float uSluurpGlow;   // 0..1 flick energy
uniform vec3  uAnchor;      // rope anchor
uniform vec4  uRope[12];    // rope points xyz (w unused); count in uRopeN
uniform int   uRopeN;
uniform vec4  uTargets[6];  // xyz + radius (radius<=0 inactive)
uniform float uTargetPulse[6]; // 0..1 hit-burst age (1 = just hit)
uniform float uZoom;        // continuous depth level (0..)
uniform float uBeat, uEnergy, uBass, uTreble; // 0..1
uniform float uHue;         // 0..1 global hue drift
uniform float uQuality;     // 0.3..1 — scale ray-step budgets by this
uniform float uFade;        // 0..1 crossfade weight (scene drawn dim while fading)
vec2 eyeUV();               // ((gl_FragCoord.xy-uEyeOrigin) - 0.5*uEyeRes)/uEyeRes.y + vec2(uUvShift,0)
vec3 rayDir(vec2 uv);       // normalize(uCamRot * vec3(uv*uFov, 1.0))
vec3 pal(float t, vec3 a, vec3 b, vec3 c, vec3 d); // IQ cosine palette
mat2 rot2(float a); mat3 rotX/rotY/rotZ(float a);
float hash11(float), hash21(vec2); float noise3(vec3); // cheap noise
float sdSphere(vec3 p, float r); float sdCapsule(vec3 p, vec3 a, vec3 b, float r); float smin(float a, float b, float k);
float toysDE(vec3 p, out int id);   // union of sluurp (id 1), rope capsules (id 2), targets (id 10+i) — raymarch scenes min() this into their DE
vec3  toyColor(int id, vec3 p, vec3 n); // emissive colour for those ids (uses uSluurpGlow / uTargetPulse)
vec3  toys2D(vec2 p, vec3 base);    // 2D scenes: draws sluurp/rope/targets as glowing discs over base (uses xy of the uniforms)
```
footer.glsl: `void main(){ vec3 c = render(eyeUV()); fragColor = vec4(max(c,0.0)*uFade, 1.0); }` (tone-mapping happens in post).
Post pass `assets/shaders/post.frag`: samples the scene FBO + previous echo FBO; bloom (5-tap), chromatic aberration `0.002 + 0.01*uEnergy`, vignette, tone-map `c/(1+c)` then gamma 1/2.2, echo mix; writes to the screen viewport for each eye.

Kotlin side: `Scene` base class per slot in `scenes/` (name, frag asset, music asset, palette params, `de(p)` CPU mirror of the shader DE (or inside-test for 2D), `cruiseSpeed` per level, `is2D`, per-zoom-level parameter mapping, spawn cone). Scene authors may edit ONLY their own `scenes/<Name>.kt` and `assets/shaders/scene<N>.frag`.

## Settings menu (double-tap opens/closes; one swipe = one step; tap = edit/toggle; follows the suite convention)
Scene · Music (on/off) · Volume · Stereo (Off/Subtle/Deep) · Echo trails (Off/Soft/Wild) · Quality (Auto/Low/High) · Head tracking (On/Off) · Show HUD (On/Off) · About (credits + music attribution + photosensitivity note) · **Reset Settings (last, confirm twice)**. No SBS toggle. Persist everything in SharedPreferences (`SettingsStore`).

## Music
Four distributable tracks (CC0 preferred, CC-BY acceptable with attribution shown in About + README + assets/music/ATTRIBUTION.md). Bundled as `assets/music/scene1.mp3 … scene4.mp3` with `sceneN.env.json` envelopes from `tools/analyze_music.py` (ffmpeg → PCM → numpy). Looping playback via MediaPlayer, crossfade on scene change, respects Music setting.

## App icon
Programmatically rendered (Python, Pillow+numpy) — an actual Julia-set spiral glowing hot-magenta/cyan/violet on near-black, with a small bright eyeball-orb (the sluurp) and a faint rope arc. Adaptive icon: foreground PNGs (108 dp → 108/162/216/324/432 px) with safe-zone padding + background colour layer; legacy ic_launcher/ic_launcher_round PNGs at all densities; `art/icon-512.png` for the README.

## Non-goals / guardrails
- No network, no camera, no mic, no vendor AARs, no permissions.
- No long-press gestures. Menu = double-tap. Triple-tap = recenter head.
- Never write "3D" in copy for the SBS presentation; say "stereo parallax" only for the actual per-eye offset feature.
- Don't leave the glasses in deep-suspend-disabled state after driving them: `settings put global deep_suspend_disabled_persist 0` and `deep_suspend_disabled_tmporary 0` when done.
