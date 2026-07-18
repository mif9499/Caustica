package dev.comfyfluffy.caustica.rt.pipeline;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;

import static dev.comfyfluffy.caustica.rt.RtContext.check;

/**
 * Multi-pass bloom compute pipelines: threshold, downsample (pyramid), Kawase blur,
 * and upsample-composite (used for both intermediate pyramid and final HDR composite).
 */
public final class RtBloomPipeline {
    private static final String SHADER_DIR = "/caustica/rt/";

    private final RtContext ctx;

    // --- threshold ---
    private final long threshDescriptorSetLayout;
    private final long threshDescriptorPool;
    private final long threshDescriptorSet;
    private final long threshPipelineLayout;
    private final long threshPipeline;
    private long boundThreshInput;
    private long boundThreshOutput;

    // --- downsample ---
    private final long downsampleDescriptorSetLayout;
    private final long downsampleDescriptorPool;
    private final long downsampleDescriptorSet;
    private final long downsamplePipelineLayout;
    private final long downsamplePipeline;
    private long boundDownsampleInput;
    private long boundDownsampleOutput;

    // --- blur (Kawase) ---
    private final long blurDescriptorSetLayout;
    private final long blurDescriptorPool;
    private final long blurDescriptorSet;
    private final long blurPipelineLayout;
    private final long blurPipeline;
    private long boundBlurInput;
    private long boundBlurOutput;

    // --- upsample (intermediate + final composite) ---
    private final long upsampleDescriptorSetLayout;
    private final long upsampleDescriptorPool;
    private final long upsampleDescriptorSet;
    private final long upsamplePipelineLayout;
    private final long upsamplePipeline;
    private long boundUpsampleDst;
    private long boundUpsampleSrc;

    private boolean destroyed;

    private RtBloomPipeline(RtContext ctx,
                            long threshDsl, long threshPool, long threshSet, long threshLayout, long threshPipeline,
                            long downsampleDsl, long downsamplePool, long downsampleSet, long downsampleLayout, long downsamplePipeline,
                            long blurDsl, long blurPool, long blurSet, long blurLayout, long blurPipeline,
                            long upsampleDsl, long upsamplePool, long upsampleSet, long upsampleLayout, long upsamplePipeline) {
        this.ctx = ctx;
        this.threshDescriptorSetLayout = threshDsl;
        this.threshDescriptorPool = threshPool;
        this.threshDescriptorSet = threshSet;
        this.threshPipelineLayout = threshLayout;
        this.threshPipeline = threshPipeline;
        this.downsampleDescriptorSetLayout = downsampleDsl;
        this.downsampleDescriptorPool = downsamplePool;
        this.downsampleDescriptorSet = downsampleSet;
        this.downsamplePipelineLayout = downsampleLayout;
        this.downsamplePipeline = downsamplePipeline;
        this.blurDescriptorSetLayout = blurDsl;
        this.blurDescriptorPool = blurPool;
        this.blurDescriptorSet = blurSet;
        this.blurPipelineLayout = blurLayout;
        this.blurPipeline = blurPipeline;
        this.upsampleDescriptorSetLayout = upsampleDsl;
        this.upsampleDescriptorPool = upsamplePool;
        this.upsampleDescriptorSet = upsampleSet;
        this.upsamplePipelineLayout = upsampleLayout;
        this.upsamplePipeline = upsamplePipeline;
    }

    public static RtBloomPipeline create(RtContext ctx) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer p = stack.mallocLong(1);

