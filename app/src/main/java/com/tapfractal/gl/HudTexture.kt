package com.tapfractal.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.opengl.GLES30
import android.opengl.GLUtils

/**
 * 640×480 RGBA HUD surface: Android Canvas → Bitmap → GL texture, re-uploaded only when dirty,
 * composited last over each eye at zero disparity (DESIGN §10). Two textures ping-pong so an
 * upload never stalls on the texture the previous frame is still reading.
 */
class HudTexture(val width: Int = 640, val height: Int = 480) {
    val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas: Canvas = Canvas(bitmap)
    private val tex = IntArray(2)
    private var cur = 0
    private var created = false
    var dirty = true

    fun createGL() {
        GLES30.glGenTextures(2, tex, 0)
        for (t in tex) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        created = true
        dirty = true
    }

    /** Upload the bitmap if dirty; returns the texture to sample this frame. */
    fun upload(): Int {
        if (!created) createGL()
        if (dirty) {
            cur = 1 - cur
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[cur])
            GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, bitmap)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            dirty = false
        }
        return tex[cur]
    }

    fun release() { if (created) { GLES30.glDeleteTextures(2, tex, 0); created = false } }
}
