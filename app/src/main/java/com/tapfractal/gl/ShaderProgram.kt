package com.tapfractal.gl

import android.content.res.AssetManager
import android.opengl.GLES30
import android.util.Log

/**
 * GLSL ES 3.00 program wrapper. Scene programs are `common.glsl + sceneN.frag + footer.glsl`;
 * compile/link failures are logged in full with line numbers (tag TapFractal) and the caller
 * falls back to the built-in error scene so the app never black-screens.
 */
class ShaderProgram private constructor(val id: Int, val label: String) {
    private val locs = HashMap<String, Int>()

    fun use() { GLES30.glUseProgram(id) }

    fun loc(name: String): Int = locs.getOrPut(name) { GLES30.glGetUniformLocation(id, name) }

    fun set1f(name: String, v: Float) { val l = loc(name); if (l >= 0) GLES30.glUniform1f(l, v) }
    fun set1i(name: String, v: Int) { val l = loc(name); if (l >= 0) GLES30.glUniform1i(l, v) }
    fun set2f(name: String, x: Float, y: Float) { val l = loc(name); if (l >= 0) GLES30.glUniform2f(l, x, y) }
    fun set3f(name: String, x: Float, y: Float, z: Float) { val l = loc(name); if (l >= 0) GLES30.glUniform3f(l, x, y, z) }
    fun set4f(name: String, x: Float, y: Float, z: Float, w: Float) { val l = loc(name); if (l >= 0) GLES30.glUniform4f(l, x, y, z, w) }
    fun set4fv(name: String, count: Int, v: FloatArray) { val l = loc(name); if (l >= 0) GLES30.glUniform4fv(l, count, v, 0) }
    fun set1fv(name: String, count: Int, v: FloatArray) { val l = loc(name); if (l >= 0) GLES30.glUniform1fv(l, count, v, 0) }
    fun setMat3(name: String, m: FloatArray) { val l = loc(name); if (l >= 0) GLES30.glUniformMatrix3fv(l, 1, false, m, 0) }
    fun setTex(name: String, unit: Int, tex: Int) {
        val l = loc(name); if (l < 0) return
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glUniform1i(l, unit)
    }

    fun release() { if (id != 0) GLES30.glDeleteProgram(id) }

    companion object {
        const val TAG = "TapFractal"

        fun readAsset(am: AssetManager, path: String): String? = try {
            am.open(path).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "asset missing: $path (${e.message})"); null
        }

        /** Build a scene program from the three concatenated assets; null on failure (logged). */
        fun scene(am: AssetManager, sceneAsset: String, common: String, footer: String, vert: String): ShaderProgram? {
            val body = readAsset(am, sceneAsset) ?: return null
            val src = common + "\n#line 1 1\n" + body + "\n#line 1 2\n" + footer
            return build(vert, src, sceneAsset)
        }

        fun build(vertSrc: String, fragSrc: String, label: String): ShaderProgram? {
            val vs = compile(GLES30.GL_VERTEX_SHADER, vertSrc, "$label.vert") ?: return null
            val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragSrc, label)
            if (fs == null) { GLES30.glDeleteShader(vs); return null }
            val prog = GLES30.glCreateProgram()
            GLES30.glAttachShader(prog, vs)
            GLES30.glAttachShader(prog, fs)
            GLES30.glLinkProgram(prog)
            val ok = IntArray(1)
            GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, ok, 0)
            GLES30.glDeleteShader(vs); GLES30.glDeleteShader(fs)
            if (ok[0] == 0) {
                Log.e(TAG, "LINK FAILED [$label]:\n" + GLES30.glGetProgramInfoLog(prog))
                GLES30.glDeleteProgram(prog)
                return null
            }
            Log.i(TAG, "shader ok: $label (program $prog)")
            return ShaderProgram(prog, label)
        }

        private fun compile(type: Int, src: String, label: String): Int? {
            val sh = GLES30.glCreateShader(type)
            GLES30.glShaderSource(sh, src)
            GLES30.glCompileShader(sh)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(sh, GLES30.GL_COMPILE_STATUS, ok, 0)
            val log = GLES30.glGetShaderInfoLog(sh)
            if (ok[0] == 0) {
                Log.e(TAG, "COMPILE FAILED [$label]:\n$log")
                // dump the numbered source so a line number in the log can be found
                val sb = StringBuilder()
                src.lines().forEachIndexed { i, line -> sb.append(String.format("%4d| %s\n", i + 1, line)) }
                sb.lines().chunked(200).forEach { Log.e(TAG, it.joinToString("\n")) }
                GLES30.glDeleteShader(sh)
                return null
            } else if (log.isNotBlank()) {
                Log.w(TAG, "shader warnings [$label]:\n$log")
            }
            return sh
        }

        /** Built-in fallback scene: a slow magenta/cyan plasma with the toys, so a broken scene file is obvious but not black. */
        const val ERROR_SCENE = """
// built-in error scene
vec3 render(vec2 uv) {
    float t = uTime * 0.4;
    float v = sin(uv.x * 9.0 + t) + sin(uv.y * 7.0 - t * 1.3) + sin(length(uv) * 12.0 - t * 2.0);
    vec3 col = pal(v * 0.15 + uHue, vec3(0.5, 0.3, 0.6), vec3(0.5, 0.3, 0.4), vec3(1.0, 1.0, 2.0), vec3(0.0, 0.5, 0.5)) * 0.6;
    col *= 1.0 - 0.3 * smoothstep(0.5, 1.2, length(uv));
    gDepth = -1.0;
    if (uIs2D == 1) return toys2D(uv, col) + toysEdge(uv);
    // raymarch only the toys so the sluurp/rope/targets still show
    vec3 ro = uCamPos, rd = rayDir(uv); float tt = 0.0; int id = 0;
    for (int i = 0; i < 48; i++) { float d = toysDE(ro + rd * tt, id); if (d < 0.001 * tt + 0.0005) { gDepth = tt; vec3 p = ro + rd * tt;
        vec3 n = normalize(vec3(toysDE(p + vec3(0.001, 0, 0), id) - toysDE(p - vec3(0.001, 0, 0), id), toysDE(p + vec3(0, 0.001, 0), id) - toysDE(p - vec3(0, 0.001, 0), id), toysDE(p + vec3(0, 0, 0.001), id) - toysDE(p - vec3(0, 0, 0.001), id)));
        return toyColor(id, p, n) + toysEdge(uv); } tt += d; if (tt > 12.0) break; }
    return col + toysEdge(uv);
}
"""
    }
}
