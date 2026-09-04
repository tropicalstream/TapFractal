package com.tapfractal.audio

import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.SystemClock
import android.util.Log
import com.tapfractal.M
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.sin

/**
 * Looping scene music (MediaPlayer on assets, no permissions) with a 1 s equal-power crossfade
 * between two players on scene change, and the pre-analysed envelope `music/sceneN.env.json`
 * indexed by playback position → uBeat (onset-gated, 340 ms refractory, τ 0.18 s), uEnergy, uBass,
 * uTreble. Missing/malformed files degrade to energy 0.5, bass/treble 0.3 and a 120 BPM synthetic beat.
 */
class Music(private val am: AssetManager) {
    companion object { const val TAG = "TapFractal" }

    class Envelope(val hopMs: Int, val bpm: Float, val energy: IntArray, val bass: IntArray, val treble: IntArray, val onsets: IntArray) {
        val lengthMs: Int get() = energy.size * hopMs
    }

    /**
     * `player`/`prepared`/`playing` are written on the GL thread (load/update/release) AND on the
     * main thread (onPrepared: the players are created on the GL thread, which has no Looper, so
     * their event handler binds to the main looper). Every such write happens under `synchronized(this@Music)`.
     */
    private class Track(val index: Int) {
        @Volatile var player: MediaPlayer? = null
        @Volatile var env: Envelope? = null
        @Volatile var envDone = false
        @Volatile var prepared = false
        var vol = 0f            // crossfade weight 0..1
        var fadeDir = 0         // +1 in, −1 out
        var lastPollMs = 0L; var lastPollAt = 0L; @Volatile var playing = false
        var lastPulseMs = -1000L
        var lastPosMs = 0L
    }

    private var cur: Track? = null
    private var old: Track? = null
    var enabled = true
        set(v) { field = v; applyPlayState() }
    var volume = 0.7f
        set(v) { field = v; applyVolumes() }

    // outputs
    var beat = 0f; private set
    var energy = 0.5f; private set
    var bass = 0.3f; private set
    var treble = 0.3f; private set
    /** True on the frame a gated onset fired. */
    var onset = false; private set
    var envelopeOk = false; private set
    private var paused = false
    private var synthT = 0f

