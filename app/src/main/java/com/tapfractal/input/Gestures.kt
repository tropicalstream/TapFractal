package com.tapfractal.input

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import com.tapfractal.engine.Swipe
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Main-thread input classifier for the X3 Pro temple pad (SPEC "Input", DESIGN §11):
 *  - the left pad (cyttsp6) is ignored entirely;
 *  - a physical tap arrives as a KEY (BUTTON_A / DPAD_CENTER / ENTER / SPACE) and often also as a short
 *    touch — the two are de-duplicated within 60 ms;
 *  - a KEY release after a held press (the system quick-settings long-press, ≥ [SYSTEM_HOLD_MS], a
 *    repeat, or a cancelled event) is dropped, as the rest of the suite does;
 *  - a single tap is DEFERRED [TAP_DEFER_MS] (suite convention): on the X3 every click of a
 *    double-tap arrives as its own KEY UP before the system adds KEYCODE_BACK, so firing on the first
 *    click would flick / activate the selected menu row on every double-tap. A second tap 40–320 ms
 *    after the first cancels the single tap and IS the double-tap (the system's KEYCODE_BACK that
 *    follows is absorbed); a lone KEYCODE_BACK (injected `input keyevent 4`, or a pair whose clicks
 *    were not delivered) still opens/closes the menu;
 *  - the double-tap itself is deferred [BACK_DEFER_MS] so that a third tap inside that window becomes
 *    a triple-tap (recentre). Never long-press;
 *  - swipes are classified ONCE per finger-up on the dominant axis, threshold max(48, 0.09·width);
 *    horizontal raw dx is inverted vs the physical gesture, so the semantics are FORWARD/BACK.
 * Every game call is marshalled onto the GL thread through [post].
 */
class Gestures(private val widthPx: () -> Int, private val post: (Runnable) -> Unit, private val sink: Sink) {
    interface Sink {
        fun tap(); fun doubleTap(); fun tripleTap(); fun swipe(dir: Swipe)
    }
    companion object {
        const val TAG = "TapFractal"
        const val BACK_DEFER_MS = 350L
        /** A single tap fires this long after its release unless a second tap arrives first. */
        const val TAP_DEFER_MS = 300L
        /** Two taps this far apart are one double-tap. */
        const val PAIR_MIN_MS = 40L; const val PAIR_MAX_MS = 320L
        /** A KEY held this long (or longer) is the system's long-press: its release is not a tap. */
        const val SYSTEM_HOLD_MS = 450L
        /** A KEYCODE_BACK arriving this soon after a tap pair was handled is the system's echo of that pair. */
        const val BACK_ECHO_MS = 600L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastTapMs = -1000L
    private var tapPending: Runnable? = null
    private var backPending: Runnable? = null
    /** True once both clicks of the current double-tap have been seen (a further tap is then a triple-tap). */
    private var pairSeen = false
    private var pairHandledMs = -1000L
    private var keyDownAt = 0L; private var keyHeld = false
    private var downX = 0f; private var downY = 0f; private var downT = 0L; private var touching = false

    fun onKey(event: KeyEvent): Boolean {
        val isTap = event.keyCode == KeyEvent.KEYCODE_BUTTON_A || event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_SPACE
        if (isTap) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) { keyDownAt = SystemClock.uptimeMillis(); keyHeld = event.isLongPress } else keyHeld = true
                }
                KeyEvent.ACTION_UP -> {
                    val now = SystemClock.uptimeMillis()
                    val heldMs = max(event.eventTime - event.downTime, if (keyDownAt > 0L) now - keyDownAt else 0L)
                    val held = keyHeld || event.isCanceled || heldMs >= SYSTEM_HOLD_MS
                    keyDownAt = 0L; keyHeld = false
                    if (held) Log.d(TAG, "key release after ${heldMs} ms hold ignored") else tap(now)
                }
            }
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) back(SystemClock.uptimeMillis())
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val dir = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> Swipe.UP
                KeyEvent.KEYCODE_DPAD_DOWN -> Swipe.DOWN
                KeyEvent.KEYCODE_DPAD_LEFT -> Swipe.BACK
                KeyEvent.KEYCODE_DPAD_RIGHT -> Swipe.FORWARD
                else -> null
            }
            if (dir != null) { post { sink.swipe(dir) }; return true }
        }
        return false
    }

    fun onTouch(ev: MotionEvent): Boolean {
        val name = ev.device?.name ?: ""
        if (name.contains("cyttsp6", ignoreCase = true)) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; downT = SystemClock.uptimeMillis(); touching = true }
            MotionEvent.ACTION_MOVE -> if (!touching) { downX = ev.x; downY = ev.y; downT = SystemClock.uptimeMillis(); touching = true }
            MotionEvent.ACTION_UP -> {
                if (!touching) return true
                touching = false
                val dx = ev.x - downX; val dy = ev.y - downY
                val dist = hypot(dx, dy)
                val threshold = max(48f, 0.09f * widthPx())
                val now = SystemClock.uptimeMillis()
                if (dist >= threshold) {
                    val dir = if (abs(dx) >= abs(dy)) { if (dx < 0) Swipe.FORWARD else Swipe.BACK } else { if (dy < 0) Swipe.UP else Swipe.DOWN }
                    Log.d(TAG, "swipe raw dx=%.0f dy=%.0f → %s".format(dx, dy, dir))
                    post { sink.swipe(dir) }
                } else if (now - downT <= 320L) tap(now)
            }
            MotionEvent.ACTION_CANCEL -> touching = false
        }
        return true
    }

    /** One physical tap (KEY release or short touch), already filtered for holds. */
    private fun tap(now: Long) {
        if (now - lastTapMs < 60L) return          // KEY + touch of the same physical tap
        val gap = now - lastTapMs
        lastTapMs = now
        val back = backPending
        if (back != null) {
            if (!pairSeen && gap <= PAIR_MAX_MS) { pairSeen = true; return }   // click 2 delivered after the system's BACK
            handler.removeCallbacks(back); backPending = null; pairSeen = false   // third tap inside the window → triple-tap
            post { sink.tripleTap() }
            return
        }
        val single = tapPending
        if (single != null && gap in PAIR_MIN_MS..PAIR_MAX_MS) {
            handler.removeCallbacks(single); tapPending = null                    // the pair IS the double-tap
            pairSeen = true; pairHandledMs = now
            deferDouble()
            return
        }
        single?.let { handler.removeCallbacks(it) }
        val r = Runnable { tapPending = null; post { sink.tap() } }
        tapPending = r
        handler.postDelayed(r, TAP_DEFER_MS)
    }

    /** The system's double-tap (KEYCODE_BACK). */
    private fun back(now: Long) {
        if (backPending != null) return                                          // already deferred (from the pair, or a duplicate)
        if (now - pairHandledMs < BACK_ECHO_MS) return                           // echo of a pair whose double-tap already fired
        val single = tapPending
        if (single != null) {
            handler.removeCallbacks(single); tapPending = null                    // only click 1 was seen: click 2's release may still arrive
            pairSeen = false
        } else pairSeen = true                                                   // lone BACK (injected, or clicks not delivered as keys)
        deferDouble()
    }

    private fun deferDouble() {
        val r = Runnable { backPending = null; pairSeen = false; post { sink.doubleTap() } }
        backPending = r
        handler.postDelayed(r, BACK_DEFER_MS)
    }
}
