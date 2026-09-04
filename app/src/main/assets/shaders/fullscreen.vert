#version 300 es
// Core-owned fullscreen triangle (no vertex buffers): draw 3 vertices with glDrawArrays(GL_TRIANGLES, 0, 3).
void main() {
    vec2 v = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    gl_Position = vec4(v * 2.0 - 1.0, 0.0, 1.0);
}
