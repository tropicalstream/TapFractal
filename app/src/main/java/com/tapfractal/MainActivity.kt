package com.tapfractal

import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.tapfractal.audio.Music
import com.tapfractal.audio.Sfx
import com.tapfractal.engine.Game
import com.tapfractal.engine.Swipe
import com.tapfractal.gl.GLRenderer
import com.tapfractal.head.HeadTracker
import com.tapfractal.input.Gestures

/**
 * TapFractal — fullscreen GLSurfaceView (EGL 3), immersive, screen kept on. All input is classified
 * on the main thread by [Gestures] and marshalled onto the GL thread via queueEvent.
 */
class MainActivity : Activity(), Gestures.Sink {
    companion object { const val TAG = "TapFractal" }

    private lateinit var glView: GLSurfaceView
    private lateinit var store: SettingsStore
    private lateinit var head: HeadTracker
    private lateinit var music: Music
    private lateinit var sfx: Sfx
    private lateinit var game: Game
    private lateinit var renderer: GLRenderer
    private lateinit var gestures: Gestures

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        head = HeadTracker(this)
        music = Music(assets)
        sfx = Sfx()
        game = Game(store, head, music, sfx)
        renderer = GLRenderer(assets, game, head, contentResolver, java.io.File(filesDir, "gpu-calib.txt"))
        gestures = Gestures({ glView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels }, { r -> glView.queueEvent(r) }, this)
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 0, 0)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            // frames are requested from the Choreographer (every vsync, or every other one at 30 fps pacing)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }
        setContentView(glView)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        Log.i(TAG, "display rotation=${display?.rotation} model=${Build.MODEL} product=${Build.PRODUCT}")
    }

    // ---- Gestures.Sink (already on the GL thread) ----
    override fun tap() = game.onTap()
    override fun doubleTap() = game.onDoubleTap()
    override fun tripleTap() = game.onTripleTap()
    override fun swipe(dir: Swipe) = game.onSwipe(dir)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (gestures.onKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean = gestures.onTouch(ev)

    /**
     * Frame pacing (CORE_API §10.1): one requestRender on EVERY vsync. The renderer decides per room
     * whether a vsync renders the whole scene (60 fps) or one of two slices of it (30 fps scene rate)
     * — the post pass presents every vsync either way, through the timewarp. Requests that arrive
     * while a frame is still in flight coalesce.
     */
    private val vsync = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            glView.requestRender()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        glView.onResume()
        Choreographer.getInstance().removeFrameCallback(vsync)
        Choreographer.getInstance().postFrameCallback(vsync)
        head.enabled = store.headTracking
        if (store.headTracking) head.start()
        sfx.start()
        glView.queueEvent { game.onResume() }
    }

    override fun onPause() {
        Choreographer.getInstance().removeFrameCallback(vsync)
        glView.queueEvent { game.onPause() }
        glView.onPause()
        head.stop()
        sfx.stop()
        super.onPause()
    }

    override fun onDestroy() {
        music.release()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { c ->
                c.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
        }
    }
}
