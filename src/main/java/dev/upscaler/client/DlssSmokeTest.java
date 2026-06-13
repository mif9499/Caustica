package dev.upscaler.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.upscaler.UpscalerMod;
import dev.upscaler.mixin.GpuDeviceAccessor;
import dev.upscaler.ngx.NgxLibrary;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * M6 stage 1: prove the NGX shim ({@code ngxshim.dll}) loads through FFM and the
 * NGX core responds. Queries the Vulkan instance + device extensions NGX/DLSS
 * requires — a static call that needs neither a device nor NGX init, so it is the
 * earliest possible end-to-end check of the native stack (shim build + link +
 * load + driver NGX core). No rendering touched.
 *
 * <p>Crucially, the device-extension list this returns is what must be enabled at
 * {@code vkCreateDevice} for DLSS to work later; this test logs it so we can wire
 * those into the device-negotiation hook next.
 */
public final class DlssSmokeTest {
	private static final String SHIM_DLL = "ngxshim.dll";

	private DlssSmokeTest() {
	}

	public static boolean run() {
		Path shim = locate(SHIM_DLL);
		if (shim == null) {
			UpscalerMod.LOGGER.warn("DLSS smoke test skipped: {} not found in run dir natives/ or -Dupscaler.ngx.path", SHIM_DLL);
			return true;
		}
		// nvngx_dlss.dll must sit next to the shim for the create step later; warn now if absent.
		if (locate("nvngx_dlss.dll") == null) {
			UpscalerMod.LOGGER.warn("DLSS smoke test: nvngx_dlss.dll not found next to the shim — feature creation will fail later");
		}

		try {
			UpscalerMod.LOGGER.info("DLSS smoke test [stage 0]: loading {}", shim);
			NgxLibrary ngx = NgxLibrary.load(shim);

			List<String> deviceExts = queryExtensions(ngx, true);
			List<String> instanceExts = queryExtensions(ngx, false);

			if (deviceExts == null) {
				UpscalerMod.LOGGER.error("DLSS smoke test FAILED: NVSDK_NGX_VULKAN_RequiredExtensions returned error {} (is this an NVIDIA GPU with a current driver?)",
						ngx.lastResult());
				return true;
			}

			UpscalerMod.LOGGER.info("DLSS smoke test [stage 1]: NGX required instance extensions ({}): {}", instanceExts.size(), instanceExts);
			UpscalerMod.LOGGER.info("DLSS smoke test [stage 1]: NGX required device extensions ({}): {}", deviceExts.size(), deviceExts);
			UpscalerMod.LOGGER.info("DLSS smoke test: stage 1 PASSED (shim + NGX core reachable)");

			runStage2(ngx, shim.getParent());
		} catch (Throwable t) {
			UpscalerMod.LOGGER.error("DLSS smoke test FAILED", t);
		}
		return true;
	}

