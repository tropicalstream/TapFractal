package com.tapfractal.engine

import android.util.Log
import com.tapfractal.M
import com.tapfractal.Mat3
import com.tapfractal.SettingsStore
import com.tapfractal.Vec2
import com.tapfractal.Vec3
import com.tapfractal.audio.Music
import com.tapfractal.audio.Sfx
import com.tapfractal.head.HeadTracker
import com.tapfractal.scenes.Hive
import com.tapfractal.scenes.Nave
import com.tapfractal.scenes.Scene
import com.tapfractal.scenes.Throat
import com.tapfractal.scenes.Tidepool
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

enum class Swipe { FORWARD, BACK, UP, DOWN }

/** A HUD "+1" floater at a screen position (left-eye px). */
class Floater(val text: String, var x: Float, var y: Float, var t: Float = 0f)

/**
 * The state machine and per-frame orchestration (DESIGN §0, §4.4–4.7, §9.5, §11, §12):
 * photosensitivity notice → play ⇄ attract; scene crossfades; hit handling (zoom ease, hue step,
 * pulses, chime, whoomp, stats); settings menu; toasts. Everything here runs on the GL thread.
 */
class Game(val store: SettingsStore, val head: HeadTracker, val music: Music, val sfx: Sfx) {
    companion object {
        const val TAG = "TapFractal"
        const val FOV = 0.35f
        const val CONVERGENCE = 1.4f
        /** Zoom ease after a hit (smoothstep = ease-in-out); a timeout eases back more slowly. */
        /** The in-play readout hides this long after the last temple input; any tap brings it back. */
        const val HUD_HOLD_S = 10f
        const val HIT_EASE_S = 1.2f
        const val TIMEOUT_EASE_S = 1.5f
    }

    enum class State { NOTICE, PLAY, ATTRACT }

    val scenes: List<Scene> = listOf(Tidepool(), Nave(), Throat(), Hive())
    var state = State.NOTICE; private set
    var sceneIndex = store.scene; private set
    val scene: Scene get() = scenes[sceneIndex]
    val rs = RenderState()
    val toy = Toy(); val targets = Targets(); val flight = Flight()
    private val toyCtx = ToyCtx(); private val tctx = TargetCtx()

    var time = 0f; private set
    private var sceneTime = 0f
    private var outSceneTime = 0f
    private var fade = 1f
    private var outgoing: Scene? = null
    private val outCam = Vec3(); private val outCam2D = Vec3(); private var outZoom = 0f; private var outTravel = 0f

    // zoom / hue (CORE_API §11: uZoom = hit levels eased + travel dive)
    private val sceneZoomTarget = IntArray(4)
    /** Integer hit level (the target of the hit ease; the dive is not counted). */
    var zoomTarget = 0; private set
    /** Eased hit-level zoom alone. */
    var zoomHits = 0f; private set
    /** The continuous uZoom = zoomHits + flight.diveZoom (3D rooms); what every de()/field()/current() call sees in Scene.zoom. */
    var zoom = 0f; private set
    private var zoomFrom = 0f; private var zoomTo = 0f; private var zoomT = 1f; private var zoomDur = HIT_EASE_S
    /** Seconds since the last real hit (large when none) — the rung controller holds its rung for 2 s after a hit. */
    var hitAge = 1e3f; private set
    var hue = 0f; private set
    private var hueOffsetTarget = 0f; private var hueOffset = 0f
    private var hitPulse = 0f; private var hitFlash = 0f
    private var whoompAt = -1f

    // menu / hud state
    var menuOpen = false; private set
    var aboutOpen = false; private set
    val menu = Menu()
    var toast: String? = null; private set
    var toastT = 0f; private set
    val floaters = ArrayList<Floater>()
    var depthPulseT = 10f; private set
    var noticeT = 0f; private set
    val noticeArmed: Boolean get() = noticeT >= 1.5f
    var lookAwayT = 0f; private set
    var hudDirty = true
    /** Set on every scene entry (including the initial room); the renderer feeds it to RungController.onSceneSwitch. */
    var rungSwitchRequest = false
    var attractRamp = 0f; private set
    private var lastInputTime = 0f
    private var hudWasVisible = true
    private var motionExitT = 0f
    private var nextAutoFlick = 0f
    private var playerZoomTarget = 0
    private var activity = 0f
    private var lastFlickTime = -10f
    private var flickHintDeadline = -1f
    private var convergence = CONVERGENCE

