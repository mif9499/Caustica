package dev.comfyfluffy.caustica.rt.pipeline;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Dual-filtering bloom via a 6-level 13-tap tent-filtered pyramid.
 *
 * <pre>
 *   hdr ──[threshold + 13-tap tent ds]──▶ mip0(½) ──[13-tap tent ds]──▶ mip1(¼) ──▶ ... ──▶ mip5(¹⁄₆₄)
 *                                                                                              │
 *   hdr ◀──[final composite]── mip0 ◀──[tent us]── mip1 ◀── ... ◀── mip5 ◀────────────────────┘
 * </pre>
 *
 * The 13-tap tent downsampling filter provides Gaussian-quality blur at each step (σ≈2.5
 * measured at the source resolution), eliminating the need for explicit separable-Gaussian
 * blur passes. The threshold is applied per-pixel at full display resolution before the
 * first downsample, preserving small light sources that a box-then-threshold approach
 * would discard.
 *
 * Pyramid depth is controlled by {@link CausticaConfig.Rt.Bloom#radius()}: radius=0 only
 * processes mip0 (tight 2-3 px glow), radius=5 processes all 6 mips (screen-wide bloom).
 *
 * @see <a href="https://www.iryoku.com/next-generation-post-processing-in-call-of-duty-advanced-warfare">
 *      Next-Generation Post Processing in Call of Duty (Karis 2014)</a>
 */
public final class RtBloom {
    private static final int MIP_COUNT = 6; // mip0(½), mip1(¼), mip2(⅛), mip3(¹⁄₁₆), mip4(¹⁄₃₂), mip5(¹⁄₆₄)

    private RtImage[] mips = new RtImage[MIP_COUNT];
    private RtBloomPipeline pipeline;
    private int lastDisplayW = -1;
    private int lastDisplayH = -1;

    public boolean ready() {
        return pipeline != null && mips[0] != null;
    }

    public void ensureResources(RtContext ctx, int displayW, int displayH) {
        if (!CausticaConfig.Rt.Bloom.ENABLED.value()) {
            destroy();
            return;
        }
        if (ready() && lastDisplayW == displayW && lastDisplayH == displayH) {
            return;
        }
        destroy();
        int w = displayW, h = displayH;
        for (int i = 0; i < MIP_COUNT; i++) {
            w = Math.max(1, (w + 1) / 2);
            h = Math.max(1, (h + 1) / 2);
            mips[i] = ctx.createStorageImage(w, h, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    "bloom mip" + i + " " + w + "x" + h);
        }
        pipeline = RtBloomPipeline.create(ctx);
        lastDisplayW = displayW;
        lastDisplayH = displayH;
    }

    public void record(RtContext ctx, VkCommandBuffer cmd, MemoryStack stack,
                       long hdrView, int displayW, int displayH) {
        if (!ready()) return;

        float threshold = CausticaConfig.Rt.Bloom.threshold();
        float knee = CausticaConfig.Rt.Bloom.knee();
        float intensity = CausticaConfig.Rt.Bloom.intensity();
        int radius = CausticaConfig.Rt.Bloom.radius();

        // 1. Combined threshold + downsample: hdr → mip0 (full-res threshold, 13-tap tent ds)
        pipeline.setThresholdImages(hdrView, mips[0].view);
        pipeline.dispatchThreshold(cmd, displayW, displayH, threshold, knee);
        VulkanCommandEncoder.memoryBarrier(cmd, stack);

        // 2. Downsample chain: mip0 → mip1 → ... → mip[radius]
        //    radius controls how deep we go — beyond the last level the pyramid is shallow.
        int activeLevels = Math.max(1, Math.min(radius + 1, MIP_COUNT));
        for (int i = 1; i < activeLevels; i++) {
            RtImage src = mips[i - 1];
            RtImage dst = mips[i];
            pipeline.setDownsampleImages(src.view, dst.view);
            pipeline.dispatchDownsample(cmd, src.width, src.height);
            VulkanCommandEncoder.memoryBarrier(cmd, stack);
        }

        // 3. Upsample chain: mip[N-1] → mip[N-2] → ... → mip0
        //    Intermediate upsamples use intensity=1.0 (additive merge, no scaling).
        for (int i = activeLevels - 1; i > 0; i--) {
            RtImage dst = mips[i - 1];
            RtImage src = mips[i];
            pipeline.setUpsampleImages(dst.view, src.view);
            pipeline.dispatchUpsample(cmd, dst.width, dst.height, 1.0f);
            VulkanCommandEncoder.memoryBarrier(cmd, stack);
        }

        // 4. Final composite: mip0 → HDR (× user intensity)
        pipeline.setUpsampleImages(hdrView, mips[0].view);
        pipeline.dispatchUpsample(cmd, displayW, displayH, intensity);
        VulkanCommandEncoder.memoryBarrier(cmd, stack);
    }

    public void destroy() {
        if (pipeline != null) { pipeline.destroy(); pipeline = null; }
        for (int i = 0; i < mips.length; i++) {
            if (mips[i] != null) { mips[i].destroy(); mips[i] = null; }
        }
        lastDisplayW = -1;
        lastDisplayH = -1;
    }
}
