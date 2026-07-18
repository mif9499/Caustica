# Bloom Post-Processing

Bloom is a 3-level Gaussian pyramid post-effect inserted between DLSS-RR and auto-exposure in the render pipeline. It operates on the HDR `rrOutput` image in-place.

## Pipeline Flow

```
display-res HDR (rrOutput)
    │
    ├─ 1. THRESHOLD: bloom_threshold.comp
    │     HDR → mip0 (½ res). Soft-knee threshold extraction + 2×2 box downsample.
    │
    ├─ 2. GAUSSIAN H+V: bloom_blur.comp
    │     mip0 → ping → mip0. 9-tap separable Gaussian, sigma=3.0.
    │
    ├─ 3. DOWNSAMPLE: bloom_downsample.comp
    │     mip0 → mip1 (¼ res). 4×4 binomial [1,3,3,1]² filter.
    │
    ├─ 4. GAUSSIAN H+V: mip1 → ping → mip1
    │
    ├─ 5. DOWNSAMPLE: mip1 → mip2 (⅛ res)
    │
    ├─ 6. GAUSSIAN H+V: mip2 → ping → mip2
    │
    ├─ (Spread > 0: extra Gaussian iterations on mip1/mip2)
    │
    ├─ 7. UPSAMPLE: bloom_upsample.comp
    │     mip2 → mip1 (+= 1.0), 3×3 tent filtered upsample
    │
    ├─ 8. UPSAMPLE: mip1 → mip0 (+= 1.0)
    │
    └─ 9. FINAL COMPOSITE: bloom_upsample.comp
          mip0 → HDR (× intensity). 3×3 tent filtered upsample + add.
```

Total passes: 9 + spread × 2 (extra Gaussian H+V per spread level).
All images in `VK_IMAGE_LAYOUT_GENERAL`, memory barriers between passes.

## Shaders

| Shader | Function | Kernel | Bindings |
|--------|----------|--------|----------|
| `bloom_threshold.comp` | Threshold + 2×2 downsample | — | in: hdr(rgba16f), out: mip0(rgba16f) |
| `bloom_blur.comp` | Separable Gaussian H/V | 9-tap, σ=3.0 | in: src, out: dst, push: direction |
| `bloom_downsample.comp` | 4×4 binomial downsample | [1,3,3,1]² / 256 | in: src, out: dst |
| `bloom_upsample.comp` | 3×3 tent upsample + add | — | in: src(ro), dst(rw), push: intensity |

### Gaussian Weights (sigma=3.0, 9-tap)

```
[0.0477, 0.0835, 0.1246, 0.1584, 0.1716, 0.1584, 0.1246, 0.0835, 0.0477]
```

Cumulative sigma after N passes: σ_eff = 3.0 × √N.
2 passes ≈ σ 4.2, 3 passes ≈ σ 5.2.

## Resources

| Resource | Resolution | Format | Purpose |
|----------|-----------|--------|---------|
| mip0 | ½ display | R16G16B16A16_SFLOAT | Threshold output + accumulated upsample |
| mip1 | ¼ display | R16G16B16A16_SFLOAT | Medium blur |
| mip2 | ⅛ display | R16G16B16A16_SFLOAT | Wide blur |
| ping | ½ display | R16G16B16A16_SFLOAT | Gaussian temp buffer (reused for all mips) |

## Java Classes

| Class | Role |
|-------|------|
| `RtBloomPipeline.java` | Vulkan pipeline: 4 sub-pipelines (threshold, downsample, blur, upsample). Each with own descriptor set layout/pool/set/layout/pipeline. |
| `RtBloom.java` | Owns resources (mip[0..2] + ping images), calls `RtBloomPipeline` dispatch methods, sequences the full pyramid pass. `record(ctx, cmd, stack, hdrView, w, h)` called from `RtComposite.recordFrame()`. |

## Integration Point

`RtComposite.java` lines ~920:

```java
if (CausticaConfig.Rt.Bloom.ENABLED.value()) {
    bloom.record(ctx, cmd, stack, rrOutput.view, displayW, displayH);
}
```

Inserted between DLSS-RR barrier and auto-exposure. Bloom modifies `rrOutput` in-place, so bloom luminance is reflected in the exposure histogram.

## Configuration

`CausticaConfig.Rt.Bloom`:

| Parameter | Range | Default | UI |
|-----------|-------|---------|-----|
| `enabled` | bool | `true` | Toggle |
| `intensity` | 0.0–3.0 | `0.2` | Slider 0–300% |
| `threshold` | 0.0–10.0 | `0.0` | Slider 0.0–10.0 |
| `knee` | 0.0–2.0 | `1.0` | Slider 0.0–2.0 |
| `spread` | 0–3 | `3` | Slider 0–3 |

### Spread Behavior

Controls extra Gaussian H+V iterations on deep mips (fixed σ=3.0, kernel shape unchanged):

| Spread | mip0 | mip1 | mip2 | Effect |
|--------|------|------|------|--------|
| 0 | 1× | 1× | 1× | Tight bloom |
| 1 | 1× | 1× | 2× | Medium |
| 2 | 1× | 2× | 2× | Wide |
| 3 | 1× | 2× | 3× | Maximum spread |

Multiple passes compound naturally (no sigma stretching = no outline artifacts).

## Tuning Notes

- **threshold=0 + intensity=0.2 + spread=3**: Global diffusion filter. All pixels bloom weakly, creating soft light wrap and airy feel.
- **threshold=0.8 + intensity=0.5**: Traditional emissive-only bloom. Only bright sources glow.
- The knee parameter softens the threshold edge. At knee=1.0, transition spans ±1.0 around threshold.