    val headOn: Boolean get() = store.headTracking && head.running
    /**
     * The readout is a heads-up glance, not furniture: it shows for [HUD_HOLD_S] after the last temple
     * input and then gets out of the way of the fractal. A tap (which is also a flick) brings it back —
     * every input handler refreshes `lastInputTime`. Toasts and the look-away chevron are NOT gated by
     * this: a speed change must be readable even when the readout is resting.
     */
    val hudVisible: Boolean get() = store.showHud && time - lastInputTime < HUD_HOLD_S

    init {
        toy.onHit = { i, clean -> onHit(i, clean) }
        toy.onBounce = { v -> sfx.bong(scene.slot, v) }
        toy.onSnap = { s -> sfx.snap(s) }
        toy.onWhiff = { sfx.whiff() }
        toy.onFlick = { t -> sfx.twang(t) }
        targets.onTimeout = { onTimeout() }
        targets.onSpawn = { if (state != State.NOTICE) sfx.bloom() }
        state = if (store.photoAck) State.PLAY else State.NOTICE
        for (i in 0 until 4) { scenes[i].zoom = 0f; scenes[i].level = 0 }
        enterScene(sceneIndex, first = true)
        music.enabled = store.music && state != State.NOTICE
        music.volume = store.volumeFloat
        sfx.volume = 0.8f * store.volumeFloat
        music.load(sceneIndex, crossfade = false)
        if (state == State.NOTICE) flight.level = 0
        rungSwitchRequest = true     // tell the rung controller which slot the initial room is (per-slot memory)
        Log.i(TAG, "game init scene=$sceneIndex state=$state")
    }

    // ------------------------------------------------------------------ input (GL thread)

    fun onTap() {
        lastInputTime = time
        if (state == State.NOTICE) { if (noticeArmed) dismissNotice(); return }
        if (state == State.ATTRACT) { exitAttract(); return }
        if (menuOpen) { if (aboutOpen) { aboutOpen = false; sfx.tick() } else menu.activate(); hudDirty = true; return }
        if (toy.flick(toyCtx)) lastFlickTime = time
    }

    fun onDoubleTap() {
        lastInputTime = time
        if (state == State.NOTICE) return
        if (state == State.ATTRACT) exitAttract()
        if (menuOpen) { if (aboutOpen) aboutOpen = false else closeMenu() } else openMenu()
    }

    fun onTripleTap() {
        lastInputTime = time
        if (state == State.NOTICE) return
        if (state == State.ATTRACT) exitAttract()
        if (menuOpen) return
        head.recentre()
        if (scene.is2D) flight.zeroPan()
        showToast("re-centred")
    }

    fun onSwipe(dir: Swipe) {
        lastInputTime = time
        if (state == State.NOTICE) return
        if (state == State.ATTRACT) exitAttract()
        if (menuOpen) {
            if (aboutOpen) { if (dir == Swipe.BACK) { aboutOpen = false; sfx.tick() } ; hudDirty = true; return }
            menu.onSwipe(dir); hudDirty = true; return
        }
        when (dir) {
            Swipe.FORWARD -> switchScene((sceneIndex + 1) % 4)
            Swipe.BACK -> switchScene((sceneIndex + 3) % 4)
            Swipe.UP -> { flight.level = flight.level + 1; showToast(Flight.NAMES[flight.level]); sfx.tick(1.2f) }
            Swipe.DOWN -> { flight.level = flight.level - 1; showToast(Flight.NAMES[flight.level]); sfx.tick(0.9f) }
        }
    }

    private fun dismissNotice() {
        store.photoAck = true
        state = State.PLAY
        flight.level = Flight.DEFAULT_LEVEL
        music.enabled = store.music
        lastInputTime = time
        sfx.select()
        hudDirty = true
    }

