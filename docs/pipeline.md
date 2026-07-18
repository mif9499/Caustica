# Rendering Pipeline

## Frame Order

Each frame in `RtComposite.recordFrame()`, dispatched on a single Vulkan command buffer. Steps separated by `VulkanCommandEncoder.memoryBarrier()` (all images stay in `VK_IMAGE_LAYOUT_GENERAL`, no layout transitions needed between compute passes).

```
1. BLAS build               (entity geometry)
2. TLAS build + bind        (scene acceleration structure)
3. PATH TRACE               (world.rgen → output, render-res HDR R16G16B16A16_SFLOAT)
4. DLSS-RR evaluate         (denoise + upscale: output → rrOutput, display-res)
   ── or fallback blit ──   (when RR off: output → rrOutput, 1:1 copy)
5. BLOOM                    (rrOutput → rrOutput in-place, see bloom.md)
6. EXPOSURE                 (rrOutput → exposure 1×1 R32_SFLOAT, auto or manual)
7. DISPLAY MAPPING          (display.comp: rrOutput + exposure → displayImage R8G8B8A8_UNORM + hdrDisplayImage)
8. COPY TO MAIN TARGET      (vkCmdCopyImage: displayImage → Minecraft native color texture)
9. PRESENT                  (separate cmd buffer: HDR PQ blit or SDR)
```

## Pipeline Architecture

### Compute Pipelines (Java + GLSL → SPIR-V)

Every compute pipeline follows the same pattern:

| Layer | File |
|-------|------|
| GLSL shader | `shaders/<category>/<name>.comp` |
| SPIR-V output | `build/generated/shaders/caustica/rt/<name>.comp.spv` |
| Classpath resource | `/caustica/rt/<name>.comp.spv` |
| Java pipeline | `src/.../pipeline/Rt<Name>Pipeline.java` |

**Pipeline class template** (see `RtDisplayPipeline.java` for canonical example):

```
create(ctx):
  1. VkDescriptorSetLayoutBinding → define bindings (STORAGE_IMAGE, SAMPLER, STORAGE_BUFFER)
  2. vkCreateDescriptorSetLayout + debug label
  3. VkDescriptorPoolSize → vkCreateDescriptorPool + label
  4. vkAllocateDescriptorSets
  5. VkPushConstantRange → vkCreatePipelineLayout
  6. loadModule() reads SPIR-V from classpath → vkCreateShaderModule
  7. vkCreateComputePipelines
  8. vkDestroyShaderModule (no longer needed)

setImages/setResources():
  - Dirtiness-tracked: skip vkUpdateDescriptorSets if handles unchanged

dispatch():
  - vkCmdBindPipeline → vkCmdBindDescriptorSets → vkCmdPushConstants → vkCmdDispatch
  - Workgroup size: 16×16, dispatch groups = ceil(w/16) × ceil(h/16)

destroy():
  - vkDestroyPipeline → vkDestroyPipelineLayout → vkDestroyDescriptorPool → vkDestroyDescriptorSetLayout
```

### Multi-pass Pipelines

`RtExposurePipeline` and `RtBloomPipeline` are multi-pass:
- Multiple sub-pipelines (histogram + resolve, threshold + downsample + blur + upsample)
- Each sub-pipeline has its own descriptor set layout, pool, set, pipeline layout, pipeline
- Orchestrator (e.g., `RtExposure.record()`) sequences: bind → dispatch → barrier → bind → dispatch → barrier

## Image Resources

All RT-owned images via `RtContext.createStorageImage()`:

```
VkImageCreateInfo: 2D, OPTIMAL tiling, single mip/sample/layer
Usage: STORAGE | SAMPLED | TRANSFER_SRC | TRANSFER_DST
Layout: UNDEFINED → GENERAL (one-time transition in synchronous submit)
```

| Purpose | Format | Resolution |
|---------|--------|------------|
| Trace output | `R16G16B16A16_SFLOAT` | render res |
| DLSS-RR output | `R16G16B16A16_SFLOAT` | display res |
| Display (SDR) | `R8G8B8A8_UNORM` | display res |
| Display (HDR PQ) | `R16G16B16A16_SFLOAT` | display res |
| Guide: normal/roughness | `R16G16B16A16_SFLOAT` | render res |
| Guide: albedo | `R16G16B16A16_SFLOAT` | render res |
| Guide: depth | `R32_SFLOAT` | render res |
| Guide: motion | `R16G16_SFLOAT` | render res |
| Guide: specular albedo | `R16G16B16A16_SFLOAT` | render res |
| Guide: specular motion | `R16G16_SFLOAT` | render res |
| Exposure | `R32_SFLOAT` | 1×1 |
| Bloom mips | `R16G16B16A16_SFLOAT` | ½, ¼, ⅛ display res |

## Tone Mapping

### SDR Path: AgX

`display.comp`: HDR radiance × exposure → AgX inset matrix → log2 clamp → polynomial contrast curve → AgX outset matrix → clamp [0,1].

`applyLook()` (contrast 1.04, saturation 1.05) is available but commented out.

### HDR Path: Custom PQ (ST.2084)

Paper-white / highlight rolloff via Michaelis-Menten: `(k * hi) / (k + hi)` where `k = headroom - 1`. BT.709 → BT.2020 gamut conversion before PQ encode. Parameters: `paperWhiteNits` (default 200), `peakNits` (default 1000).

### Auto-Exposure

256-bin luminance histogram → 50%-95% weighted average → temporal adaptation (separate up/down rates). Default: key=0.18, EV range [-1.5, 2.0], adapt up=0.12s, adapt down=0.35s.

## Shader Compilation

`build.gradle` task `CompileShaders`:

| Source | Compiler | Output |
|--------|----------|--------|
| `*.comp`, `*.vert`, `*.frag` | `glslangValidator -V --target-env vulkan1.2 -g` | `<name>.<ext>.spv` |
| `*.slang` (ray tracing) | `slangc -target spirv -profile spirv_1_5` | `<name>.spv` |

Naming: `display.comp` → `display.comp.spv`, `world.rgen.slang` → `world.rgen.spv`.

Output placed in `build/generated/shaders/caustica/rt/` → classpath `/caustica/rt/*.spv`.

## Configuration

`CausticaConfig.java`: each setting resolves from `-Dcaustica.*` system property → `config/caustica.toml` → hardcoded default.

Setting types: `BooleanSetting`, `IntSetting`, `FloatSetting`, `StringSetting`.

UI sliders: `RtVideoOptions.java` builds `OptionInstance<?>[]`, injected into Video Settings screen via `VideoSettingsScreenMixin`. Config saved on screen close.
