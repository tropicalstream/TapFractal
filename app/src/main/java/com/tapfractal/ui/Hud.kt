package com.tapfractal.ui

import android.content.res.AssetManager
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.util.Log
import com.tapfractal.M
import com.tapfractal.engine.Game
import com.tapfractal.gl.HudTexture
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sin

/**
 * In-play HUD, toasts, settings menu, About page and the first-launch photosensitivity notice
 * (DESIGN §10–§12), drawn with Canvas onto the 640×480 HUD texture. Glow everything: each element
 * is drawn twice (blurred at 55 %, then crisp). No white fields, no filled panels.
 */
class Hud(private val game: Game, private val tex: HudTexture, am: AssetManager) {
    companion object {
        const val TAG = "TapFractal"
        const val CYAN = 0xFF40E0FF.toInt()
        const val MAGENTA = 0xFFFF3CC8.toInt()
        const val LIME = 0xFFB8FF3C.toInt()
        const val GOLD = 0xFFFFD24A.toInt()
        const val VIOLET = 0xFF9A6BFF.toInt()
    }

    private val bold: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold; maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL) }
    private val crisp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    private val strokeGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    /**
     * One credit = ("title · artist", "licence · source", optional sample credit), all shown in full
     * (CC-BY: title, author, licence, source — and the sampled work where the track page lists one).
     */
    private class Credit(val l1: String, val l2: String, val l3: String?)
    private val credits: List<Credit> = loadCredits(am)
    private var sinceDraw = 1f
    private var t = 0f
    var debugLine: String? = null
    /** The "yaw/pitch/roll · rung · ms" line; true only while the tapfractal_debug settings flag is non-empty. */
    var debugEnabled = false
    /** False when the last redraw left the texture fully transparent (Show HUD Off, nothing animating): the composite pass is skipped. */
    var hasContent = true; private set

    init {
        // The Effects row (SettingsStore.effects) drives the SFX bus in Sfx. The HUD is the one core-facing
        // object that holds both the store and the mixer, so it forwards the setting here (initial value +
        // every edit); Game.applySettings may also set `sfx.effects` — the two agree by construction.
        game.sfx.effects = game.store.effects
        game.store.effectsListener = { game.sfx.effects = it }
    }

    /** "https://www.ccmixter.org/files/cdk/50691" → "ccmixter.org/files/cdk/50691"; incompetech's long query → "incompetech.com · ISRC …". */
    private fun shortSource(url: String): String {
        if (url.isBlank()) return ""
        var s = url.substringAfter("://").removePrefix("www.").trimEnd('/')
        val isrc = Regex("[?&]isrc=([A-Za-z0-9]+)").find(s)?.groupValues?.get(1)
        if (isrc != null) return s.substringBefore('/') + " · ISRC $isrc"
        s = s.substringBefore('?')
        return if (s.length <= 40) s else s.substringBefore('/')
    }

    private fun loadCredits(am: AssetManager): List<Credit> {
        try {
            val txt = am.open("music/credits.json").bufferedReader().use { it.readText() }
            val out = ArrayList<Credit>()
            fun line(o: JSONObject): Credit {
                val title = o.optString("title", o.optString("track", "untitled"))
                val artist = o.optString("artist", o.optString("author", ""))
                val lic = o.optString("licence", o.optString("license", ""))
                val src = shortSource(o.optString("sourceUrl", o.optString("source", o.optString("url", ""))))
                // "Uses samples from" (ccMixter): the sampled work is a credit of its own — title, author, licence, source
                val samples = o.optJSONArray("samples")
                val l3 = if (samples != null && samples.length() > 0) (0 until samples.length()).joinToString(" · ") { i ->
                    val s = samples.getJSONObject(i)
                    val st = s.optString("title", ""); val sa = s.optString("artist", s.optString("author", ""))
                    val sl = s.optString("licence", s.optString("license", "")); val ss = shortSource(s.optString("sourceUrl", s.optString("source", "")))
                    listOf(if (st.isNotBlank()) "\"$st\"" else "", sa, sl, ss).filter { it.isNotBlank() }.joinToString(" · ")
                }.let { "samples: $it" } else null
                return Credit(listOf(title, artist).filter { it.isNotBlank() }.joinToString(" · "),
                              listOf(lic, src).filter { it.isNotBlank() }.joinToString(" · "), l3)
            }
            val trimmed = txt.trim()
            if (trimmed.startsWith("[")) { val a = JSONArray(trimmed); for (i in 0 until a.length()) out.add(line(a.getJSONObject(i))) }
            else {
                val o = JSONObject(trimmed)
                val arr = o.optJSONArray("tracks") ?: o.optJSONArray("scenes") ?: o.optJSONArray("music")
                if (arr != null) for (i in 0 until arr.length()) out.add(line(arr.getJSONObject(i)))
                else for (k in listOf("scene1", "scene2", "scene3", "scene4", "1", "2", "3", "4")) o.optJSONObject(k)?.let { out.add(line(it)) }
            }
            if (out.isNotEmpty()) return out.take(4)
        } catch (e: Exception) { Log.w(TAG, "music/credits.json unavailable (${e.message})") }
        return listOf(Credit("see assets/music/ATTRIBUTION.md", "", null))
    }

    /** Redraws when something changed or an animation is running (≤ 12 Hz). Returns true if the texture was updated. */
    fun draw(dt: Float): Boolean {
        t += dt; sinceDraw += dt
        val animating = game.toast != null || game.floaters.isNotEmpty() || game.state != Game.State.PLAY || game.depthPulseT < 1f || debugEnabled || game.lookAwayChevron() != null
        if (!game.hudDirty && !(animating && sinceDraw >= 0.08f)) return false
        if (game.hudDirty && sinceDraw < 0.03f && animating) return false
        game.hudDirty = false; sinceDraw = 0f
        val c = tex.canvas
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        hasContent = false   // set again by every primitive that paints something
        when {
            game.state == Game.State.NOTICE -> drawNotice(c)
            game.menuOpen && game.aboutOpen -> drawAbout(c)
            game.menuOpen -> drawMenu(c)
            else -> drawPlay(c)
        }
        tex.dirty = true
        return true
    }

    // ------------------------------------------------------------------ primitives

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, align: Paint.Align = Paint.Align.LEFT, alpha: Float = 1f) {
        if (s.isEmpty() || alpha <= 0f) return
        hasContent = true
        for (p in arrayOf(glow, crisp)) {
            p.textSize = size; p.color = color; p.textAlign = align
            p.letterSpacing = if (size <= 22f) 0.04f else 0f
            p.alpha = ((if (p === glow) 0.55f else 1f) * alpha * 255f).toInt().coerceIn(0, 255)
        }
        c.drawText(s, x, y, glow); c.drawText(s, x, y, crisp)
    }

    private fun rectGlow(c: Canvas, l: Float, tp: Float, r: Float, b: Float, color: Int) {
        hasContent = true
        strokeGlow.color = color; strokeGlow.alpha = 140; stroke.color = color
        c.drawRoundRect(l, tp, r, b, 8f, 8f, strokeGlow); c.drawRoundRect(l, tp, r, b, 8f, 8f, stroke)
    }

    // ------------------------------------------------------------------ pages

    private fun drawPlay(c: Canvas) {
        val g = game
        if (g.state == Game.State.ATTRACT) {
            val a = 0.6f * (0.7f + 0.3f * sin(M.TAU * 0.5f * t))
            text(c, "look to fly · swing your head · tap to flick", 320f, 400f, 22f, CYAN, Paint.Align.CENTER, a)
        }
        if (g.hudVisible && g.state == Game.State.PLAY) {
            text(c, g.scene.hudLabel, 40f, 52f, 18f, VIOLET)
            val ps = 1f + 0.5f * (1f - M.smoothstep(0f, 0.8f, g.depthPulseT))
            text(c, "DEPTH ${g.zoomTarget}", 40f, 428f, 28f * ps, GOLD)
            text(c, "SLUURPS ${g.store.totalSluurps}", 40f, 456f, 18f, CYAN)
            val (act, des) = g.targetPips()
            val sb = StringBuilder(); for (i in 0 until des) sb.append(if (i < act) "●" else "○")
            text(c, sb.toString(), 200f, 428f, 18f, VIOLET)
            // speed pips centred at (320,456): ◦ + four chevrons, lit up to the level
            val lv = g.flight.level
            val pips = StringBuilder("◦")
            for (i in 1..4) pips.append(if (i <= lv) " ›" else " ·")
            text(c, pips.toString(), 320f, 456f, 18f, LIME, Paint.Align.CENTER, if (lv == 0) 0.8f else 1f)
            text(c, "BEST ${g.store.bestDepth(g.scene.slot - 1)}", 600f, 456f, 18f, VIOLET, Paint.Align.RIGHT)
            for (f in g.floaters) text(c, f.text, f.x, f.y - 20f * (f.t / 0.6f), 22f, GOLD, Paint.Align.CENTER, 1f - f.t / 0.6f)
        }
        g.lookAwayChevron()?.let { d ->
            // 24 px chevron at the frame edge pointing back toward the Nave
            val cx = 320f + d.x * 260f; val cy = 240f - d.y * 180f
            val path = Path()
            val ax = d.x; val ay = -d.y
            path.moveTo(cx + ax * 12f, cy + ay * 12f)
            path.lineTo(cx - ax * 8f + ay * 10f, cy - ay * 8f - ax * 10f)
            path.lineTo(cx - ax * 8f - ay * 10f, cy - ay * 8f + ax * 10f)
            path.close()
            hasContent = true
            fill.color = MAGENTA; fill.maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL); fill.alpha = 140; c.drawPath(path, fill)
            fill.maskFilter = null; fill.alpha = 255; c.drawPath(path, fill)
        }
        g.toast?.let { text(c, it, 320f, 120f, 22f, MAGENTA, Paint.Align.CENTER, M.clamp(g.toastT / 0.3f, 0f, 1f)) }
        if (debugEnabled) debugLine?.let { text(c, it, 40f, 90f, 18f, LIME) }
    }

    private fun drawNotice(c: Canvas) {
        rectGlow(c, 80f, 118f, 560f, 362f, CYAN)
        text(c, "PHOTOSENSITIVITY NOTICE", 320f, 164f, 34f, MAGENTA, Paint.Align.CENTER)
        text(c, "Contains flashing lights and moving patterns.", 320f, 206f, 22f, CYAN, Paint.Align.CENTER)
        text(c, "Stop and rest if you feel dizzy or unwell.", 320f, 236f, 22f, CYAN, Paint.Align.CENTER)
        text(c, "Echo trails and stereo parallax can be", 320f, 266f, 22f, CYAN, Paint.Align.CENTER)
        text(c, "turned off in Settings (double-tap).", 320f, 294f, 22f, CYAN, Paint.Align.CENTER)
        val a = if (game.noticeArmed) 0.7f + 0.3f * sin(M.TAU * 0.5f * t) else 0.25f
        text(c, "TAP TO CONTINUE", 320f, 340f, 18f, LIME, Paint.Align.CENTER, a)
    }

    private fun drawMenu(c: Canvas) {
        val m = game.menu
        text(c, "SETTINGS", 40f, 80f, 34f, CYAN)
        // 11 rows (Effects added after Volume) at pitch 31 from y=114 → last baseline 424, selection bar to 433
        // — the same bottom edge as the old 10 × 34 layout, so the footer hint (16 px, y=462, glyph tops ≈ 450)
        // stays clear of the Reset Settings row and its bar; the title's descenders (80 + 7) clear the first
        // bar's top (114 − 24 = 90). 24 px bold sans is ~17 px cap + 5 px descender = 22 px per 31 px pitch,
        // and the 33 px bar overlaps neighbouring glyph boxes by ≤ 1 px. Verified offline with tools-free
        // Java2D geometry (same numbers) — see the SFX agent's notes.
        val y0 = 114f; val pitch = 31f
        for ((i, item) in m.items.withIndex()) {
            val y = y0 + i * pitch
            if (i == m.selected) {
                hasContent = true
                fill.maskFilter = null; fill.color = CYAN; fill.alpha = 31
                c.drawRoundRect(40f, y - 24f, 600f, y + 9f, 6f, 6f, fill)
                text(c, "▸", 60f, y, 24f, MAGENTA)
            }
            val v = item.value()
            val vc = if (v == "tap again!") MAGENTA else CYAN
            text(c, item.label, 90f, y, 24f, if (i == m.selected) CYAN else VIOLET)
            if (v.isNotEmpty()) text(c, v, 560f, y, 22f, vc, Paint.Align.RIGHT)
        }
        text(c, "double-tap closes · up/down move · fwd/back adjust · tap select", 320f, 462f, 16f, VIOLET, Paint.Align.CENTER)
        game.toast?.let { text(c, it, 320f, 40f, 22f, MAGENTA, Paint.Align.CENTER, M.clamp(game.toastT / 0.3f, 0f, 1f)) }
    }

    /**
     * About (DESIGN §11, credits per the CC-BY rule): every music credit on two lines — title · artist
     * (20 px cyan) over licence · source (15 px violet) — never elided; a line that would overflow the
     * 548 px column is drawn at a smaller size instead (`fitSize`). Fits 480 px tall with four credits.
     */
    private fun drawAbout(c: Canvas) {
        var y = 56f
        text(c, "TAPFRACTAL 1.0", 40f, y, 34f, CYAN); y += 30f
        text(c, "A head-swung eyeball in a live fractal.", 40f, y, 20f, VIOLET); y += 24f
        text(c, "Music:", 40f, y, 20f, GOLD); y += 22f
        val colW = 548f
        for (cr in credits) {
            text(c, cr.l1, 56f, y, fitSize(cr.l1, 20f, colW), CYAN); y += 18f
            if (cr.l2.isNotEmpty()) { text(c, cr.l2, 56f, y, fitSize(cr.l2, 15f, colW), VIOLET); y += 18f } else y += 2f
            cr.l3?.let { l3 -> text(c, l3, 56f, y, fitSize(l3, 15f, colW), VIOLET); y += 18f }
            y += 4f
        }
        y += 4f
        text(c, "Contains flashing lights and moving patterns.", 40f, y, 20f, MAGENTA); y += 22f
        text(c, "Stop and rest if you feel dizzy or unwell.", 40f, y, 20f, MAGENTA); y += 26f
        text(c, "Stereo parallax draws each eye from a slightly", 40f, y, 20f, CYAN); y += 22f
        text(c, "different point; Off is easiest on the eyes.", 40f, y, 20f, CYAN); y += 26f
        text(c, "tropicalstream · no network, no permissions", 40f, y, 16f, VIOLET)
    }

    /** Largest size ≤ `size` (≥ 12 px) at which `s` fits `maxW` with the HUD's letter spacing. */
    private fun fitSize(s: String, size: Float, maxW: Float): Float {
        var sz = size
        while (sz > 12f) {
            crisp.textSize = sz; crisp.letterSpacing = if (sz <= 22f) 0.04f else 0f
            if (crisp.measureText(s) <= maxW) break
            sz -= 0.5f
        }
        return sz
    }

}