    private fun openMenu() { menuOpen = true; aboutOpen = false; menu.onOpen(); sfx.select(); hudDirty = true }
    private fun closeMenu() { menuOpen = false; aboutOpen = false; sfx.tick(); hudDirty = true }

    fun showToast(s: String) { toast = s; toastT = 1.2f; hudDirty = true }

    // ------------------------------------------------------------------ scenes

    private fun enterScene(idx: Int, first: Boolean) {
        val s = scenes[idx]
        s.onEnter()
        zoomTarget = sceneZoomTarget[idx]; zoomHits = zoomTarget.toFloat(); zoomT = 1f; zoomFrom = zoomHits; zoomTo = zoomHits
        zoom = zoomHits                               // the dive restarts with the flight (travel 0 at the start pose)
        s.zoom = zoom; s.level = zoomTarget; s.time = 0f
        flight.enter(s)
        sceneTime = 0f
        fillToyCtx()
        if (first) toy.reset(toyCtx)
        targets.resetForScene(time, if (first) 0.2f else 0.6f)
        lookAwayT = 0f
    }

    fun switchScene(idx: Int) {
        if (idx == sceneIndex) return
        val out = scene
        outgoing = out; outSceneTime = sceneTime; outZoom = zoom; outTravel = flight.travel
        outCam.set(camCenterFor(out)); outCam2D.set(flight.pan.x, flight.pan.y, flight.planeRotation)
        val oldCam = camCenterFor(out).copy()
        sceneZoomTarget[sceneIndex] = zoomTarget
        sceneIndex = idx
        store.scene = idx
        enterScene(idx, first = false)
        val newCam = camCenterFor(scene)
        toy.translate(newCam - oldCam)
        fade = 0f
        music.load(idx, crossfade = true)
        sfx.swoosh()
        toyGlowKick = 1f
        rungSwitchRequest = true
        hudDirty = true
        Log.i(TAG, "scene → ${scene.name}")
    }
    private var toyGlowKick = 0f

    private fun camCenterFor(s: Scene): Vec3 = if (s.is2D) Vec3() else flight.pos

    // ------------------------------------------------------------------ hits

    private fun onHit(i: Int, clean: Boolean) {
        val t = targets.slots[i]
        val golden = t.golden
        val wasAssist = t.assist
        Log.d(TAG, "hit slot=$i pos=${t.pos} sluurp=${toy.sluurp} clean=$clean state=$state")
        targets.hit(i, tctx)
        val cosmetic = state == State.ATTRACT
        if (!cosmetic) {
            zoomTarget += if (golden) 2 else 1
            startZoomEase(HIT_EASE_S)
            hueOffsetTarget += 0.05f + (if (golden) 0.10f else 0f) + (if (clean) 0.02f else 0f)
            store.totalSluurps = store.totalSluurps + 1
            store.setBestDepth(sceneIndex, zoomTarget)
            scene.level = zoomTarget; scene.onLevelChanged(zoomTarget)
            if (!store.onboarded && targets.onboardStep >= 3) store.onboarded = true
        }
        hitPulse = 1f; hitFlash = 1f; hitAge = 0f
        val vel = 0.5f + 0.5f * min(toy.vel.length() / 6f, 1f)
        sfx.chime(scene.chimeRootHz, zoomTarget, golden, clean, vel)
        // growth whoomp, beat-quantised to the next onset
        val ms = music.msToNextOnset()
        whoompAt = when {
            ms == null -> time
            ms < 30 -> time
            ms > 350 -> time + ms / 2000f
            else -> time + ms / 1000f
        }
        // HUD "+1" at the projected target
        val d = rs.camRot.mulT(t.pos - camCenterFor(scene))
        if (d.z > 0.05f) {
            val px = 320f + 480f * d.x / (d.z * FOV); val py = 240f - 480f * d.y / (d.z * FOV)
            floaters.add(Floater(if (golden) "+2" else "+1", px, py))
        }
        depthPulseT = 0f
        if (wasAssist) Log.i(TAG, "assist target hit")
        hudDirty = true
    }

