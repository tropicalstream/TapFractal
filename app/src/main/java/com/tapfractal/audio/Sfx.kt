package com.tapfractal.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import com.tapfractal.M
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Synthesised one-shots on a single streaming AudioTrack mixer thread (no binary assets, DESIGN §4.10):
 * hit chime (FM bell, pentatonic by level, scene root; golden = shimmer arpeggio), flick twang/whoosh,
 * re-tether snap, menu tick/select, scene swoosh, growth whoomp, near-miss whiff, spawn bloom, timeout sigh,
 * fractal bounce bong. Voices are synthesised on a dedicated worker thread at trigger time (a 0.6 s
 * bell is ~29k sin/exp samples, several ms — never on the GL thread) and summed at 48 kHz on the mixer
 * thread; the fixed cues are pre-generated at start(), the level-dependent bells are cached by pitch.
 *
 * Every cue passes one central gate (`gate`) before a voice is synthesised: a per-cue cooldown (bounce
 * 0.6 s, snap 0.8 s, hum re-arm 4 s, …), a global limiter of ≤ MAX_CUES_PER_S toy cues in any 1 s window,
 * and the Effects bus gain (Off / Quiet −9 dB, hit cues a further −6 dB / Full). The bus is a plain gain on
 * this mixer — music is never ducked. Every cue that actually plays is logged as
 * `sfx cue=<name> gain=<linear>` so cues/min can be counted from logcat.
 */
class Sfx {
    companion object {
        const val TAG = "TapFractal"
        const val SR = 48000
        private const val BLOCK = 480 // 10 ms
        /** Effects setting (SettingsStore.effects): 0 Off · 1 Quiet · 2 Full. */
        const val EFFECTS_OFF = 0
        const val EFFECTS_QUIET = 1
        const val EFFECTS_FULL = 2
        const val QUIET_BUS_DB = -9f
        const val QUIET_HIT_DB = -6f
        /** Global limiter: at most this many toy cues in any rolling second (menu tick/select are exempt). */
        const val MAX_CUES_PER_S = 3
        /** Per-cue minimum spacing in seconds (a cue arriving sooner is dropped, not queued). */
        val COOLDOWN_S: Map<String, Float> = mapOf(
            "bounce" to 1.0f,   // fractal bong/clang — same value as Toy.BOUNCE_COOLDOWN
            "snap" to 0.8f,     // re-tether snap
            "hum" to 4.0f,      // continuous hum re-arm (no hum voice exists yet; any future one must pass this gate)
            "twang" to 0.2f,    // flick (Toy already locks out 250 ms)
            "whiff" to 0.5f,    // near miss
            "bloom" to 0.4f,    // target spawn — several slots spawn together on entry
            "chime" to 0.2f,    // hit (Toy hit cooldown 250 ms)
            "whoomp" to 0.4f,   // growth
            "sigh" to 1.0f,     // timeout
            "swoosh" to 0.5f,   // scene switch
            "tick" to 0.04f,    // menu / speed step
            "select" to 0.08f,
        )
        /** Hit cues: the loudest of the table, trimmed a further QUIET_HIT_DB at Effects = Quiet. */
        private val HIT_CUES = setOf("chime", "shimmer", "whoomp")
        /** UI cues: exempt from the global limiter (a swipe must always answer), still under the bus gain. */
        private val UI_CUES = setOf("tick", "select")
    }

    private class Voice(val data: FloatArray, val gain: Float) { var pos = 0 }