	/** Stage 2: init NGX against the live device, check DLSS availability + optimal settings. */
	private static void runStage2(NgxLibrary ngx, Path nativesDir) {
		if (!(((GpuDeviceAccessor) RenderSystem.getDevice()).upscaler$getBackend() instanceof VulkanDevice vulkanDevice)) {
			UpscalerMod.LOGGER.warn("DLSS smoke test [stage 2] skipped: not on the Vulkan backend");
			return;
		}

		VkDevice vkDevice = vulkanDevice.vkDevice();
		VkPhysicalDevice physicalDevice = vkDevice.getPhysicalDevice();
		VkInstance instance = physicalDevice.getInstance();

		Path dataPath = FabricLoader.getInstance().getGameDir().resolve("upscaler-ngx");
		try {
			Files.createDirectories(dataPath);
		} catch (Exception e) {
			UpscalerMod.LOGGER.warn("Could not create NGX data path {}", dataPath, e);
		}

		try (Arena arena = Arena.ofConfined()) {
			long gdpa;
			try (MemoryStack stack = MemoryStack.stackPush()) {
				gdpa = VK10.vkGetInstanceProcAddr(instance, stack.ASCII("vkGetDeviceProcAddr"));
			}

			MemorySegment dataPathW = wideString(arena, dataPath.toString());
			MemorySegment dllPathW = wideString(arena, nativesDir.toString());

			UpscalerMod.LOGGER.info("DLSS smoke test [stage 2]: initializing NGX (device=0x{}, dllPath={})",
					Long.toHexString(vkDevice.address()), nativesDir);
			int rc = ngx.init(0L, dataPathW,
					instance.address(), physicalDevice.address(), vkDevice.address(),
					0L, gdpa, dllPathW);
			if (ngxFailed(rc)) {
				UpscalerMod.LOGGER.error("DLSS smoke test [stage 2] FAILED: ngxshim_init -> 0x{} (lastResult 0x{})",
						Integer.toHexString(rc), Integer.toHexString(ngx.lastResult()));
				return;
			}
			UpscalerMod.LOGGER.info("DLSS smoke test [stage 2]: NGX init OK");

			boolean available = ngx.dlssAvailable();
			UpscalerMod.LOGGER.info("DLSS smoke test [stage 2]: DLSS available = {}", available);
			if (!available) {
				UpscalerMod.LOGGER.warn("DLSS smoke test [stage 2]: DLSS not available on this system; stopping");
				ngx.shutdown(vkDevice.address());
				return;
			}

			int displayWidth = 3840;
			int displayHeight = 2160;
			String[] names = {"MaxPerf", "Balanced", "MaxQuality", "UltraPerf", "UltraQuality", "DLAA"};
			MemorySegment outW = arena.allocate(ValueLayout.JAVA_INT);
			MemorySegment outH = arena.allocate(ValueLayout.JAVA_INT);
			MemorySegment outSharp = arena.allocate(ValueLayout.JAVA_FLOAT);
			for (int mode = 0; mode < names.length; mode++) {
				int qr = ngx.queryOptimal(displayWidth, displayHeight, mode, outW, outH, outSharp);
				if (ngxFailed(qr)) {
					UpscalerMod.LOGGER.info("DLSS smoke test [stage 2]: quality={} -> query failed (0x{})", names[mode], Integer.toHexString(qr));
					continue;
				}
				UpscalerMod.LOGGER.info("DLSS smoke test [stage 2]: quality={} @ {}x{} -> render {}x{} (sharpness {})",
						names[mode], displayWidth, displayHeight,
						outW.get(ValueLayout.JAVA_INT, 0), outH.get(ValueLayout.JAVA_INT, 0),
						String.format("%.2f", outSharp.get(ValueLayout.JAVA_FLOAT, 0)));
			}

			ngx.shutdown(vkDevice.address());
			UpscalerMod.LOGGER.info("DLSS smoke test: stage 2 PASSED (NGX init + DLSS available + optimal settings)");
		}
	}

	/** NVSDK_NGX_Result: failure when top 12 bits == 0xBAD (NVSDK_NGX_Result_Fail = 0xBAD00000). */
	private static boolean ngxFailed(int result) {
		return (result & 0xFFF00000) == 0xBAD00000;
	}

	/** Null-terminated UTF-16LE (wchar_t* on Windows). */
	private static MemorySegment wideString(Arena arena, String s) {
		byte[] utf16 = s.getBytes(StandardCharsets.UTF_16LE);
		MemorySegment seg = arena.allocate(utf16.length + 2L);
		MemorySegment.copy(utf16, 0, seg, ValueLayout.JAVA_BYTE, 0, utf16.length);
		seg.set(ValueLayout.JAVA_BYTE, utf16.length, (byte) 0);
		seg.set(ValueLayout.JAVA_BYTE, utf16.length + 1, (byte) 0);
		return seg;
	}

	private static List<String> queryExtensions(NgxLibrary ngx, boolean device) {
		try (Arena arena = Arena.ofConfined()) {
			int bufLen = 8192;
			MemorySegment buf = arena.allocate(bufLen);
			int count = ngx.requiredExtensions(device, buf, bufLen);
			if (count < 0) {
				return null;
			}
			String joined = buf.getString(0);
			var result = new ArrayList<String>();
			for (String s : joined.split("\n")) {
				if (!s.isBlank()) {
					result.add(s.trim());
				}
			}
			return result;
		}
	}

	private static Path locate(String name) {
		String override = System.getProperty("upscaler.ngx.path");
		if (override != null && name.equals(SHIM_DLL)) {
			Path p = Path.of(override);
			return Files.isRegularFile(p) ? p : null;
		}
		Path runDir = FabricLoader.getInstance().getGameDir();
		Path[] candidates = {runDir.resolve("natives").resolve(name), runDir.resolve(name)};
		for (Path candidate : candidates) {
			if (Files.isRegularFile(candidate)) {
				return candidate;
			}
		}
		return null;
	}
}
