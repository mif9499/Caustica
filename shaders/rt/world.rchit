#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require

// P1 step 3: directional sky-light. Per-primitive face normal is fetched from a buffer-reference
// array (one vec4 per triangle, indexed by gl_PrimitiveID) and shaded with a fixed sun direction
// + ambient. Flat gray albedo still — textures (atlas-by-UV) come with the real mesher next.
layout(buffer_reference, std430, buffer_reference_align = 16) readonly buffer Normals {
    vec4 n[];
};

layout(push_constant) uniform Push {
    mat4 invViewProj;
    vec3 camOffset;
    uint64_t normalAddr;
} pc;

layout(location = 0) rayPayloadInEXT vec3 payload;
hitAttributeEXT vec2 attribs;

void main() {
    Normals normals = Normals(pc.normalAddr);
    vec3 n = normalize(normals.n[gl_PrimitiveID].xyz);

    const vec3 sunDir = normalize(vec3(0.35, 0.9, 0.25));
    float ndl = max(0.0, dot(n, sunDir));
    float ambient = 0.35;
    vec3 albedo = vec3(0.78);
    payload = albedo * (ambient + 0.75 * ndl);
}
