// Core-owned echo compose pass (common.glsl prepended, footer.glsl appended; uFade = 1).
// Renders into the NEXT echo buffer at the current eye rect:
//   hdr = scene + uEchoMix · min(prevEcho(reprojected, zoomed, rotated), 2) · uEchoTint
// The previous echo is world-locked by reprojectPrev() (head rotation exact).
uniform sampler2D uSceneTex;     // this frame's scene colour (whole FBO)
uniform sampler2D uPrevEcho;     // last frame's echo buffer (whole FBO, prev eye rect)
uniform float uEchoMix, uEchoZoom, uEchoRot;
uniform vec2  uEchoCentre;
uniform vec3  uEchoTint;

vec3 render(vec2 uv) {
    vec3 scene = texelFetch(uSceneTex, ivec2(gl_FragCoord.xy), 0).rgb;
    vec2 g = uv - vec2(uUvShift, 0.0);
    vec2 c = uEchoCentre;
    vec2 gz = c + rot2(uEchoRot) * (g - c) / uEchoZoom;
    vec2 gp = reprojectPrev(gz);
    vec3 prev = vec3(0.0);
    float prevAspect = uPrevEyeRes.x / max(uPrevEyeRes.y, 1.0);
    if (abs(gp.x) <= 0.5 * prevAspect && abs(gp.y) <= 0.5) {
        vec2 px = gp * uPrevEyeRes.y + 0.5 * uPrevEyeRes + uPrevEyeOrigin;
        vec2 lo = uPrevEyeOrigin + 0.5, hi = uPrevEyeOrigin + uPrevEyeRes - 0.5;
        vec2 ts = vec2(textureSize(uPrevEcho, 0));
        // 3×3 tent (four bilinear taps at ±½ px): the per-frame zoom resamples the buffer at a sub-pixel
        // phase that varies with radius; a single bilinear tap then alternates sharp/blurred rings
        // around the echo centre (moiré). The tent makes the response phase-independent.
        prev  = texture(uPrevEcho, clamp(px + vec2(-0.5, -0.5), lo, hi) / ts).rgb;
        prev += texture(uPrevEcho, clamp(px + vec2( 0.5, -0.5), lo, hi) / ts).rgb;
        prev += texture(uPrevEcho, clamp(px + vec2(-0.5,  0.5), lo, hi) / ts).rgb;
        prev += texture(uPrevEcho, clamp(px + vec2( 0.5,  0.5), lo, hi) / ts).rgb;
        prev *= 0.25;
    }
    prev = min(prev, 2.0) * uEchoTint;
    // Floor: near-black echo does not feed back. A dim radial haze (Nave) zoomed outward each frame
    // otherwise wins the decaying max against its own gradient and etches concentric rings into the
    // black; anything below ECHO_FLOOR luma (≈ sRGB 0.22) simply dies instead of lingering.
    const float ECHO_FLOOR = 0.04;
    float pl = dot(prev, vec3(0.2126, 0.7152, 0.0722));
    prev *= smoothstep(ECHO_FLOOR, 2.0 * ECHO_FLOOR, pl);
    gDepth = -1.0;
    // Decaying-max feedback: trails fade with τ but the buffer can never exceed its brightest source.
    // (A plain `scene + mix·prev` is an IIR accumulator that converges to scene/(1−mix) ≈ 7× — pale fields.)
    return max(scene, uEchoMix * prev);
}