    private fun onTimeout() {
        if (state == State.ATTRACT) return
        zoomTarget = max(0, zoomTarget - 1)
        startZoomEase(TIMEOUT_EASE_S)
        hueOffsetTarget -= 0.03f
        scene.level = zoomTarget; scene.onLevelChanged(zoomTarget)
        if (time - lastFlickTime < 20f) sfx.sigh()      // a cruiser who never swung gets a silent recycle, not a sound (SFX-over-music)
        hudDirty = true
    }

    private fun startZoomEase(dur: Float) { zoomFrom = zoomHits; zoomTo = zoomTarget.toFloat(); zoomT = 0f; zoomDur = dur }

    // ------------------------------------------------------------------ attract

    private fun enterAttract() {
        state = State.ATTRACT
        playerZoomTarget = zoomTarget
        nextAutoFlick = time + 8f + 6f * Math.random().toFloat()
        motionExitT = 0f
        hudDirty = true
        Log.i(TAG, "attract on")
    }

    private fun exitAttract() {
        if (state != State.ATTRACT) return
        state = State.PLAY
        zoomTarget = playerZoomTarget; startZoomEase(1.5f)
        scene.level = zoomTarget
        targets.clear(); for (t in targets.slots) t.respawnAt = time + 0.3f
        hudDirty = true
        Log.i(TAG, "attract off")
    }

    // ------------------------------------------------------------------ frame

    private fun fillToyCtx() {
        val s = scene
        toyCtx.camCenter.set(camCenterFor(s)); toyCtx.camRot.set(rs.camRot); toyCtx.gaze.set(rs.camRot.forward)
        toyCtx.gravity.set(rs.gravity); toyCtx.scene = s; toyCtx.time = time
        toyCtx.onset = music.onset; toyCtx.bass = music.bass; toyCtx.energy = music.energy
        toyCtx.is2D = s.is2D; toyCtx.headOn = headOn; toyCtx.attract = state == State.ATTRACT
        toyCtx.activity = activity
        toyCtx.driftAmp = when { state == State.ATTRACT -> 0.35f; !headOn -> 0.18f; else -> 0.03f }
        toyCtx.fov = FOV; toyCtx.targets = targets; toyCtx.level = zoomTarget
        toyCtx.toyScale = s.ropeScale(zoom)           // the sluurp radius / rope rest length shrink with the dive (CORE_API §11; the Tidepool's own ropeScale floors at 0.5)
        val depth = zoom                              // uZoom already carries the travel dive in every room (CORE_API §11)
        toyCtx.planePan.set(flight.pan.x, flight.pan.y); toyCtx.planeRot = flight.planeRotation
        toyCtx.planeScale = s.planeScale(depth); toyCtx.planeDepth = depth
        tctx.cam.set(camCenterFor(s)); tctx.camRot.set(rs.camRot); tctx.gaze.set(rs.camRot.forward)
        tctx.scene = s; tctx.zoom = zoom; tctx.level = zoomTarget; tctx.restLen = toy.restLen
        tctx.toyScale = if (s.is2D) 1f else s.ropeScale(zoom)
        tctx.time = time; tctx.bass = music.bass; tctx.attract = state == State.ATTRACT; tctx.headOn = headOn
        tctx.is2D = s.is2D; tctx.fov = FOV
        tctx.planePan.set(flight.pan.x, flight.pan.y); tctx.planeRot = flight.planeRotation; tctx.planeScale = toyCtx.planeScale; tctx.planeDepth = depth
        tctx.onboardingActive = !store.onboarded
        tctx.sluurp.set(toy.sluurp); tctx.sluurpValid = true
    }

