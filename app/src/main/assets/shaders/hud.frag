#version 300 es
precision mediump float;
// Core-owned HUD composite: the 640×480 Canvas texture (premultiplied alpha) over each eye at zero disparity.
uniform sampler2D uHud;
uniform vec2 uScreenOrigin, uScreenRes;
uniform float uHudAlpha;
out vec4 fragColor;
void main() {
    vec2 t = (gl_FragCoord.xy - uScreenOrigin) / uScreenRes;
    vec4 c = texture(uHud, vec2(t.x, 1.0 - t.y));
    fragColor = c * uHudAlpha;
}