    private val queue = ConcurrentLinkedQueue<Voice>()
    private val active = ArrayList<Voice>()
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile var volume = 0.56f   // 0.8 × Volume setting
    /** Effects level 0 Off · 1 Quiet · 2 Full (SettingsStore.effects; default Quiet). Bus gain only, never music. */
    @Volatile var effects = EFFECTS_QUIET
        set(v) { val nv = v.coerceIn(EFFECTS_OFF, EFFECTS_FULL); if (nv != field) { field = nv; Log.i(TAG, "sfx effects=${arrayOf("off", "quiet", "full")[nv]} bus=${"%.1f".format(busDb(nv))}dB") } }
    private fun busDb(level: Int) = when (level) { EFFECTS_QUIET -> QUIET_BUS_DB; EFFECTS_FULL -> 0f; else -> -120f }
    // ---- cue gate state (GL thread only: every cue is triggered from Game) ----
    private val lastCueMs = HashMap<String, Long>()
    private val recentMs = LongArray(MAX_CUES_PER_S)   // ring of the last MAX_CUES_PER_S toy-cue times
    private var recentN = 0
    private var dropped = 0
    private val cache = HashMap<String, FloatArray>()
    /** Single-thread synthesiser: cue → buffer → mixer queue, in trigger order. Null while stopped. */
    @Volatile private var synth: ExecutorService? = null

