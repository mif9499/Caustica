package dev.upscaler.rt;

import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Duck interface mixed into {@code BlockModelRenderState}: carries the blockState a {@code
 * BlockModelResolver.update} resolved, so the RT entity capture can re-mesh it from the world block-state
 * model set. The display block-model set ({@code getBlockModelSet()}) wraps some blocks (e.g. logs) in a
 * {@code SpecialBlockModelWrapper} whose special-renderer path the entity collector can't capture — so a
 * contained block (the sulfur cube's swallowed block) submitted nothing. Meshing from the world model set
 * (as falling blocks do) sidesteps that.
 */
public interface ContainedBlockSource {
    @Nullable
    BlockState upscaler$containedBlock();

    void upscaler$setContainedBlock(@Nullable BlockState state);
}