    fun update(dtRaw: Float) {
        val dt = min(dtRaw, 0.1f)
        time += dt
        music.update(dt)
        // head
        if (headOn) { rs.camRot.set(head.camRot); rs.gravity.set(head.gravity); rs.headRate.set(head.headRate) }
        else { rs.camRot.identity(); rs.gravity.set(0f, -1f, 0f); rs.headRate.set(0f, 0f, 0f) }
        activity += (min(head.omegaMag / 1.5f, 1f) - activity) * M.ease(dt, 0.5f)
        head.creepAllowed = state == State.PLAY && !menuOpen && (flight.level == 0 || lookAway() > 0.5f) && time - lastFlickTime > 3f

        // toasts / floaters / pulses always tick. While they run the HUD redraws itself at ≤ 12 Hz (Hud.draw's
        // `animating` test); hudDirty is only forced when one of them ENDS so the last frame is not left behind.
        if (toastT > 0f) { toastT -= dt; if (toastT <= 0f) { toast = null; hudDirty = true } }
        if (floaters.isNotEmpty()) { val it = floaters.iterator(); while (it.hasNext()) { val f = it.next(); f.t += dt; if (f.t > 0.6f) it.remove() }; if (floaters.isEmpty()) hudDirty = true }
        if (depthPulseT < 1f) { depthPulseT += dt; if (depthPulseT >= 1f) hudDirty = true }
        // the auto-hide edge needs one repaint, in both directions, or the texture keeps the stale frame
        if (hudVisible != hudWasVisible) { hudWasVisible = hudVisible; hudDirty = true }
        hitPulse *= exp(-dt / 0.18f); hitFlash *= exp(-dt / 0.7f)
        hitAge = min(hitAge + dt, 1e3f)
        toyGlowKick *= exp(-dt / 0.6f)
        if (whoompAt >= 0f && time >= whoompAt) { sfx.whoomp(); whoompAt = -1f }

        if (state == State.NOTICE) {
            noticeT += dt
            hudDirty = true
            fillToyCtx()
            fillFrame(dt, frozen = true)
            return
        }
        if (menuOpen) {
            fillToyCtx()
            fillFrame(dt, frozen = true)
            return
        }

        // attract detection / exit
        if (state == State.PLAY && time - lastInputTime > 45f && head.omegaMag < 0.08f && headOn) enterAttract()
        if (state == State.ATTRACT) {
            attractRamp = min(1f, attractRamp + dt / 2f)
            if (head.omegaMag > 0.25f) { motionExitT += dt; if (motionExitT > 0.15f) exitAttract() } else motionExitT = 0f
            if (time >= nextAutoFlick) { toy.autoFlick(toyCtx); nextAutoFlick = time + 8f + 6f * Math.random().toFloat() }
        } else attractRamp = max(0f, attractRamp - dt / 0.5f)

        // crossfade
        if (fade < 1f) { fade = min(1f, fade + dt); outSceneTime += dt; if (fade >= 1f) outgoing = null }

        // zoom ease (monotonic, ease-in-out over HIT_EASE_S)
        if (zoomT < 1f) { zoomT = min(1f, zoomT + dt / zoomDur); zoomHits = M.mix(zoomFrom, zoomTo, M.smoothstep(0f, 1f, zoomT)) }
        // hue drift + eased steps
        hue += (0.004f + 0.02f * music.energy) * dt + 0.01f * music.beat * dt
        hueOffset += (hueOffsetTarget - hueOffset) * M.ease(dt, 0.4f)
        val hueTotal = M.fract(hue + hueOffset)

        // flight — Scene.zoom (= uZoom: hits eased + travel dive) is written BEFORE any de()/current() call (CORE_API §11)
        val s = scene
        sceneTime += dt
        zoom = zoomHits + flight.diveZoom
        s.zoom = zoom; s.time = sceneTime; s.level = zoomTarget
        val auto = !headOn || state == State.ATTRACT
        val gaze = rs.camRot.forward
        if (s.is2D) flight.step2D(dt, head.yaw, head.pitch, head.roll, s, zoom, sceneTime, music.energy, auto, if (state == State.ATTRACT) 1 else -1)
        else flight.step3D(dt, gaze, s, zoom, sceneTime, auto, if (state == State.ATTRACT) 1 else -1)
        zoom = zoomHits + flight.diveZoom; s.zoom = zoom          // the dive advanced with this frame's travel: toys, targets and the shader see the same value

        // toy + targets
        fillToyCtx()
        toy.update(dt, toyCtx)
        targets.update(dt, tctx)

        // onboarding whisper
        if (!store.onboarded && !store.flickHintShown && targets.onboardStep == 2 && targets.onboardSpawnedThird > 0f && time - targets.onboardSpawnedThird > 15f) {
            store.flickHintShown = true; showToast("tap to flick")
        }
        // look-away chevron timer (Nave)
        if (lookAway() > 0.5f) lookAwayT += dt else lookAwayT = 0f

        rs.hue = hueTotal
        fillFrame(dt, frozen = false)
    }