    fun start() {
        if (running) return
        try {
            val minBuf = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(max(minBuf, BLOCK * 2 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            t.play()
            track = t
            running = true
            thread = Thread({ mixLoop() }, "TapFractal.sfx").also { it.priority = Thread.MAX_PRIORITY - 1; it.start() }
            val ex = Executors.newSingleThreadExecutor { r -> Thread(r, "TapFractal.synth").also { it.isDaemon = true } }
            synth = ex
            // pre-generate every fixed cue off the GL thread so the first scene switch / flick / bounce costs nothing there
            ex.execute {
                cached("swoosh") { swooshWave() }; cached("whoomp") { whoompWave() }; cached("snap") { snapWave() }
                cached("select") { selectWave() }; cached("whiff") { whiffWave() }; cached("bloom") { bloomWave() }
                cached("sigh") { sighWave() }; cached("clang") { clangWave() }; cached("plip") { tickWave(900f, 0.05f) }
                cached("bong") { bell(110f, 0.5f) }
                for (p in floatArrayOf(1f, 0.9f, 1.2f, 1.3f)) cached("tick$p") { tickWave(2000f * p, 0.012f) }
            }
        } catch (e: Exception) { Log.e(TAG, "AudioTrack unavailable: $e") }
    }

    fun stop() {
        running = false
        synth?.shutdownNow(); synth = null
        thread?.join(200); thread = null
        try { track?.stop(); track?.release() } catch (_: Exception) {}
        track = null
    }

    private fun mixLoop() {
        val mix = FloatArray(BLOCK); val out = ShortArray(BLOCK)
        while (running) {
            val t = track ?: break
            java.util.Arrays.fill(mix, 0f)
            while (true) { val v = queue.poll() ?: break; active.add(v) }
            val it = active.iterator()
            while (it.hasNext()) {
                val v = it.next()
                val n = min(BLOCK, v.data.size - v.pos)
                for (i in 0 until n) mix[i] += v.data[v.pos + i] * v.gain
                v.pos += n
                if (v.pos >= v.data.size) it.remove()
            }
            val vol = volume
            for (i in 0 until BLOCK) { val s = M.clamp(mix[i] * vol, -1f, 1f); out[i] = (s * 32767f).toInt().toShort() }
            t.write(out, 0, BLOCK)
        }
    }

    /**
     * The central cue gate. Returns the total gain in dB (cue level + Effects bus + hit trim) for a cue that
     * may play now, or null when it is dropped: Effects Off, inside its per-cue cooldown, or — for toy cues —
     * when MAX_CUES_PER_S have already played in the last second. A dropped cue is dropped, never deferred
     * (a late bong is worse than none). Logs `sfx cue=<name> gain=<linear>` for every cue that passes.
     */
    private fun gate(name: String, cueDb: Float): Float? {
        if (!running) return null
        val level = effects
        if (level == EFFECTS_OFF) return null
        val now = SystemClock.uptimeMillis()
        val cd = COOLDOWN_S[name] ?: 0f
        val last = lastCueMs[name]
        if (last != null && now - last < (cd * 1000f).toLong()) { drop(name, "cooldown ${cd}s"); return null }
        val ui = name in UI_CUES
        if (!ui) {
            // ring holds the last MAX_CUES_PER_S toy-cue times; full and the oldest is < 1 s ago → limited
            if (recentN >= MAX_CUES_PER_S && now - recentMs[0] < 1000L) { drop(name, "limit ${MAX_CUES_PER_S}/s"); return null }
            if (recentN < MAX_CUES_PER_S) recentMs[recentN++] = now
            else { System.arraycopy(recentMs, 1, recentMs, 0, MAX_CUES_PER_S - 1); recentMs[MAX_CUES_PER_S - 1] = now }
        }
        lastCueMs[name] = now
        var db = cueDb + busDb(level)
        if (level == EFFECTS_QUIET && name in HIT_CUES) db += QUIET_HIT_DB
        val lin = 10f.pow(db / 20f)
        Log.i(TAG, "sfx cue=$name gain=${"%.3f".format(lin)} db=${"%.1f".format(db)} bus=${"%.0f".format(busDb(level))} vol=${"%.2f".format(volume)}")
        return db
    }

    private fun drop(name: String, why: String) {
        dropped++
        if (dropped <= 20 || dropped % 50 == 0) Log.d(TAG, "sfx drop $name ($why, dropped=$dropped)")
    }

    /**
     * Queue a voice: `gen` runs on the synth worker (in trigger order), never on the caller's thread.
     * Without a worker (start() failed) the cue is dropped — silence rather than a stall. `gainDb` is the
     * total from `gate` (already includes the bus).
     */
    private fun play(gainDb: Float, gen: () -> FloatArray) {
        if (!running) return
        val ex = synth ?: return
        val gain = 10f.pow(gainDb / 20f)
        try { ex.execute { if (running) queue.add(Voice(gen(), gain)) } } catch (_: RejectedExecutionException) {}
    }

    /** Gate + play in one step for the simple cues. */
    private fun cue(name: String, cueDb: Float, gen: () -> FloatArray) { val db = gate(name, cueDb) ?: return; play(db, gen) }

    // ------------------------------------------------------------------ cues

    /** Hit chime: FM bell 600 ms, pentatonic degree [0,2,4,7,9][level mod 5], octave (level/5) mod 3, scene root. */
    fun chime(rootHz: Float, level: Int, golden: Boolean, clean: Boolean, velocity: Float) {
        val deg = intArrayOf(0, 2, 4, 7, 9)[level % 5]
        val oct = (level / 5) % 3
        val f = rootHz * 2f.pow(oct.toFloat()) * 2f.pow(deg / 12f) * 2f
        val db = gate("chime", -3f + 6f * (velocity - 0.5f)) ?: return
        play(db) { bellCached(f, 0.6f) }
        // the shimmer is part of the same hit: one cue for the limiter, its own gain/log line
        if (golden || clean) shimmer(f * 2f)
    }

    private fun shimmer(baseHz: Float) {
        val level = effects
        if (level == EFFECTS_OFF) return
        var db = -6f + busDb(level); if (level == EFFECTS_QUIET) db += QUIET_HIT_DB
        Log.i(TAG, "sfx cue=shimmer gain=${"%.3f".format(10f.pow(db / 20f))} db=${"%.1f".format(db)} bus=${"%.0f".format(busDb(level))} vol=${"%.2f".format(volume)}")
        val notes = intArrayOf(0, 4, 7, 12)
        for ((k, n) in notes.withIndex()) {
            play(db) {
                val d = bellCached(baseHz * 2f.pow(n / 12f), 0.35f)
                val padded = FloatArray(d.size + (k * SR * 60 / 1000)); System.arraycopy(d, 0, padded, padded.size - d.size, d.size)
                padded
            }
        }
    }

    fun twang(tension: Float) { val f = 180f + 240f * tension; cue("twang", -6f) { twangWave(f) } }
    fun snap(strength: Float) { cue("snap", -8f + 6f * (strength - 0.5f)) { cached("snap") { snapWave() } } }
    fun tick(pitch: Float = 1f) { cue("tick", -10f) { cached("tick$pitch") { tickWave(2000f * pitch, 0.012f) } } }
    fun select() { cue("select", -8f) { cached("select") { selectWave() } } }
    fun swoosh() { cue("swoosh", -6f) { cached("swoosh") { swooshWave() } } }
    fun whoomp() { cue("whoomp", -4f) { cached("whoomp") { whoompWave() } } }
    fun whiff() { cue("whiff", -14f) { cached("whiff") { whiffWave() } } }
    fun bloom() { cue("bloom", -14f) { cached("bloom") { bloomWave() } } }
    fun sigh() { cue("sigh", -12f) { cached("sigh") { sighWave() } } }
    /** Fractal bounce (3D rooms; the 2D plane contact is silent — Toy.planeBounce). Gate name "bounce", 1.0 s. */
    fun bong(sceneSlot: Int, strength: Float) {
        cue("bounce", -8f + 6f * (M.clamp(strength / 3f, 0f, 1f) - 0.5f)) {
            when (sceneSlot) {
                3 -> cached("clang") { clangWave() }
                1 -> cached("plip") { tickWave(900f, 0.05f) }
                else -> cached("bong") { bell(110f, 0.5f) }
            }
        }
    }

    private fun cached(key: String, gen: () -> FloatArray): FloatArray = synchronized(cache) { cache.getOrPut(key, gen) }

    /** Bells are a pure function of (pitch, duration); a level's chime recurs, so keep them (≤ ~115 KB each). */
    private fun bellCached(f: Float, dur: Float): FloatArray = cached("bell:${"%.2f".format(f)}:$dur") { bell(f, dur) }

    // ------------------------------------------------------------------ synthesis

    private fun bell(f: Float, dur: Float): FloatArray {
        val n = (dur * SR).toInt(); val out = FloatArray(n)
        val ratio = 3.01f
        for (i in 0 until n) {
            val t = i.toFloat() / SR
            val env = exp(-t / (dur * 0.35f)) * min(1f, t / 0.004f)
            val idx = 2f * exp(-t / (dur * 0.5f))
            val mod = sin(M.TAU * f * ratio * t) * idx
            out[i] = sin(M.TAU * f * t + mod) * env * 0.6f
        }
        return out
    }

    private fun twangWave(f: Float): FloatArray {
        // Karplus–Strong pluck 80 ms + short whoosh
        val n = (0.16f * SR).toInt(); val out = FloatArray(n)
        val period = max(2, (SR / f).toInt()); val buf = FloatArray(period) { (Math.random().toFloat() * 2f - 1f) }
        var p = 0
        var noise = 0f
        for (i in 0 until n) {
            val t = i.toFloat() / SR
            val s = buf[p]; val nx = buf[(p + 1) % period]
            buf[p] = 0.5f * (s + nx) * 0.996f
            p = (p + 1) % period
            noise = noise * 0.92f + (Math.random().toFloat() * 2f - 1f) * 0.08f
            val ks = s * exp(-t / 0.05f)
            val wh = noise * 3f * exp(-t / 0.06f) * min(1f, t / 0.01f)
            out[i] = (ks * 0.8f + wh * 0.5f)
        }
        return out
    }

    private fun snapWave(): FloatArray {
        val n = (0.14f * SR).toInt(); val out = FloatArray(n)
        var bp1 = 0f; var bp2 = 0f
        for (i in 0 until n) {
            val t = i.toFloat() / SR
            val nz = if (t < 0.03f) (Math.random().toFloat() * 2f - 1f) else 0f
            // cheap band-pass around 2 kHz
            bp1 += 0.26f * (nz - bp1); bp2 += 0.26f * (bp1 - bp2)
            val click = (bp1 - bp2) * 6f
            val thump = sin(M.TAU * 90f * t) * exp(-t / 0.04f) * 0.9f
            out[i] = click + thump
        }
        return out
    }

    private fun tickWave(f: Float, dur: Float): FloatArray {
        val n = (dur * SR).toInt(); val out = FloatArray(n)
        for (i in 0 until n) { val t = i.toFloat() / SR; out[i] = sin(M.TAU * f * t) * exp(-t / (dur * 0.3f)) * 0.8f }
        return out
    }

    private fun selectWave(): FloatArray {
        val n = (0.12f * SR).toInt(); val out = FloatArray(n)
        for (i in 0 until n) { val t = i.toFloat() / SR; val f = if (t < 0.05f) 1200f else 1800f; out[i] = sin(M.TAU * f * t) * exp(-(t % 0.05f) / 0.02f) * 0.7f }
        return out
    }

    private fun swooshWave(): FloatArray {
        val n = (0.6f * SR).toInt(); val out = FloatArray(n)
        var lp = 0f; var lp2 = 0f
        for (i in 0 until n) {
            val t = i.toFloat() / SR; val x = t / 0.6f
            val cutoff = 0.02f + 0.3f * sin(M.PI * x)
            val nz = Math.random().toFloat() * 2f - 1f
            lp += cutoff * (nz - lp); lp2 += cutoff * (lp - lp2)
            out[i] = lp2 * 3f * sin(M.PI * x)
        }
        return out
    }

    private fun whoompWave(): FloatArray {
        val n = (0.5f * SR).toInt(); val out = FloatArray(n)
        var ph = 0f
        for (i in 0 until n) {
            val t = i.toFloat() / SR; val f = 80f - 40f * (t / 0.5f)
            ph += M.TAU * f / SR
            val s = sin(ph) * exp(-t / 0.25f) * min(1f, t / 0.01f)
            out[i] = kotlin.math.tanh(s * 1.6f)
        }
        return out
    }

    private fun whiffWave(): FloatArray {
        val n = (0.12f * SR).toInt(); val out = FloatArray(n)
        var lp = 0f; var hp = 0f
        for (i in 0 until n) {
            val t = i.toFloat() / SR; val f = 800f - 500f * (t / 0.12f); val a = M.clamp(f / SR * 6f, 0.02f, 0.5f)
            val nz = Math.random().toFloat() * 2f - 1f
            lp += a * (nz - lp); hp += a * 0.5f * (lp - hp)
            out[i] = (lp - hp) * 4f * sin(M.PI * t / 0.12f)
        }
        return out
    }

    private fun bloomWave(): FloatArray {
        val n = (0.3f * SR).toInt(); val out = FloatArray(n)
        val fs = floatArrayOf(440f, 554.4f, 659.3f)
        for (i in 0 until n) { val t = i.toFloat() / SR; var s = 0f; for (f in fs) s += sin(M.TAU * f * t); out[i] = s / 3f * sin(M.PI * t / 0.3f) * 0.8f }
        return out
    }

    private fun sighWave(): FloatArray {
        val n = (0.4f * SR).toInt(); val out = FloatArray(n)
        for (i in 0 until n) { val t = i.toFloat() / SR; val f = if (t < 0.2f) 330f else 277.2f; out[i] = (sin(M.TAU * f * t) + 0.5f * sin(M.TAU * f * 2f * t)) * 0.5f * exp(-(t % 0.2f) / 0.12f) }
        return out
    }

    private fun clangWave(): FloatArray {
        val n = (0.25f * SR).toInt(); val out = FloatArray(n)
        val fs = floatArrayOf(523f, 1187f, 1911f, 2734f)
        for (i in 0 until n) { val t = i.toFloat() / SR; var s = 0f; for ((k, f) in fs.withIndex()) s += sin(M.TAU * f * t) * exp(-t / (0.12f / (k + 1))); out[i] = s * 0.35f }
        return out
    }
}