            // ---- threshold: 2 STORAGE_IMAGE, float threshold + float knee ----
            VkDescriptorSetLayoutBinding.Buffer threshBinds = twoStorageImageBindings(stack);
            VkDescriptorSetLayoutCreateInfo threshDslci = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(threshBinds);
            check(VK10.vkCreateDescriptorSetLayout(vk, threshDslci, null, p), "vkCreateDescriptorSetLayout(bloom threshold)");
            long threshDsl = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, threshDsl, "bloom threshold dsl");
            long threshPool = createPool(vk, stack, 2, "threshold");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_POOL, threshPool, "bloom threshold pool");
            long threshSet = allocateSet(vk, stack, threshPool, threshDsl, "threshold");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, threshSet, "bloom threshold set");
            long threshLayout = createPipelineLayout(vk, stack, threshDsl, 2 * Integer.BYTES, "threshold");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT, threshLayout, "bloom threshold layout");
            long threshModule = loadModule(vk, stack, "bloom_threshold.comp.spv");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SHADER_MODULE, threshModule, "bloom threshold shader");
            long threshPipeline = createComputePipeline(vk, stack, threshLayout, threshModule, "threshold");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, threshPipeline, "bloom threshold pipeline");
            VK10.vkDestroyShaderModule(vk, threshModule, null);

            // ---- downsample: 2 STORAGE_IMAGE, no push constants ----
            VkDescriptorSetLayoutBinding.Buffer dsBinds = twoStorageImageBindings(stack);
            VkDescriptorSetLayoutCreateInfo dsDslci = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(dsBinds);
            check(VK10.vkCreateDescriptorSetLayout(vk, dsDslci, null, p), "vkCreateDescriptorSetLayout(bloom downsample)");
            long downsampleDsl = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, downsampleDsl, "bloom downsample dsl");
            long downsamplePool = createPool(vk, stack, 2, "downsample");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_POOL, downsamplePool, "bloom downsample pool");
            long downsampleSet = allocateSet(vk, stack, downsamplePool, downsampleDsl, "downsample");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, downsampleSet, "bloom downsample set");
            long downsampleLayout = createPipelineLayout(vk, stack, downsampleDsl, 0, "downsample");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT, downsampleLayout, "bloom downsample layout");
            long downsampleModule = loadModule(vk, stack, "bloom_downsample.comp.spv");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SHADER_MODULE, downsampleModule, "bloom downsample shader");
            long downsamplePipeline = createComputePipeline(vk, stack, downsampleLayout, downsampleModule, "downsample");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, downsamplePipeline, "bloom downsample pipeline");
            VK10.vkDestroyShaderModule(vk, downsampleModule, null);

            // ---- blur: 2 STORAGE_IMAGE, int iteration ----
            VkDescriptorSetLayoutBinding.Buffer blurBinds = twoStorageImageBindings(stack);
            VkDescriptorSetLayoutCreateInfo blurDslci = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(blurBinds);
            check(VK10.vkCreateDescriptorSetLayout(vk, blurDslci, null, p), "vkCreateDescriptorSetLayout(bloom blur)");
            long blurDsl = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, blurDsl, "bloom blur dsl");
            long blurPool = createPool(vk, stack, 2, "blur");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_POOL, blurPool, "bloom blur pool");
            long blurSet = allocateSet(vk, stack, blurPool, blurDsl, "blur");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, blurSet, "bloom blur set");
            long blurLayout = createPipelineLayout(vk, stack, blurDsl, Integer.BYTES, "blur");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT, blurLayout, "bloom blur layout");
            long blurModule = loadModule(vk, stack, "bloom_blur.comp.spv");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SHADER_MODULE, blurModule, "bloom blur shader");
            long blurPipeline = createComputePipeline(vk, stack, blurLayout, blurModule, "blur");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, blurPipeline, "bloom blur pipeline");
            VK10.vkDestroyShaderModule(vk, blurModule, null);

            // ---- upsample: 2 STORAGE_IMAGE, float intensity ----
            VkDescriptorSetLayoutBinding.Buffer upsampleBinds = twoStorageImageBindings(stack);
            VkDescriptorSetLayoutCreateInfo upsampleDslci = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(upsampleBinds);
            check(VK10.vkCreateDescriptorSetLayout(vk, upsampleDslci, null, p), "vkCreateDescriptorSetLayout(bloom upsample)");
            long upsampleDsl = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, upsampleDsl, "bloom upsample dsl");
            long upsamplePool = createPool(vk, stack, 2, "upsample");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_POOL, upsamplePool, "bloom upsample pool");
            long upsampleSet = allocateSet(vk, stack, upsamplePool, upsampleDsl, "upsample");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, upsampleSet, "bloom upsample set");
            long upsampleLayout = createPipelineLayout(vk, stack, upsampleDsl, Integer.BYTES, "upsample");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT, upsampleLayout, "bloom upsample layout");
            long upsampleModule = loadModule(vk, stack, "bloom_upsample.comp.spv");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SHADER_MODULE, upsampleModule, "bloom upsample shader");
            long upsamplePipeline = createComputePipeline(vk, stack, upsampleLayout, upsampleModule, "upsample");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, upsamplePipeline, "bloom upsample pipeline");
            VK10.vkDestroyShaderModule(vk, upsampleModule, null);

            return new RtBloomPipeline(ctx,
                    threshDsl, threshPool, threshSet, threshLayout, threshPipeline,
                    downsampleDsl, downsamplePool, downsampleSet, downsampleLayout, downsamplePipeline,
                    blurDsl, blurPool, blurSet, blurLayout, blurPipeline,
                    upsampleDsl, upsamplePool, upsampleSet, upsampleLayout, upsamplePipeline);
        }
    }

    // --- descriptor writes ---

    public void setThresholdImages(long hdrInputView, long bloomHalfView) {
        if (boundThreshInput == hdrInputView && boundThreshOutput == bloomHalfView) {
            return;
        }
        writeTwoStorageImages(threshDescriptorSet, hdrInputView, bloomHalfView);
        boundThreshInput = hdrInputView;
        boundThreshOutput = bloomHalfView;
    }

    public void setDownsampleImages(long srcView, long dstView) {
        if (boundDownsampleInput == srcView && boundDownsampleOutput == dstView) {
            return;
        }
        writeTwoStorageImages(downsampleDescriptorSet, srcView, dstView);
        boundDownsampleInput = srcView;
        boundDownsampleOutput = dstView;
    }

    public void setBlurImages(long inView, long outView) {
        if (boundBlurInput == inView && boundBlurOutput == outView) {
            return;
        }
        writeTwoStorageImages(blurDescriptorSet, inView, outView);
        boundBlurInput = inView;
        boundBlurOutput = outView;
    }

    public void setUpsampleImages(long dstView, long srcView) {
        if (boundUpsampleDst == dstView && boundUpsampleSrc == srcView) {
            return;
        }
        writeTwoStorageImages(upsampleDescriptorSet, dstView, srcView);
        boundUpsampleDst = dstView;
        boundUpsampleSrc = srcView;
    }

    // --- dispatch ---

    public void dispatchThreshold(VkCommandBuffer cmd, int displayW, int displayH, float threshold, float knee) {
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "bloom threshold")) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, threshPipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, threshPipelineLayout, 0,
                    stack.longs(threshDescriptorSet), null);
            ByteBuffer push = stack.malloc(2 * Integer.BYTES);
            push.putFloat(0, threshold);
            push.putFloat(4, knee);
            VK10.vkCmdPushConstants(cmd, threshPipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            int halfW = Math.max(1, (displayW + 1) / 2);
            int halfH = Math.max(1, (displayH + 1) / 2);
            VK10.vkCmdDispatch(cmd, (halfW + 15) / 16, (halfH + 15) / 16, 1);
        }
    }

    public void dispatchDownsample(VkCommandBuffer cmd, int srcW, int srcH) {
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "bloom downsample")) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, downsamplePipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, downsamplePipelineLayout, 0,
                    stack.longs(downsampleDescriptorSet), null);
            int dstW = Math.max(1, (srcW + 1) / 2);
            int dstH = Math.max(1, (srcH + 1) / 2);
            VK10.vkCmdDispatch(cmd, (dstW + 15) / 16, (dstH + 15) / 16, 1);
        }
    }

    /** Separable Gaussian blur pass. Set horizontal=true for horizontal, false for vertical. */
    public void dispatchBlur(VkCommandBuffer cmd, int w, int h, boolean horizontal) {
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "bloom gauss " + (horizontal ? "H" : "V"))) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, blurPipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, blurPipelineLayout, 0,
                    stack.longs(blurDescriptorSet), null);
            ByteBuffer push = stack.malloc(Integer.BYTES);
            push.putInt(0, horizontal ? 1 : 0);
            VK10.vkCmdPushConstants(cmd, blurPipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(cmd, (w + 15) / 16, (h + 15) / 16, 1);
        }
    }

    /** Upsample src into dst (dst += bilinear_sample(src) * intensity). */
    public void dispatchUpsample(VkCommandBuffer cmd, int dstW, int dstH, float intensity) {
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "bloom upsample")) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, upsamplePipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, upsamplePipelineLayout, 0,
                    stack.longs(upsampleDescriptorSet), null);
            ByteBuffer push = stack.malloc(Integer.BYTES);
            push.putFloat(0, intensity);
            VK10.vkCmdPushConstants(cmd, upsamplePipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(cmd, (dstW + 15) / 16, (dstH + 15) / 16, 1);
        }
    }

    public void destroy() {
        if (destroyed) return;
        VkDevice vk = ctx.vk();
        VK10.vkDestroyPipeline(vk, threshPipeline, null);
        VK10.vkDestroyPipelineLayout(vk, threshPipelineLayout, null);
        VK10.vkDestroyDescriptorPool(vk, threshDescriptorPool, null);
        VK10.vkDestroyDescriptorSetLayout(vk, threshDescriptorSetLayout, null);
        VK10.vkDestroyPipeline(vk, downsamplePipeline, null);
        VK10.vkDestroyPipelineLayout(vk, downsamplePipelineLayout, null);
        VK10.vkDestroyDescriptorPool(vk, downsampleDescriptorPool, null);
        VK10.vkDestroyDescriptorSetLayout(vk, downsampleDescriptorSetLayout, null);
        VK10.vkDestroyPipeline(vk, blurPipeline, null);
        VK10.vkDestroyPipelineLayout(vk, blurPipelineLayout, null);
        VK10.vkDestroyDescriptorPool(vk, blurDescriptorPool, null);
        VK10.vkDestroyDescriptorSetLayout(vk, blurDescriptorSetLayout, null);
        VK10.vkDestroyPipeline(vk, upsamplePipeline, null);
        VK10.vkDestroyPipelineLayout(vk, upsamplePipelineLayout, null);
        VK10.vkDestroyDescriptorPool(vk, upsampleDescriptorPool, null);
        VK10.vkDestroyDescriptorSetLayout(vk, upsampleDescriptorSetLayout, null);
        destroyed = true;
    }

    // --- helpers ---

    private static VkDescriptorSetLayoutBinding.Buffer twoStorageImageBindings(MemoryStack stack) {
        VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(2, stack);
        binds.get(0).binding(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
        binds.get(1).binding(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
        return binds;
    }

    private void writeTwoStorageImages(long descriptorSet, long view0, long view1) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer info0 = VkDescriptorImageInfo.calloc(1, stack);
            info0.get(0).imageView(view0).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer info1 = VkDescriptorImageInfo.calloc(1, stack);
            info1.get(0).imageView(view1).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
            writes.get(0).sType$Default().dstSet(descriptorSet).dstBinding(0)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(info0);
            writes.get(1).sType$Default().dstSet(descriptorSet).dstBinding(1)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(info1);
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
        }
    }

    private static long createPool(VkDevice vk, MemoryStack stack, int storageImages, String label) {
        VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(1, stack);
        sizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(storageImages);
        VkDescriptorPoolCreateInfo ci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes);
        LongBuffer p = stack.mallocLong(1);
        check(VK10.vkCreateDescriptorPool(vk, ci, null, p), "vkCreateDescriptorPool(bloom " + label + ")");
        return p.get(0);
    }

    private static long allocateSet(VkDevice vk, MemoryStack stack, long pool, long layout, String label) {
        VkDescriptorSetAllocateInfo ai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                .descriptorPool(pool).pSetLayouts(stack.longs(layout));
        LongBuffer pSet = stack.mallocLong(1);
        check(VK10.vkAllocateDescriptorSets(vk, ai, pSet), "vkAllocateDescriptorSets(bloom " + label + ")");
        return pSet.get(0);
    }

    private static long createPipelineLayout(VkDevice vk, MemoryStack stack, long setLayout, int pushBytes, String label) {
        VkPipelineLayoutCreateInfo ci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                .pSetLayouts(stack.longs(setLayout));
        if (pushBytes > 0) {
            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack);
            pcr.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(pushBytes);
            ci.pPushConstantRanges(pcr);
        }
        LongBuffer p = stack.mallocLong(1);
        check(VK10.vkCreatePipelineLayout(vk, ci, null, p), "vkCreatePipelineLayout(bloom " + label + ")");
        return p.get(0);
    }

    private static long createComputePipeline(VkDevice vk, MemoryStack stack, long layout, long module, String label) {
        VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
        VkComputePipelineCreateInfo.Buffer cpci = VkComputePipelineCreateInfo.calloc(1, stack);
        cpci.get(0).sType$Default().stage(stage).layout(layout);
        LongBuffer pPipeline = stack.mallocLong(1);
        check(VK10.vkCreateComputePipelines(vk, VK10.VK_NULL_HANDLE, cpci, null, pPipeline),
                "vkCreateComputePipelines(bloom " + label + ")");
        return pPipeline.get(0);
    }

    private static long loadModule(VkDevice vk, MemoryStack stack, String name) {
        byte[] bytes;
        try (InputStream in = RtBloomPipeline.class.getResourceAsStream(SHADER_DIR + name)) {
            if (in == null) {
                throw new IllegalStateException("missing SPIR-V resource: " + SHADER_DIR + name);
            }
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read SPIR-V resource: " + SHADER_DIR + name, e);
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length).put(bytes);
        code.flip();
        try {
            VkShaderModuleCreateInfo ci = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
            LongBuffer pModule = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(vk, ci, null, pModule), "vkCreateShaderModule(" + name + ")");
            return pModule.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }
}
