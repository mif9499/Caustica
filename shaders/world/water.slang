// Water surface: 3D gradient-noise wave field. Perlin-style gradient noise (a
// random gradient per lattice cell) is naturally directional — it reads as
// wind-driven waves rather than blobs or grid lines. Sampling a 3D lattice on
// the 2D water surface (the third axis is time) keeps the pattern organic at
// every scale: the slice cuts the cubic lattice at a continuously varying
// angle, so no grid line or repeating motif ever coheres — 2D lattices (square
// or triangular) always show their cell structure, which is why the earlier 2D
// attempts read as seam lines or repeating bumps.
//
// Cost notes (why this stays close to the old sine spectrum):
//  * Gradients are analytic (gradNoise3DEx): one lattice walk returns both the
//    height and the exact gradient — no finite-difference probe, no eps tuning.
//  * Per-octave drift directions are a precomputed constant table, so the walk
//    costs no per-octave trigonometry.
//  * The temporal pair rewalks only the coarse octaves for the previous frame
//    (fine chop barely contributes to the reprojection vector).
//  * Octaves fade in via waterWaveLodWeight instead of a hard break, and the
//    caustic landing path culls sub-probe detail.
//
// Height gain = roughness^i; gradient gain = (roughness/lacunarity)^i so the
// gradient sum converges — no "ringing" when detail is turned up.
//
// All public interfaces (applyWaterWaves, waterCaustic, waterWaveGrad, etc.)
// are unchanged so no call-sites in the path tracer or guide pass needed
// modification.

import world_common;
import world_core;

// ---- 3D hashing -------------------------------------------------------------
// Fast lattice-point hash → [0, 1). No trig: only fract + dot products.
// Used to seed gradient vectors; the distribution at integer-lattice inputs is
// well-scrambled enough for gradient-noise interpolation.

float hash3D(float3 p) {
    float3 q = fract(p * 0.3183099f + float3(0.1f, 0.2f, 0.3f));
    q += dot(q, q.yzx + 19.19f);
    return fract(q.x * q.y * q.z * 73.157f);
}

// Map a lattice point to a pseudo-random 3D gradient vector (unnormalised,
// roughly uniform over [−1, 1]³). One hash3D call + three scaled fracts.

float3 hashGradient3D(float3 lattice) {
    float h = hash3D(lattice);
    return float3(
        fract(h * 127.1f) * 2.0f - 1.0f,
        fract(h * 269.5f) * 2.0f - 1.0f,
        fract(h * 311.7f) * 2.0f - 1.0f
    );
}

// ---- Domain rotation ---------------------------------------------------------
// Two 2D rotations applied in sequence (xy then xz) so no lattice plane aligns
// with the sampling plane — the property that keeps 2D slices of the 3D field
// organic at every scale. The angles are irrational-ish (~49° and ~65°) so no
// alignment survives.

static const float2 ROT_A = float2(0.754877666f, 0.655866346f);
static const float2 ROT_B = float2(0.909297427f, 0.416146837f);

float3 rotateDomain(float3 p) {
    float rx = p.x * ROT_A.y - p.y * ROT_A.x;
    float ry = p.x * ROT_A.x + p.y * ROT_A.y;
    float rz = rx * ROT_B.y - p.z * ROT_B.x;
    float rx2 = rx * ROT_B.x + p.z * ROT_B.y;
    return float3(rx2, ry, rz);
}

// ---- 3D gradient noise (Perlin-style) with analytic gradient -----------------
// Each lattice corner contributes dot(gradient, offset) instead of a scalar
// hash value. Quintic-smoothstep interpolation (C² continuous) makes the
// analytic gradient clean and grid-artefact-free. The gradient is the chain
// rule of the trilinear interpolation:
//
//   ∂n/∂f.i = du.i · ∂(trilinear)/∂u.i + Σ_k w_k · g_k.i
//
// du = 30·f²·(f−1)² is the quintic's derivative. Since f = r − floor(r),
// ∂/∂f = ∂/∂r in the rotated domain, and the R^T back-rotation below maps the
// gradient back to input/coord space so slopes keep their world orientation.

