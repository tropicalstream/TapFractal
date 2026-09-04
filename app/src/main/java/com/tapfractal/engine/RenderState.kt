package com.tapfractal.engine

import com.tapfractal.Mat3
import com.tapfractal.Vec2
import com.tapfractal.Vec3
import com.tapfractal.scenes.Scene

/** One scene's per-frame render inputs (the outgoing and incoming sides of a crossfade each get one). */
class SceneDraw {
    var scene: Scene? = null
    /** Camera centre for THIS scene's world (the outgoing scene keeps its own position during a fade). */
    val camCenter = Vec3()
    /** 2D rooms: (pan.x, pan.y, rotation) → uCamPos. */
    val cam2D = Vec3()
    var fade = 1f
    var transition = 0f
    var time = 0f
    var zoom = 0f
    var travel = 0f
    var speed = 0f
    var lookAway = 0f
}

/**
 * Everything the pipeline needs for one frame, filled by [Game] on the GL thread. World-space toy
 * data is expressed relative to the incoming/current scene's world; the pipeline offsets it for
 * the outgoing scene by (outgoing.camCenter − current.camCenter).
 */
class RenderState {
    val current = SceneDraw()
    val outgoing = SceneDraw()
    var crossfading = false

    val camRot = Mat3()
    var dt = 0.016f
    var fov = 0.35f
    /** Half-interaxial s (0 = mono). */
    var eyeSep = 0f
    /** Convergence distance for uUvShift. */
    var convergence = 1.4f

    // toys (world space of the current scene)
    val sluurp = Vec3(); var sluurpRadius = 0.045f
    val sluurpVel = Vec3(); var sluurpSquash = 0f
    var sluurpGlow = 0f
    val sluurpEye = Vec3()
    val anchor = Vec3()
    val rope = Array(12) { Vec3() }; var ropeN = 12
    var ropeTension = 0f; var ropeFree = 0f
    val targetPos = Array(6) { Vec3() }
    val targetRadius = FloatArray(6)
    val targetPulse = FloatArray(6)
    val targetLife = FloatArray(6) { 1f }
    val toyBounds = Vec3(); var toyBoundsR = 0f

    // music / pulses
    var beat = 0f; var energy = 0.5f; var bass = 0.3f; var treble = 0.3f
    var hue = 0f
    var hitPulse = 0f; var hitFlash = 0f
    /** Seconds since the last hit (the rung controller holds its rung for 2 s after one). */
    var hitAge = 1e3f
    var attract = 0f
    val gravity = Vec3(0f, -1f, 0f)
    val headRate = Vec3()

    // post
    var echoMix = 0f; var echoZoom = 1f; var echoRot = 0f; val echoCentre = Vec2()
    val echoTint = Vec3(1f, 1f, 1f)
    var aberr = 0.002f; var breath = 0f; var exposure = 1f; var prism = 0
    var menuDim = false

    // quality
    var rungCap = 9
    var forceRung = -1
    /** Settings › Quality: 0 Auto · 1 Low · 2 High (drives the pacing mode, CORE_API §10). */
    var qualityMode = 0
    var hudAlpha = 1f
}
