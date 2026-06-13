package dev.upscaler.client;

import com.mojang.blaze3d.textures.GpuTexture;
import dev.upscaler.UpscalerMod;
import net.fabricmc.loader.api.FabricLoader;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

public final class SodiumCompat {
	private static final List<String> FFX_DEVICE_EXTENSIONS = List.of(
			"VK_KHR_get_memory_requirements2",
			"VK_KHR_dedicated_allocation");

	private static final boolean SODIUM_LOADED = FabricLoader.getInstance().isModLoaded("sodium");
	private static boolean extensionRegistrationAttempted;
	private static boolean loggedMissingApi;
	private static boolean loggedRawAccessFailure;
	private static Method setCullingViewProjectionOverride;
	private static Object rawVulkanAccess;
	private static Method vkImage;

	private SodiumCompat() {
	}

	public static void registerDeviceExtensions() {
		if (!SODIUM_LOADED || extensionRegistrationAttempted) {
			return;
		}

		extensionRegistrationAttempted = true;
		try {
			Class<?> apiClass = Class.forName("net.caffeinemc.mods.sodium.api.gpu.SodiumGpuApi");
			Object registry = apiClass.getMethod("extensions").invoke(null);
			Method requestDeviceExtension = registry.getClass().getMethod("requestDeviceExtension", String.class, boolean.class);

			for (String extension : FFX_DEVICE_EXTENSIONS) {
				requestDeviceExtension.invoke(registry, extension, true);
			}

			UpscalerMod.LOGGER.info("Registered FFX Vulkan device extensions through Sodium");
		} catch (ClassNotFoundException | NoSuchMethodException e) {
			logMissingApi();
		} catch (IllegalAccessException | InvocationTargetException e) {
			UpscalerMod.LOGGER.warn("Failed to register FFX Vulkan device extensions through Sodium", e);
		}
	}

	public static void setCullingViewProjectionOverride(Matrix4fc projection, Matrix4fc viewRotation) {
		if (!SODIUM_LOADED) {
			return;
		}

		try {
			if (setCullingViewProjectionOverride == null) {
				Class<?> accessClass = Class.forName("net.caffeinemc.mods.sodium.api.render.FrameContextAccess");
				setCullingViewProjectionOverride = accessClass.getMethod("setCullingViewProjectionOverride", Matrix4fc.class);
			}

			Matrix4f viewProjection = new Matrix4f(projection).mul(viewRotation);
			setCullingViewProjectionOverride.invoke(null, viewProjection);
		} catch (ClassNotFoundException | NoSuchMethodException e) {
			logMissingApi();
		} catch (IllegalAccessException | InvocationTargetException e) {
			UpscalerMod.LOGGER.warn("Failed to install Sodium culling projection override", e);
		}
	}

	public static Long vkImage(GpuTexture texture) {
		if (!SODIUM_LOADED) {
			return null;
		}

		try {
			if (rawVulkanAccess == null || vkImage == null) {
				Class<?> apiClass = Class.forName("net.caffeinemc.mods.sodium.api.gpu.SodiumGpuApi");
				rawVulkanAccess = apiClass.getMethod("rawVulkan").invoke(null);
				vkImage = rawVulkanAccess.getClass().getMethod("vkImage", GpuTexture.class);
			}

			return (Long) vkImage.invoke(rawVulkanAccess, texture);
		} catch (ClassNotFoundException | NoSuchMethodException e) {
			logMissingApi();
		} catch (IllegalAccessException | InvocationTargetException e) {
			if (!loggedRawAccessFailure) {
				loggedRawAccessFailure = true;
				UpscalerMod.LOGGER.warn("Sodium raw Vulkan texture access failed; falling back to local accessor", e);
			}
		}

		return null;
	}

	private static void logMissingApi() {
		if (!loggedMissingApi) {
			loggedMissingApi = true;
			UpscalerMod.LOGGER.info("Sodium is loaded without the upscaler API; using standalone upscaler hooks");
		}
	}
}
