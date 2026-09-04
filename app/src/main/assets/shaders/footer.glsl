
// =====================================================================================
// footer.glsl (core-owned): writes colour (× uFade) and the depth attachment.
// =====================================================================================
layout(location = 0) out vec4 fragColor;
layout(location = 1) out float outDepth;
void main() {
    vec3 c = render(eyeUV());
    fragColor = vec4(max(c, 0.0) * uFade, 1.0);
    outDepth = gDepth;
}
