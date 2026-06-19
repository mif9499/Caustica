package dev.upscaler.rt;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.upscaler.UpscalerMod;
import dev.upscaler.client.SodiumCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStream;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * P6.2a LabPBR specular ({@code _s}) ingestion for terrain. Vanilla MC builds no {@code _n}/{@code _s}
 * atlas, so we build our own <b>parallel {@code _s} atlas</b> that mirrors the block atlas's sprite
 * layout: a {@link NativeImage} sized to the block atlas, into which each sprite's {@code _s} texture is
 * blitted at the <em>same</em> rect the albedo occupies. The closest-hit then samples it at the exact
 * same UV it uses for albedo — so this needs just one extra plain sampler, no bindless (that is only for
 * the per-type entity textures).
 *
 * <p>Built <b>lazily</b> from the sprites terrain extraction already encounters
 * ({@code BakedQuad.materialInfo().sprite()} in {@link RtTerrain}) — no atlas enumeration. A sprite whose
 * {@code _s} map is missing is remembered as "no data", and that prim keeps the {@link RtMaterials}
 * heuristic (signalled per-prim via the free {@code mat.z} lane).
 *
 * <p>Upload reuses MC's own texture path ({@link DynamicTexture} → {@code writeToTexture}); the
 * {@code GpuTextureView} handle is stable across re-uploads, so the RT descriptor is bound once.
 */
public final class RtBlockMaterials {
    public static final RtBlockMaterials INSTANCE = new RtBlockMaterials();
    private static final Identifier TEX_ID = Identifier.fromNamespaceAndPath("upscaler", "rt/blocks_s");

    private NativeImage atlasImage;
    private DynamicTexture dynTex;
    private int atlasW, atlasH;
    private boolean dirty;
    // Per-sprite result cache: true = _s blitted into the atlas, false = no _s map (heuristic fallback).
    private final Map<TextureAtlasSprite, Boolean> seen = new IdentityHashMap<>();
    private boolean loggedFailure;

    private RtBlockMaterials() {
    }

    /**
     * (Re)create the parallel {@code _s} atlas sized to the current block atlas. Called when the world
     * pipeline is (re)created — the block atlas is already resolved by then, so the texture/view exist
     * immediately and can be bound once. No-op (leaves {@link #view()} at 0) if the atlas isn't ready.
     */
    public void reset() {
        seen.clear();
        dirty = false;
        if (dynTex != null) {
            dynTex.close();
            dynTex = null;
            atlasImage = null;
        }
        try {
            GpuTextureView atlas = Minecraft.getInstance().getTextureManager()
                    .getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
            atlasW = atlas.getWidth(0);
            atlasH = atlas.getHeight(0);
            if (atlasW <= 0 || atlasH <= 0) {
                return;
            }
            atlasImage = new NativeImage(atlasW, atlasH, true); // zeroed: unfilled texels are gated by mat.z
            dynTex = new DynamicTexture(() -> "rt_blocks_s", atlasImage); // creates + uploads the GpuTexture
        } catch (Throwable t) {
            warnOnce("RT _s atlas creation failed", t);
            dynTex = null;
            atlasImage = null;
        }
    }

    /**
     * Ensure this sprite's {@code _s} map is present in the parallel atlas. Returns true if {@code _s}
     * data is available for the sprite (so the shader should sample it), false if it should fall back to
     * the heuristic. Cached per sprite.
     */
    public boolean ensure(TextureAtlasSprite sprite) {
        if (sprite == null || atlasImage == null) {
            return false;
        }
        Boolean cached = seen.get(sprite);
        if (cached != null) {
            return cached;
        }
        boolean has = false;
        try {
            Identifier name = sprite.contents().name(); // e.g. minecraft:block/stone
            Identifier specLoc = Identifier.fromNamespaceAndPath(name.getNamespace(),
                    "textures/" + name.getPath() + "_s.png");
            Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(specLoc);
            if (res.isPresent()) {
                try (InputStream in = res.get().open(); NativeImage src = NativeImage.read(in)) {
                    blit(src, sprite);
                    dirty = true;
                    has = true;
                }
            }
        } catch (Throwable t) {
            warnOnce("RT _s load failed for a sprite", t);
        }
        seen.put(sprite, has);
        return has;
    }

    /**
     * Blit the {@code _s} source into the atlas at the sprite's content rect, nearest-sampled to the
     * sprite's resolution (the pack's {@code _s} may be a different size; animated sprites use frame 0 =
     * the top {@code width×height} region). Content origin derives from the sprite UVs (avoids the
     * private {@code padding} field): {@code contentX = round(u0 * atlasW)}.
     */
    private void blit(NativeImage src, TextureAtlasSprite sprite) {
        int w = sprite.contents().width();
        int h = sprite.contents().height();
        int cx = Math.round(sprite.getU0() * atlasW);
        int cy = Math.round(sprite.getV0() * atlasH);
        int sw = src.getWidth();
        int sh = Math.min(src.getHeight(), src.getWidth()); // animated _s strip: clamp to a square frame 0
        for (int dy = 0; dy < h; dy++) {
            int sy = Math.min(sh - 1, dy * sh / h);
            int ty = cy + dy;
            if (ty < 0 || ty >= atlasH) {
                continue;
            }
            for (int dx = 0; dx < w; dx++) {
                int sx = Math.min(sw - 1, dx * sw / w);
                int tx = cx + dx;
                if (tx < 0 || tx >= atlasW) {
                    continue;
                }
                atlasImage.setPixel(tx, ty, src.getPixel(sx, sy));
            }
        }
    }

    /** Re-upload the atlas if any sprite was added since the last flush. Call before the trace records. */
    public void flush() {
        if (dirty && dynTex != null) {
            dynTex.upload();
            dirty = false;
        }
    }

    /** Vulkan image-view handle of the parallel {@code _s} atlas, or 0 if not created. Stable across uploads. */
    public long view() {
        return dynTex != null ? vkImageView(dynTex.getTextureView()) : 0L;
    }

    private void warnOnce(String msg, Throwable t) {
        if (!loggedFailure) {
            loggedFailure = true;
            UpscalerMod.LOGGER.warn(msg, t);
        }
    }

    private static long vkImageView(GpuTextureView view) {
        Long sodiumHandle = SodiumCompat.vkImageView(view);
        if (sodiumHandle != null) {
            return sodiumHandle;
        }
        if (view instanceof VulkanGpuTextureView vulkanView) {
            return vulkanView.vkImageView();
        }
        return 0L;
    }
}