void gradNoise3DEx(float3 p, out float v, out float3 grad) {
    float3 r = rotateDomain(p);
    float3 i = floor(r);
    float3 f = fract(r);
    float3 u = f * f * f * (f * (f * 6.0f - 15.0f) + 10.0f);
    float3 du = 30.0f * f * f * (f - 1.0f) * (f - 1.0f);

    float3 g000 = hashGradient3D(i + float3(0.0f, 0.0f, 0.0f));
    float3 g100 = hashGradient3D(i + float3(1.0f, 0.0f, 0.0f));
    float3 g010 = hashGradient3D(i + float3(0.0f, 1.0f, 0.0f));
    float3 g110 = hashGradient3D(i + float3(1.0f, 1.0f, 0.0f));
    float3 g001 = hashGradient3D(i + float3(0.0f, 0.0f, 1.0f));
    float3 g101 = hashGradient3D(i + float3(1.0f, 0.0f, 1.0f));
    float3 g011 = hashGradient3D(i + float3(0.0f, 1.0f, 1.0f));
    float3 g111 = hashGradient3D(i + float3(1.0f, 1.0f, 1.0f));

    float v000 = dot(g000, f - float3(0.0f, 0.0f, 0.0f));
    float v100 = dot(g100, f - float3(1.0f, 0.0f, 0.0f));
    float v010 = dot(g010, f - float3(0.0f, 1.0f, 0.0f));
    float v110 = dot(g110, f - float3(1.0f, 1.0f, 0.0f));
    float v001 = dot(g001, f - float3(0.0f, 0.0f, 1.0f));
    float v101 = dot(g101, f - float3(1.0f, 0.0f, 1.0f));
    float v011 = dot(g011, f - float3(0.0f, 1.0f, 1.0f));
    float v111 = dot(g111, f - float3(1.0f, 1.0f, 1.0f));

    float n00 = lerp(v000, v100, u.x);
    float n10 = lerp(v010, v110, u.x);
    float n01 = lerp(v001, v101, u.x);
    float n11 = lerp(v011, v111, u.x);
    v = lerp(lerp(n00, n10, u.y), lerp(n01, n11, u.y), u.z);

    // Weight-derivative terms (du.i · ∂(trilinear)/∂u.i).
    float gx = lerp(lerp(v100 - v000, v110 - v010, u.y),
                    lerp(v101 - v001, v111 - v011, u.y), u.z) * du.x;
    float gy = lerp(lerp(v010 - v000, v110 - v100, u.x),
                    lerp(v011 - v001, v111 - v101, u.x), u.z) * du.y;
    float gz = lerp(lerp(v001 - v000, v101 - v100, u.x),
                    lerp(v011 - v010, v111 - v110, u.x), u.y) * du.z;

    // Weighted average of the corner gradients (Σ w_k · g_k).
    float gx0 = lerp(lerp(g000.x, g100.x, u.x), lerp(g010.x, g110.x, u.x), u.y);
    float gx1 = lerp(lerp(g001.x, g101.x, u.x), lerp(g011.x, g111.x, u.x), u.y);
    float gy0 = lerp(lerp(g000.y, g100.y, u.x), lerp(g010.y, g110.y, u.x), u.y);
    float gy1 = lerp(lerp(g001.y, g101.y, u.x), lerp(g011.y, g111.y, u.x), u.y);
    float gz0 = lerp(lerp(g000.z, g100.z, u.x), lerp(g010.z, g110.z, u.x), u.y);
    float gz1 = lerp(lerp(g001.z, g101.z, u.x), lerp(g011.z, g111.z, u.x), u.y);
    float3 gr = float3(gx + lerp(gx0, gx1, u.z),
                       gy + lerp(gy0, gy1, u.z),
                       gz + lerp(gz0, gz1, u.z));

    // Undo the domain rotation (r = R·p ⇒ grad_p = R^T · grad_r).
    grad = float3(gr.x * (ROT_A.y * ROT_B.x) + gr.y * ROT_A.x + gr.z * (ROT_A.y * ROT_B.y),
                  gr.x * (-ROT_A.x * ROT_B.x) + gr.y * ROT_A.y + gr.z * (-ROT_A.x * ROT_B.y),
                  gr.x * ROT_B.y - gr.z * ROT_B.x);
}

