package com.tapfractal

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences-backed settings (DESIGN §11). Everything is read on the GL thread and written
 * from the GL thread (menu edits are marshalled there), so no locking. `photoAck` survives
 * Reset Settings; stats (best depth, total sluurps, onboarded) survive too.
 */
class SettingsStore(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("tapfractal", Context.MODE_PRIVATE)

    var scene: Int
        get() = p.getInt("scene", 0).coerceIn(0, 3)
        set(v) = p.edit().putInt("scene", v.coerceIn(0, 3)).apply()
    var music: Boolean
        get() = p.getBoolean("music", true)
        set(v) = p.edit().putBoolean("music", v).apply()
    /** 0..10 (tens of percent). */
    var volume: Int
        get() = p.getInt("volume", 7).coerceIn(0, 10)
        set(v) = p.edit().putInt("volume", v.coerceIn(0, 10)).apply()
    /**
     * Sound effects level, 0 Off · 1 Quiet · 2 Full (default Quiet: SFX bus −9 dB, hit cues a further −6 dB —
     * the owner's "too many sound effects over the music"). Music is never ducked by it.
     */
    var effects: Int
        get() = p.getInt("effects", 1).coerceIn(0, 2)
        set(v) { p.edit().putInt("effects", v.coerceIn(0, 2)).apply(); effectsListener?.invoke(effects) }
    /** Notified with the new level after every `effects` write (Sfx follows it without a core hook). */
    var effectsListener: ((Int) -> Unit)? = null
    /** 0 Off · 1 Subtle · 2 Deep */
    var stereo: Int
        get() = p.getInt("stereo", 1).coerceIn(0, 2)
        set(v) = p.edit().putInt("stereo", v.coerceIn(0, 2)).apply()
    /** 0 Off · 1 Soft · 2 Wild */
    var echo: Int
        get() = p.getInt("echo", 1).coerceIn(0, 2)
        set(v) = p.edit().putInt("echo", v.coerceIn(0, 2)).apply()
    /** 0 Auto · 1 Low · 2 High */
    var quality: Int
        get() = p.getInt("quality", 0).coerceIn(0, 2)
        set(v) = p.edit().putInt("quality", v.coerceIn(0, 2)).apply()
    var headTracking: Boolean
        get() = p.getBoolean("head", true)
        set(v) = p.edit().putBoolean("head", v).apply()
    var showHud: Boolean
        get() = p.getBoolean("hud", true)
        set(v) = p.edit().putBoolean("hud", v).apply()

    var photoAck: Boolean
        get() = p.getBoolean("photoAck", false)
        set(v) = p.edit().putBoolean("photoAck", v).apply()
    var onboarded: Boolean
        get() = p.getBoolean("onboarded", false)
        set(v) = p.edit().putBoolean("onboarded", v).apply()
    var flickHintShown: Boolean
        get() = p.getBoolean("flickHint", false)
        set(v) = p.edit().putBoolean("flickHint", v).apply()
    var totalSluurps: Int
        get() = p.getInt("totalSluurps", 0)
        set(v) = p.edit().putInt("totalSluurps", v).apply()

    fun bestDepth(scene: Int) = p.getInt("best$scene", 0)
    fun setBestDepth(scene: Int, v: Int) { if (v > bestDepth(scene)) p.edit().putInt("best$scene", v).apply() }

    /** Reset Settings: rows only; photoAck, stats and onboarding are kept. */
    fun resetSettings() {
        p.edit().remove("scene").remove("music").remove("volume").remove("effects").remove("stereo").remove("echo")
            .remove("quality").remove("head").remove("hud").apply()
        effectsListener?.invoke(effects)
    }

    val volumeFloat: Float get() = volume / 10f
    val effectsName: String get() = arrayOf("Off", "Quiet", "Full")[effects]
    val stereoName: String get() = arrayOf("Off", "Subtle", "Deep")[stereo]
    val echoName: String get() = arrayOf("Off", "Soft", "Wild")[echo]
    val qualityName: String get() = arrayOf("Auto", "Low", "High")[quality]
}