    private fun lookAway(): Float {
        val s = scene
        val cone = M.rad(s.interestConeDeg)
        if (cone >= M.PI - 1e-3f) return 0f
        val ang = acos(rs.camRot.forward.z.coerceIn(-1f, 1f))
        return M.smoothstep(cone, cone + M.rad(20f), ang)
    }

    private fun fillFrame(dt: Float, frozen: Boolean) {
        val s = scene
        rs.dt = dt; rs.fov = FOV
        // stereo
        val sep = when (store.stereo) { 0 -> 0f; 1 -> 0.004f; else -> 0.008f }
        rs.eyeSep = sep
        if (store.stereo == 2) {
            val target = M.clamp(toy.sluurp.distance(camCenterFor(s)), 0.7f, 1.4f)
            convergence += (target - convergence) * M.ease(dt, 0.4f)
        } else convergence = CONVERGENCE
        rs.convergence = convergence
        // scene draws
        val cd = rs.current
        cd.scene = s; cd.camCenter.set(camCenterFor(s)); cd.cam2D.set(flight.pan.x, flight.pan.y, flight.planeRotation)
        cd.fade = if (state == State.NOTICE) 0.15f else fade
        cd.transition = if (fade < 1f) fade else 0f
        cd.time = sceneTime; cd.zoom = zoom; cd.travel = flight.travel
        cd.speed = flight.speed; cd.lookAway = lookAway()
        val og = outgoing
        rs.crossfading = og != null && fade < 1f
        if (og != null) {
            val od = rs.outgoing
            od.scene = og; od.camCenter.set(outCam); od.cam2D.set(outCam2D)
            od.fade = 1f - fade; od.transition = -(1f - fade); od.time = outSceneTime; od.zoom = outZoom; od.travel = outTravel
            od.speed = 0f; od.lookAway = 0f
        } else rs.outgoing.scene = null
        // toys
        rs.sluurp.set(toy.sluurp); rs.sluurpRadius = toy.drawRadius
        rs.sluurpVel.set(toy.vel); rs.sluurpSquash = toy.squash
        rs.sluurpGlow = max(toy.glow, toyGlowKick)
        rs.sluurpEye.set(toy.eyeDir); rs.anchor.set(toy.anchor)
        for (i in 0 until 12) rs.rope[i].set(toy.rope[i]); rs.ropeN = 12
        rs.ropeTension = toy.tension; rs.ropeFree = toy.ropeFree
        // bounds enclose the sluurp + rope only (targets are tested individually in toysDE — CORE_API.md #3)
        rs.toyBoundsR = toy.bounds(rs.toyBounds) + 0.03f   // + TOY_PAD (common.glsl)
        for (i in 0 until 6) {
            val t = targets.slots[i]
            if (t.state == Target.State.FREE) { rs.targetRadius[i] = 0f; rs.targetPulse[i] = 0f; rs.targetLife[i] = 1f; continue }
            rs.targetPos[i].set(t.pos); rs.targetRadius[i] = t.radius; rs.targetPulse[i] = t.pulse; rs.targetLife[i] = t.lifeUniform
        }
        // music / pulses
        rs.beat = music.beat; rs.energy = music.energy; rs.bass = music.bass; rs.treble = music.treble
        rs.hitPulse = hitPulse; rs.hitFlash = hitFlash; rs.attract = attractRamp
        rs.hitAge = hitAge
        // echo
        val echoSetting = store.echo
        if (echoSetting == 0 || frozen) { rs.echoMix = 0f; rs.echoZoom = 1f; rs.echoRot = 0f }
        else {
            val e = s.echo
            val tau = if (echoSetting == 1) e.softTau else e.wildTau
            var zoomF = if (echoSetting == 1) e.softZoom else e.wildZoom
            var rot = if (echoSetting == 1) e.softRot else e.wildRot
            if (s.slot == 1 && echoSetting == 2) rot *= kotlin.math.sign(sin(0.05f * sceneTime)).let { if (it == 0f) 1f else it }
            var mix = min(0.90f, exp(-dt / tau))
            mix = min(0.92f, mix + 0.06f * hitPulse)
            mix *= 1f - M.smoothstep(M.rad(60f), M.rad(180f), head.omegaMag)
            val fr = dt / 0.016667f
            zoomF += (if (s.slot == 4) -0.01f else 0.01f) * music.beat
            if (hitPulse > 0.3f) zoomF *= 1.01f
            rs.echoMix = mix; rs.echoZoom = zoomF.pow(fr).coerceIn(0.9f, 1.15f); rs.echoRot = rot * fr
        }
        rs.echoCentre.set(s.echo.centre.x, s.echo.centre.y)
        val dHue = when (s.slot) { 1 -> 0.07f; 2 -> 0.06f; 3 -> 0.08f; else -> 0.05f }
        val base = when (s.slot) { 2 -> Vec3(0.95f, 1f, 0.9f); 3 -> Vec3(0.8f, 1f, 1f); 4 -> Vec3(1f, 0.85f, 1f); else -> Vec3(1f, 1f, 1f) }
        val p = s.palette; val tt = rs.hue + dHue
        val pc = Vec3(
            p.a.x + p.b.x * cos(M.TAU * (p.c.x * tt + p.d0.x + p.zshift.x * zoom)),
            p.a.y + p.b.y * cos(M.TAU * (p.c.y * tt + p.d0.y + p.zshift.y * zoom)),
            p.a.z + p.b.z * cos(M.TAU * (p.c.z * tt + p.d0.z + p.zshift.z * zoom)))
        val mx = max(1e-3f, max(pc.x, max(pc.y, pc.z)))
        rs.echoTint.set(M.clamp(pc.x / mx, 0f, 1f) * base.x, M.clamp(pc.y / mx, 0f, 1f) * base.y, M.clamp(pc.z / mx, 0f, 1f) * base.z)
        // post
        rs.aberr = 0.002f + 0.01f * music.energy + 0.004f * music.beat + 0.008f * hitPulse
        rs.breath = 0.012f * sin(M.TAU * 0.08f * time)
        rs.exposure = 1f
        rs.prism = if (echoSetting == 2) 1 else 0
        rs.menuDim = menuOpen
        // quality
        val userCap = when (store.quality) { 1 -> 3; else -> 9 }
        rs.rungCap = min(userCap, s.maxRung)
        rs.qualityMode = store.quality
        rs.forceRung = if (menuOpen) 2 else -1
        rs.hudAlpha = 1f
    }