// ---- LOD utility --------------------------------------------------------------

public float waterWaveLodWeight(float wavelength, float footprint) {
    float cyclesPerPixel = max(footprint, 0.0f) / max(wavelength, 1.0e-4f);
    return 1.0f - smoothstep(0.25f, 0.5f, cyclesPerPixel);
}

// ---- FBM wave field ------------------------------------------------------------

// Per-octave drift directions (unit), precomputed: driftDir_i = R(0.17·i +
// 0.03·i²) · NOISE_WIND, NOISE_WIND = normalize((1, 0.35)). A constant table
// keeps the walk free of per-octave trigonometry.
static const float2 DRIFT_DIR[10] = {
    float2(0.943858356f, 0.330350425f),
    float2(0.859413531f, 0.511281118f),
    float2(0.699088192f, 0.715035454f),
    float2(0.438673029f, 0.898646746f),
    float2(0.074053655f, 0.997254259f),
    float2(-0.357769777f, 0.933809823f),
    float2(-0.761664795f, 0.647971249f),
    float2(-0.989517775f, 0.144411126f),
    float2(-0.889255141f, -0.457411515f),
    float2(-0.403843368f, -0.914828145f)
};

// Core walk: height + analytic gradient from one lattice evaluation per octave.
// Each octave drifts with the wind in xz while the noise's z axis is time —
// genuine "heaving" motion. `octaveCap` bounds the walk (the temporal pair uses
// a coarse previous-time walk; see waterWaveEvalTemporal).
void waterWaveEvalCapped(float2 p, float t, float footprint, int octaveCap,
                         out float height, out float2 grad) {
    float scale       = worldPush.waterWaveConfig2.x;
    float lacunarity  = worldPush.waterWaveConfig2.y;
    float speed       = worldPush.waterWaveConfig.y;
    int   octaves     = int(clamp(worldPush.waterWaveConfig.z, 1.0f, 10.0f));
    float roughness   = worldPush.waterWaveConfig.w;

    float h = 0.0f;
    float2 gradSum = float2(0.0f, 0.0f);
    float ampH = 1.0f;             // height gain: roughness^i
    float ampG = 1.0f;             // gradient gain: (roughness/lacunarity)^i
    float gainGrad = roughness / lacunarity;
    float freq = 1.0f;
    float maxVal = 0.0f;

    for (int i = 0; i < min(octaves, octaveCap); i++) {
        float wavelength = scale / freq;
        // Smooth LOD fade: a hard inclusion break would make octaves pop at the
        // footprint threshold, showing as banding.
        float lodWeight = waterWaveLodWeight(wavelength, footprint);
        if (lodWeight <= 0.0f) break;

        float2 driftDir = DRIFT_DIR[i];
        float driftSpeed = speed * (1.0f - 0.08f * float(i));

        float3 coord = float3(
            (p + driftDir * t * driftSpeed) * freq / scale,
            t * speed * (0.5f + 0.15f * float(i))
        );

        float v;
        float3 g;
        gradNoise3DEx(coord, v, g);

        h += ampH * v * lodWeight;
        gradSum += ampG * g.xz * (freq / scale) * lodWeight;
        maxVal += ampH * lodWeight;
        ampH *= roughness;
        ampG *= gainGrad;
        freq *= lacunarity;
    }

    height = h / max(maxVal, 1.0e-6f);
    grad = gradSum * worldPush.waterWaveConfig.x;
}

public void waterWaveEval(float2 p, float t, float footprint,
                          out float height, out float2 grad) {
    waterWaveEvalCapped(p, t, footprint, 10, height, grad);
}

