package dev.comfyfluffy.caustica.mixin;

import dev.comfyfluffy.caustica.rt.water.BiomeWaterColors;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts {@link BiomeColors#getAverageWaterColor} so that Caustica's per-biome water
 * absorption config replaces vanilla's biome water colour. Covers the camera-submerged fallback
 * path; per-triangle water surface tint is handled directly in {@code RtFluidMesher}.
 *
 * <p>The returned ARGB int encodes per-channel per-metre extinction coefficients rather than a
 * display colour. The shader side reads these values directly as Beer–Lambert extinction.
 */
@Mixin(BiomeColors.class)
public class BiomeColorsMixin {

    @Inject(method = "getAverageWaterColor", at = @At("HEAD"), cancellable = true)
    private static void caustica$overrideWaterColor(
            BlockAndTintGetter level,
            BlockPos pos,
            CallbackInfoReturnable<Integer> cir) {
        float[] e = BiomeWaterColors.resolveExtinction(pos);
        cir.setReturnValue(BiomeWaterColors.packExtinction(e[0], e[1], e[2]));
    }
}
