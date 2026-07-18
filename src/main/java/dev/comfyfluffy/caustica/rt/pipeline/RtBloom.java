package dev.comfyfluffy.caustica.rt.pipeline;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Circular bloom via separable Gaussian blur on a 3-level pyramid.
 *
 * <pre>
 *   hdr → threshold → mip0(½) → gauss(H+V) → ds → mip1(¼) → gauss(H+V) → ds → mip2(⅛) → gauss(H+V)
 *                                                                                          │
 *   hdr ← final ← mip0 ← us ← mip1 ← us ← mip2 ← ← ← ← ← ← ← ← ← ← ← ← ← ← ← ← ← ← ← ← ┘
 * </pre>
 *
 * Gaussian sigma = 3.0 (9-tap kernel) at each level, producing isotropic circular falloff.
 * The per-level blur + pyramid chain creates a natural multi-scale glow from tight (mip0)
 * to screen-wide (mip2 at ¹⁄₈ with cumulative ~55 display-pixel radius).
 */
public final class RtBloom {
    private static final int MIP_COUNT = 3; // mip0(½), mip1(¼), mip2(⅛)

    private RtImage[] mips = new RtImage[MIP_COUNT];
    private RtImage ping;     // temp buffer for gaussian (sized for largest mip = ½ display)
    private RtBloomPipeline pipeline;
    private int lastDisplayW = -1;
    private int lastDisplayH = -1;

    public boolean ready() {
        return pipeline != null && mips[0] != null && ping != null;
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
        ping = ctx.createStorageImage(mips[0].width, mips[0].height,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "bloom gauss ping " + mips[0].width + "x" + mips[0].height);
        pipeline = RtBloomPipeline.create(ctx);
        lastDisplayW = displayW;
        lastDisplayH = displayH;
    }

    /** Run separable Gaussian (H + V) on a mip image, using ping as temp. */
    private void gaussian(VkCommandBuffer cmd, MemoryStack stack, int w, int h, long mipView) {
        pipeline.setBlurImages(mipView, ping.view);
        pipeline.dispatchBlur(cmd, w, h, true);  // horizontal
        VulkanCommandEncoder.memoryBarrier(cmd, stack);
        pipeline.setBlurImages(ping.view, mipView);
        pipeline.dispatchBlur(cmd, w, h, false); // vertical
        VulkanCommandEncoder.memoryBarrier(cmd, stack);
    }

    public void record(RtContext ctx, VkCommandBuffer cmd, MemoryStack stack,
                       long hdrView, int displayW, int displayH) {
        if (!ready()) return;

        float threshold = CausticaConfig.Rt.Bloom.threshold();
        float knee = CausticaConfig.Rt.Bloom.knee();
        float intensity = CausticaConfig.Rt.Bloom.intensity();
        int spread = CausticaConfig.Rt.Bloom.spread();

        // 1. Threshold + 2×2 downsample: hdr → mip0
        pipeline.setThresholdImages(hdrView, mips[0].view);
        pipeline.dispatchThreshold(cmd, displayW, displayH, threshold, knee);
        VulkanCommandEncoder.memoryBarrier(cmd, stack);

        // Per-level: gaussian blur + downsample.
        // Spread adds extra gaussian iterations on deeper mips (mip1, mip2) to widen
        // the bloom naturally through multi-pass compounding at fixed sigma=3.0.
        for (int i = 0; i < MIP_COUNT; i++) {
            RtImage mip = mips[i];
            gaussian(cmd, stack, mip.width, mip.height, mip.view);

            // Extra blur on this mip based on spread:
            // spread=1 → 1 extra on mip2 only
            // spread=2 → 1 extra on mip1 + mip2
            // spread=3 → 2 extra on mip2, 1 extra on mip1
            int extra = 0;
            if (i == MIP_COUNT - 1) {
                extra = spread;           // mip2: spread passes extra
            } else if (i == MIP_COUNT - 2) {
                extra = Math.max(0, spread - 1); // mip1: spread-1 passes extra
            }
            for (int p = 0; p < extra; p++) {
                gaussian(cmd, stack, mip.width, mip.height, mip.view);
            }

            if (i < MIP_COUNT - 1) {
                RtImage next = mips[i + 1];
                pipeline.setDownsampleImages(mip.view, next.view);
                pipeline.dispatchDownsample(cmd, mip.width, mip.height);
                VulkanCommandEncoder.memoryBarrier(cmd, stack);
            }
        }

        // Upsample chain (energy-preserving, intensity=1.0)
        for (int i = MIP_COUNT - 1; i > 0; i--) {
            RtImage dst = mips[i - 1];
            RtImage src = mips[i];
            pipeline.setUpsampleImages(dst.view, src.view);
            pipeline.dispatchUpsample(cmd, dst.width, dst.height, 1.0f);
            VulkanCommandEncoder.memoryBarrier(cmd, stack);
        }

        // Final composite: mip0 → HDR
        pipeline.setUpsampleImages(hdrView, mips[0].view);
        pipeline.dispatchUpsample(cmd, displayW, displayH, intensity);
        VulkanCommandEncoder.memoryBarrier(cmd, stack);
    }

    public void destroy() {
        if (pipeline != null) { pipeline.destroy(); pipeline = null; }
        for (int i = 0; i < mips.length; i++) {
            if (mips[i] != null) { mips[i].destroy(); mips[i] = null; }
        }
        if (ping != null) { ping.destroy(); ping = null; }
        lastDisplayW = -1;
        lastDisplayH = -1;
    }
}