// Standalone height query — kept for call sites that only need the height
// (the unified walk above stays the hot path for everything else).
public float waterNoiseHeight(float2 p, float t, float footprint) {
    float h;
    float2 g;
    waterWaveEval(p, t, footprint, h, g);
    return h;
}

// ---- Gradient ----------------------------------------------------------------
// Analytic gradient from the shared walk (waterWaveEval) — exact per-octave
// derivatives. Gradient gain = (roughness / lacunarity)^i so the sum converges;
// without it the natural freq^i amplification of gradients makes every octave
// equally loud when roughness × lacunarity ≈ 1, creating a noisy mess.

public float2 waterWaveGrad(float2 p, float t, float footprint) {
    float h;
    float2 g;
    waterWaveEval(p, t, footprint, h, g);
    return g;
}

public float2 waterWaveGrad(float2 p, float t) {
    return waterWaveGrad(p, t, 0.0f);
}

// Temporal pair: evaluate height+gradient at two closely-spaced wave times for
// specular motion-vector reprojection. The previous time is walked with only
// the coarse octaves (cap 3): the reprojection vector is dominated by the
// swell, whose gradient gain (roughness/lacunarity)^i is already ~0.03 by
// octave 3 — a full previous walk would cost ~40% more for an imperceptible
// change in the motion vector.

public void waterWaveEvalTemporal(float2 p, float currentT, float previousT, float footprint,
                                  out float2 currentGrad, out float2 previousGrad,
                                  out float height) {
    waterWaveEval(p, currentT, footprint, height, currentGrad);
    float prevHeight;
    waterWaveEvalCapped(p, previousT, footprint, 3, prevHeight, previousGrad);
}

public void waterWaveGradTemporal(float2 p, float currentT, float previousT, float footprint,
                           out float2 currentGrad, out float2 previousGrad) {
    float h;
    waterWaveEvalTemporal(p, currentT, previousT, footprint, currentGrad, previousGrad, h);
}

// ---- Normal perturbation (same interface as the original spectrum) -------------
// Convert a wave gradient to a surface normal, clamped so the normal never tilts
// past a safe angle from vertical. Without this, strong / fine-scale waves can
// make the surface nearly horizontal — Fresnel → 1 from above (black mirror),
// total internal reflection from below (can't see the sky).
//
// MAX_GRAD_LEN_SQ = 3.0 → ny ≥ 0.5 → normal stays within 60° of vertical.
// Real ocean waves rarely exceed 30°; 60° is a generous safety limit.
// The horizon-clip in the callers (see world_primary.rgen, world.rgen) prevents
// the perturbed normal from tilting past the viewer-facing hemisphere — the true
// source of the "normal flip" artefact.

static const float MAX_GRAD_LEN_SQ = 3.0f;

public float3 waveNormal(float2 grad) {
    float gradLenSq = dot(grad, grad);
    if (gradLenSq > MAX_GRAD_LEN_SQ) {
        grad *= sqrt(MAX_GRAD_LEN_SQ / gradLenSq);
    }
    return normalize(float3(-grad.x, 1.0f, -grad.y));
}

// Perturb a (near-)horizontal water surface normal by the wave field. Flowing
// side faces (|n.y| small) keep their flat geometric normal. `worldXZ` must be
// a world-stable coordinate.
//
// Crest-shaping: raw noise produces uniform steepness everywhere — unlike real
// waves which are steep only at crests. We evaluate the total noise height and
// attenuate the gradient in troughs via exp(S·(h−1)), the same shaping function
// the original sine-wave spectrum used. This concentrates normal perturbation at
// wave peaks so the surface reads as natural water rather than uniform noise.
public float3 applyWaterWaves(float3 nGeo, float2 worldXZ, float t, float footprint) {
    if (abs(nGeo.y) < 0.5f) return nGeo;
    float2 grad;
    float height;
    waterWaveEval(worldXZ, t, footprint, height, grad);
    // Map crest-sharpness config (0.3–0.8) to exp steepness S (1.5–4.0).
    // At crest (height≈1): exp(0)=1 → full gradient.
    // At trough (height≈-1): exp(−2S) → gradient severely reduced.
    float S = lerp(1.5, 4.0, (worldPush.waterWaveConfig.w - 0.3) / 0.5);
    float crest = saturate(exp(S * (height - 1.0)));
    grad *= lerp(worldPush.waterWaveConfig3.x, 1.0, crest);
    float3 up = waveNormal(grad);
    return nGeo.y >= 0.0f ? up : -up;
}