    fun load(sceneIndex: Int, crossfade: Boolean) {
        val t = Track(sceneIndex)
        var prev: Track? = null
        synchronized(this) {
            // the cur/old reassignment and the release of the outgoing player happen under the lock so an
            // onPrepared callback on the main thread can never see a track that is being released
            old?.let { release(it) }
            prev = cur
            cur = t
            if (prev != null) { if (crossfade) { prev.fadeDir = -1; old = prev } else release(prev) }
        }
        t.vol = if (crossfade && prev != null) 0f else 1f
        t.fadeDir = if (crossfade && prev != null) 1 else 0
        loadEnvelope(t)
        try {
            val afd = am.openFd("music/scene${sceneIndex + 1}.mp3")
            val mp = MediaPlayer()
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            mp.isLooping = true
            mp.setOnPreparedListener { p ->
                synchronized(this) {
                    // runs on the main thread; `t.player === p` is false once release(t) has run
                    if (t.player !== p) return@synchronized
                    t.prepared = true
                    if (t === cur || t === old) {
                        try {
                            p.setVolume(t.vol * volume, t.vol * volume)
                            if (enabled && !paused) { p.start(); t.playing = true }
                        } catch (e: IllegalStateException) { Log.w(TAG, "onPrepared after release: $e") }
                    }
                }
            }
            mp.setOnErrorListener { _, what, extra -> Log.e(TAG, "MediaPlayer error $what/$extra"); true }
            t.player = mp
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "music/scene${sceneIndex + 1}.mp3 unavailable (${e.message}) — silent with fallback envelope")
            t.player = null
        }
    }

    private fun loadEnvelope(t: Track) {
        Thread {
            try {
                val txt = am.open("music/scene${t.index + 1}.env.json").bufferedReader().use { it.readText() }
                val o = JSONObject(txt)
                val hop = o.optInt("hopMs", 20)
                val bpm = o.optDouble("bpm", 120.0).toFloat()
                fun arr(k: String): IntArray { val a = o.getJSONArray(k); return IntArray(a.length()) { a.getInt(it) } }
                val e = arr("energy"); val b = arr("bass"); val tr = arr("treble"); val on = arr("onsets")
                if (e.isEmpty() || b.size != e.size || tr.size != e.size || hop <= 0) throw IllegalArgumentException("bad envelope")
                t.env = Envelope(hop, bpm, e, b, tr, on)
                Log.i(TAG, "envelope scene${t.index + 1}: ${e.size} hops @${hop} ms, ${on.size} onsets, bpm $bpm")
            } catch (ex: Exception) {
                Log.w(TAG, "envelope scene${t.index + 1} missing/malformed (${ex.message}) — synthetic 120 BPM fallback")
                t.env = null
            }
            t.envDone = true
        }.start()
    }

    /** Stops and releases the track's player under the lock (the lock is reentrant, callers may hold it). */
    private fun release(t: Track) {
        synchronized(this) {
            val p = t.player
            t.player = null; t.playing = false; t.prepared = false
            if (p != null) {
                try { p.stop() } catch (_: Exception) {}
                try { p.release() } catch (_: Exception) {}
            }
        }
    }

    private fun applyPlayState() {
        synchronized(this) {
            for (t in listOfNotNull(cur, old)) {
                val p = t.player ?: continue
                if (!t.prepared) continue
                try {
                    if (enabled && !paused) { if (!p.isPlaying) p.start(); t.playing = true }
                    else { if (p.isPlaying) p.pause(); t.playing = false }
                } catch (_: Exception) {}
            }
        }
    }

    private fun applyVolumes() {
        for (t in listOfNotNull(cur, old)) { val p = t.player ?: continue; if (t.prepared) try { val v = equalPower(t.vol) * volume; p.setVolume(v, v) } catch (_: Exception) {} }
    }

    private fun equalPower(x: Float) = sin(x * M.PI * 0.5f)

    fun pause() { paused = true; applyPlayState() }
    fun resume() { paused = false; applyPlayState() }

    /** Playback position (ms) with extrapolation between polls. */
    private fun position(t: Track): Long {
        val p = t.player ?: return 0L
        if (!t.prepared || !t.playing) return t.lastPollMs
        val now = SystemClock.uptimeMillis()
        if (now - t.lastPollAt > 250L) {
            try { t.lastPollMs = p.currentPosition.toLong(); t.lastPollAt = now } catch (_: Exception) {}
            return t.lastPollMs
        }
        return t.lastPollMs + (now - t.lastPollAt)
    }

    fun update(dt: Float) {
        onset = false
        // crossfade
        old?.let { o ->
            o.vol -= dt
            if (o.vol <= 0f) synchronized(this) { release(o); old = null }
            else { val p = o.player; if (p != null && o.prepared) try { val v = equalPower(o.vol) * volume; p.setVolume(v, v) } catch (_: Exception) {} }
        }
        cur?.let { c ->
            if (c.fadeDir > 0) { c.vol += dt; if (c.vol >= 1f) { c.vol = 1f; c.fadeDir = 0 }
                val p = c.player; if (p != null && c.prepared) try { val v = equalPower(c.vol) * volume; p.setVolume(v, v) } catch (_: Exception) {} }
        }
        val c = cur
        if (!enabled || c == null) {
            // Music off: envelope frozen at zero (rooms breathe on uTime alone)
            beat *= exp(-dt / 0.18f); energy += (0f - energy) * M.ease(dt, 0.25f); bass += (0f - bass) * M.ease(dt, 0.12f); treble += (0f - treble) * M.ease(dt, 0.12f)
            envelopeOk = false
            return
        }
        val env = c.env
        if (env != null && c.playing) {
            envelopeOk = true
            val pos = position(c)
            val loopMs = env.lengthMs.coerceAtLeast(1)
            val pm = (pos % loopMs)
            val idx = (pm / env.hopMs).toInt().coerceIn(0, env.energy.size - 1)
            val e = env.energy[idx] / 255f; val b = env.bass[idx] / 255f; val tr = env.treble[idx] / 255f
            energy += (e - energy) * M.ease(dt, 0.25f); bass += (b - bass) * M.ease(dt, 0.12f); treble += (tr - treble) * M.ease(dt, 0.12f)
            // onset detection in (lastPos, pos]
            val last = c.lastPosMs
            var fired = false
            if (pos >= last) fired = anyOnset(env, last % loopMs, pm, last, pos) else fired = true // loop wrap
            c.lastPosMs = pos
            if (fired && pos - c.lastPulseMs >= 340L) { c.lastPulseMs = pos; beat = 1f; onset = true } else beat *= exp(-dt / 0.18f)
        } else {
            // fallback: constant envelope + synthetic 120 BPM
            envelopeOk = false
            energy += (0.5f - energy) * M.ease(dt, 0.25f); bass += (0.3f - bass) * M.ease(dt, 0.12f); treble += (0.3f - treble) * M.ease(dt, 0.12f)
            synthT += dt
            if (synthT >= 0.5f) { synthT -= 0.5f; beat = 1f; onset = true } else beat *= exp(-dt / 0.18f)
        }
    }

    private fun anyOnset(env: Envelope, a: Long, b: Long, rawA: Long, rawB: Long): Boolean {
        if (rawB - rawA > 2000L) return false // huge jump (seek): ignore
        if (b < a) return true
        val on = env.onsets
        // binary search first onset > a
        var lo = 0; var hi = on.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (on[mid] <= a) lo = mid + 1 else hi = mid }
        return lo < on.size && on[lo] <= b
    }

    /** Milliseconds until the next onset (beat-quantised whoomp); null without an envelope. */
    fun msToNextOnset(): Long? {
        val c = cur ?: return null; val env = c.env ?: return null
        if (!c.playing) return null
        val pos = position(c) % env.lengthMs.coerceAtLeast(1)
        val on = env.onsets; if (on.isEmpty()) return null
        var lo = 0; var hi = on.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (on[mid] <= pos) lo = mid + 1 else hi = mid }
        return if (lo < on.size) (on[lo] - pos) else (env.lengthMs - pos + on[0])
    }

    fun release() { synchronized(this) { cur?.let { release(it) }; old?.let { release(it) }; cur = null; old = null } }

}