    /** One-line diagnostic for the periodic frame log. */
    fun debugState(): String {
        val s = scene; val cam = camCenterFor(s)
        return "cam=%s de=%.3f skin=%.3f spd=%.2f lvl=%d sluurp=%s |b-cam|=%.2f zoom=%.2f (hits %.2f + dive %.2f, travel %.1f) targets=%d".format(
            cam, s.de(cam), flight.skin, flight.speed, flight.level, toy.sluurp, toy.sluurp.distance(cam), zoom, zoomHits, flight.diveZoom, flight.travel, targetPips().first) +
            " T[" + (0 until 6).filter { targets.slots[it].state != com.tapfractal.engine.Target.State.FREE }.joinToString(" ") { "%d:%s r=%.3f pulse=%.2f".format(it, targets.slots[it].pos, targets.slots[it].radius, targets.slots[it].pulse) } + "]"
    }

    /** The camera basis to PRESENT with (the newest head pose) — the renderer feeds it to the timewarp; identity when tracking is off. */
    fun presentRot(): Mat3 = if (headOn) head.camRot else identityRot
    private val identityRot = Mat3()

    fun onPause() { music.pause() }
    fun onResume() { music.resume(); lastInputTime = time }

    // ---- timed debug hooks (tapfractal_debug "pulse=<epoch ms>" / "flick=<epoch ms>", fired by the renderer) ----
    var debugPulseAt = 0L
    var debugFlickAt = 0L
    /** Visual-only hit: the post ripple + the scene flash, no zoom/stats change. */
    fun debugPulse() { hitPulse = 1f; hitFlash = 1f }
    fun debugFlick() { onTap() }