// ---- Caustics ------------------------------------------------------------------
// Sunlight refracting through the waved surface converges/diverges before it
// reaches an underwater receiver; the analytic wave field makes the true
// focusing factor computable instead of faked with a scrolling texture. Where
// the shadow ray of an underwater NEE vertex crossed water (returned by
// visibility() as VisibilityResult.waterHitT), evaluate the horizontal landing
// position of the refracted sun ray as a function of surface position and
// finite-difference it. The caustic intensity is the inverse Jacobian
// determinant of that surface→floor mapping (area compression = brightening,
// real fold caustics where det → 0).

public static const float CAUSTIC_EPS = 0.25f; // finite-difference step (m); also low-passes sub-step wave detail
public static const float CAUSTIC_MAX = 6.0f;  // focusing clamp: keeps fold-line singularities from becoming fireflies

// Horizontal landing offset of a refracted sun ray entering the surface at
// world-stable xz, intersected with the receiver plane h metres below.
// inc = sunlight travel direction (downward, unit).
public float2 causticLanding(float2 xz, float t, float3 inc, float h) {
    float2 g;
    float height;
    // Caustic octave budget: a fixed 10% of the base scale. The smooth LOD culls
    // octaves finer than 2× the footprint, so caustics keep only the coarsest ~2
    // octaves at lacunarity 2.5 (≈4 at 1.5), independent of scale — the surface
    // path may use more, but finer caustic detail is smeared by the CAUSTIC_EPS
    // landing finite difference and blurred by refraction depth anyway, so it
    // only adds cost here.
    float causticFootprint = max(worldPush.waterWaveConfig2.x * 0.1f, CAUSTIC_EPS);
    waterWaveEval(xz, t, causticFootprint, height, g);
    // Same crest-shaping as applyWaterWaves (see above) — concentrates gradient
    // at wave peaks so caustics inherit the sine-wave-like fold pattern.
    float S = lerp(1.5, 4.0, (worldPush.waterWaveConfig.w - 0.3) / 0.5);
    float crest = saturate(exp(S * (height - 1.0)));
    g *= lerp(worldPush.waterWaveConfig3.x, 1.0, crest);
    float3 nw = waveNormal(g);
    float3 rdw = refract(inc, nw, 1.0f / WATER_IOR);
    return xz + rdw.xz * (h / max(-rdw.y, 0.05f));
}

public float waterCaustic(float3 exitPos, float3 lightDir, float waterDist) {
    float fade = smoothstep(0.06f, 0.18f, lightDir.y);
    if (fade <= 0.0f) return 1.0f;
    float h = max(waterDist * lightDir.y, 0.05f);
    float t = worldPush.waterParams.w;
    float3 inc = -lightDir;
    float2 base = exitPos.xz + worldPush.waterAnchor.xy;
    float2 p0 = causticLanding(base, t, inc, h);
    float2 px = causticLanding(base + float2(CAUSTIC_EPS, 0.0f), t, inc, h);
    float2 pz = causticLanding(base + float2(0.0f, CAUSTIC_EPS), t, inc, h);
    float det = abs((px.x - p0.x) * (pz.y - p0.y) - (px.y - p0.y) * (pz.x - p0.x));
    float focus = (CAUSTIC_EPS * CAUSTIC_EPS) / max(det, 1.0e-5f);
    return lerp(1.0f, min(focus, CAUSTIC_MAX), fade);
}