    /** Active target count / desired, for the HUD pips. */
    fun targetPips(): Pair<Int, Int> {
        var a = 0
        for (i in 0 until 4) if (targets.slots[i].state == Target.State.ACTIVE) a++
        return Pair(a, (2 + zoomTarget / 3).coerceIn(2, 4))
    }

    /** Direction (screen px offset from centre, left eye) toward the Nave when looking away; null otherwise. */
    fun lookAwayChevron(): Vec2? {
        if (scene.slot != 2 || lookAwayT < 4f) return null
        val d = rs.camRot.mulT(Vec3() - camCenterFor(scene))
        val v = Vec2(d.x, d.y)
        val l = v.length(); if (l < 1e-4f) return null
        return v * (1f / l)
    }

    fun applySettings() {
        music.enabled = store.music && state != State.NOTICE
        music.volume = store.volumeFloat
        sfx.volume = 0.8f * store.volumeFloat
        sfx.effects = store.effects
        head.enabled = store.headTracking
    }

    // ------------------------------------------------------------------ settings menu (DESIGN §11)

    inner class MenuItem(val label: String, val value: () -> String, val adjust: ((Int) -> Unit)? = null, val activate: (() -> Unit)? = null)

    inner class Menu {
        var selected = 0; private set
        var confirmingReset = false; private set
        val items: List<MenuItem> = listOf(
            MenuItem("Scene", { scene.name }, adjust = { d -> switchScene((sceneIndex + d + 4) % 4) }),
            MenuItem("Music", { if (store.music) "On" else "Off" }, adjust = { store.music = !store.music; applySettings() }),
            MenuItem("Volume", { "${store.volume * 10} %" }, adjust = { d -> store.volume = store.volume + d; applySettings() }),
            MenuItem("Effects", { store.effectsName }, adjust = { d -> store.effects = (store.effects + d + 3) % 3; applySettings() }),
            MenuItem("Stereo", { store.stereoName }, adjust = { d -> store.stereo = (store.stereo + d + 3) % 3 }),
            MenuItem("Echo trails", { store.echoName }, adjust = { d -> store.echo = (store.echo + d + 3) % 3 }),
            MenuItem("Quality", { store.qualityName }, adjust = { d -> store.quality = (store.quality + d + 3) % 3 }),
            MenuItem("Head tracking", { if (store.headTracking) "On" else "Off" }, adjust = { store.headTracking = !store.headTracking; applySettings() }),
            MenuItem("Show HUD", { if (store.showHud) "On" else "Off" }, adjust = { store.showHud = !store.showHud }),
            MenuItem("About", { "▸" }, activate = { aboutOpen = true }),
            MenuItem("Reset Settings", { if (confirmingReset) "tap again!" else "" }, activate = {
                if (confirmingReset) {
                    store.resetSettings(); applySettings(); confirmingReset = false
                    if (store.scene != sceneIndex) switchScene(store.scene)
                    showToast("done")
                } else confirmingReset = true
            }),
        )

        fun onOpen() { selected = 0; confirmingReset = false }

        fun onSwipe(dir: Swipe) {
            when (dir) {
                Swipe.UP -> move(-1)
                Swipe.DOWN -> move(1)
                Swipe.FORWARD -> adjust(1)
                Swipe.BACK -> adjust(-1)
            }
        }

        private fun move(d: Int) { selected = (selected + d + items.size) % items.size; confirmingReset = false; sfx.tick() }
        private fun adjust(d: Int) { val it = items[selected]; if (it.adjust != null) { it.adjust.invoke(d); sfx.tick(1.3f) } }

        fun activate() {
            val it = items[selected]
            if (it.activate != null) { it.activate.invoke(); sfx.select() }
            else { it.adjust?.invoke(1); sfx.tick(1.3f) }
        }
    }

}
